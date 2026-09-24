//! Onion-only bounded transport. Android must terminate the dedicated process at lock.
use crate::wire::MAX_FRAME;
use anyhow::{Result, anyhow, ensure};
use arti_client::{BootstrapBehavior, TorClient};
use futures::StreamExt;
use safelog::DisplayRedacted;
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        Arc, Mutex, OnceLock, Weak,
        atomic::{AtomicU8, AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    runtime::{Handle, Runtime},
    sync::{OwnedSemaphorePermit, Semaphore, watch},
};
use tokio_util::compat::FuturesAsyncReadCompatExt;
use tor_config::ExplicitOrAuto;
use tor_hscrypto::pk::{HsId, HsIdKey, HsIdKeypair};
use tor_llcrypto::pk::ed25519;
use tor_rtcompat::PreferredRuntime;

const PORT: u16 = 4242;
const MAX_CONNECTIONS: usize = 128;
/// Onion service nickname of the messaging transport. Arti keys the identity keypair in its
/// keystore by this name, so it is also what `forget_onion_key` has to erase at teardown.
const NICKNAME: &str = "nomessages";
/// Transport lifecycle codes carried by `Transport::state`.
/// STOPPED is terminal: a late status event must never resurrect a torn-down generation.
const STATE_STOPPED: u8 = 0;
const STATE_BOOTSTRAPPING: u8 = 1;
const STATE_READY: u8 = 2;
const STATE_PUBLISHING: u8 = 3;
/// Upper bound for one `await_ready` call, independent of the 180s bootstrap budget.
const MAX_READY_TIMEOUT_MS: i32 = 300_000;
type Client = TorClient<PreferredRuntime>;
type PendingWrite = (Vec<u8>, tokio::sync::oneshot::Sender<bool>);
#[derive(Clone)]
struct Connection {
    send: tokio::sync::mpsc::Sender<PendingWrite>,
    cancel: watch::Sender<bool>,
}
type Connections = Arc<Mutex<HashMap<u64, Connection>>>;
static CURRENT: OnceLock<Mutex<Option<Arc<Transport>>>> = OnceLock::new();
/// Weak handle on the Arti host shared by the messaging transport and the doorbell.
/// Weak, not strong: the host must die as soon as its last real owner lets go, so a
/// plain `stop` with no doorbell running still tears the whole Tor stack down.
static HOST: OnceLock<Mutex<Weak<TorHost>>> = OnceLock::new();
static IDS: AtomicU64 = AtomicU64::new(1);
static PEER_USES: AtomicU64 = AtomicU64::new(1);

pub struct Frame {
    pub connection_id: u64,
    pub payload: Vec<u8>,
}

/// The tokio runtime plus the Arti client every onion service in this process runs on.
///
/// Both the messaging transport and the doorbell (`crate::doorbell`) hold an `Arc` of the
/// same host, which is what makes their lifecycles independent: dropping the messaging
/// `Transport` no longer shuts the runtime down while the doorbell still needs it, and a
/// doorbell-only process can keep serving knocks after the vault locked. Arti also takes a
/// lock on its state directory, so a second `TorClient` over the same directories would fail
/// outright — sharing one host is the only correct way to run two services here.
pub(crate) struct TorHost {
    runtime: Mutex<Option<Runtime>>,
    pub(crate) handle: Handle,
    pub(crate) client: Arc<Client>,
}
impl Drop for TorHost {
    fn drop(&mut self) {
        // Only reached once no transport and no doorbell holds the host any more.
        if let Some(runtime) = self.runtime.get_mut().ok().and_then(Option::take) {
            // Shutting a runtime down from inside one of its own worker threads panics.
            // No task is given an `Arc<TorHost>`, so this is defensive only.
            if Handle::try_current().is_ok() {
                std::thread::spawn(move || runtime.shutdown_timeout(Duration::from_secs(2)));
            } else {
                runtime.shutdown_timeout(Duration::from_secs(2));
            }
        }
    }
}

/// Returns the Arti host currently shared in this process, if any is still alive.
pub(crate) fn active_host() -> Option<Arc<TorHost>> {
    HOST.get_or_init(|| Mutex::new(Weak::new()))
        .lock()
        .ok()?
        .upgrade()
}

/// Returns the shared Arti host, building it (runtime + unbootstrapped `TorClient`) on first use.
///
/// Arguments are always validated, including when an existing host is reused, so bad paths or
/// bridges are rejected identically whichever service asks first. When a host already exists the
/// directories and bridges of this call are ignored: a live `TorClient` cannot be reconfigured
/// onto another state directory, and the caller that created it holds the state-directory lock.
pub(crate) fn host(state_dir: &str, cache_dir: &str, bridges: &[String]) -> Result<Arc<TorHost>> {
    ensure!(
        Path::new(state_dir).is_absolute()
            && Path::new(cache_dir).is_absolute()
            && state_dir != cache_dir,
        "Distinct absolute state/cache paths required"
    );
    ensure!(
        bridges.len() <= 16 && bridges.iter().all(|b| b.len() <= 1024),
        "Invalid bridges"
    );
    let mut slot = HOST
        .get_or_init(|| Mutex::new(Weak::new()))
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?;
    if let Some(existing) = slot.upgrade() {
        return Ok(existing);
    }
    let mut cfg = arti_client::config::TorClientConfigBuilder::from_directories(state_dir, cache_dir);
    cfg.storage()
        .keystore()
        .primary()
        .kind(ExplicitOrAuto::Explicit(
            tor_keymgr::config::ArtiKeystoreKind::Ephemeral,
        ));
    for bridge in bridges {
        // Only plain OR bridges. Unsupported transports are rejected, never bypassed.
        let first = bridge
            .split_whitespace()
            .next()
            .ok_or_else(|| anyhow!("Empty bridge"))?;
        ensure!(
            first.parse::<std::net::SocketAddr>().is_ok(),
            "Only plain Tor bridges are supported"
        );
        cfg.bridges()
            .bridges()
            .push(bridge.parse::<arti_client::config::BridgeConfigBuilder>()?);
    }
    // Arti leaves Rustls provider selection to its embedding application.
    // Install it before constructing PreferredRuntime/TorClient; no sockets open here.
    static TLS_PROVIDER: OnceLock<()> = OnceLock::new();
    TLS_PROVIDER.get_or_init(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    let handle = runtime.handle().clone();
    let client: Arc<Client> = {
        let _guard = runtime.enter();
        TorClient::builder()
            .config(cfg.build()?)
            .bootstrap_behavior(BootstrapBehavior::Manual)
            .create_unbootstrapped()?
    };
    let built = Arc::new(TorHost {
        runtime: Mutex::new(Some(runtime)),
        handle,
        client,
    });
    *slot = Arc::downgrade(&built);
    Ok(built)
}

/// Erases the onion identity keypair Arti holds for `nickname` in the shared client's keystore.
///
/// Why this exists: `launch_onion_service_with_hsid` *inserts* the identity keypair under the
/// service nickname and, by Arti's own design, refuses to overwrite an entry that is already
/// there ("Key already exists"). That keystore belongs to the shared `TorHost`, which — since the
/// doorbell was introduced — deliberately outlives any single onion service: keeping it alive
/// across a vault lock is exactly what lets the next unlock skip a 40-60s bootstrap. Dropping a
/// `RunningOnionService` releases the service but not its keystore entry, so a second launch of
/// the same nickname inside one process was rejected before it could even start. Erasing the entry
/// as part of the teardown puts the keystore back in step with the services actually running.
///
/// Why this is safe: the entry is derived deterministically from the vault seed (`key`), so it
/// carries no state that could be lost — the next `start`/`launch` rebuilds it bit for bit and
/// therefore republishes the *same* onion address. And only the nickname handed in is touched, so
/// the messaging service and the doorbell never disturb each other's identity.
///
/// Errors are deliberately flattened to fixed strings: nothing from the keystore may reach a
/// message that can surface outside native code.
pub(crate) fn forget_onion_key(host: &TorHost, nickname: &str) -> Result<()> {
    let nickname: tor_hsservice::HsNickname = nickname
        .to_owned()
        .try_into()
        .map_err(|_| anyhow!("Invalid onion service nickname"))?;
    let removed: Option<HsIdKeypair> = host
        .client
        .keymgr()
        .map_err(|_| anyhow!("Keystore unavailable"))?
        .remove(
            &tor_hsservice::HsIdKeypairSpecifier::new(nickname),
            tor_keymgr::KeystoreSelector::Primary,
        )
        .map_err(|_| anyhow!("Keystore removal failed"))?;
    // Dropping it here zeroizes the secret half; `None` simply means the service never launched.
    drop(removed);
    Ok(())
}

struct Transport {
    host: Arc<TorHost>,
    onion: String,
    state: Arc<AtomicU8>,
    // Mirrors STATE_READY so a waiter parks instead of polling the atomic.
    ready: watch::Sender<bool>,
    cancel: watch::Sender<bool>,
    connections: Connections,
    peers: Mutex<HashMap<String, (Arc<Client>, u64)>>,
    slots: Arc<Semaphore>,
    events: tokio::sync::mpsc::Sender<Frame>,
    incoming: Mutex<tokio::sync::mpsc::Receiver<Frame>>,
    service: Mutex<Option<Arc<tor_hsservice::RunningOnionService>>>,
}
fn current() -> Result<Arc<Transport>> {
    CURRENT
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .clone()
        .ok_or_else(|| anyhow!("Transport stopped"))
}
fn take_if_current<T>(slot: &Mutex<Option<Arc<T>>>, expected: &Arc<T>) -> Result<Option<Arc<T>>> {
    let mut current = slot.lock().map_err(|_| anyhow!("Transport unavailable"))?;
    if current
        .as_ref()
        .is_some_and(|value| Arc::ptr_eq(value, expected))
    {
        Ok(current.take())
    } else {
        Ok(None)
    }
}
fn key(seed: &[u8]) -> Result<HsIdKeypair> {
    let seed: &[u8; 32] = seed
        .try_into()
        .map_err(|_| anyhow!("Onion seed must be 32 bytes"))?;
    let kp = ed25519::Keypair::from_bytes(seed);
    Ok(HsIdKeypair::from(ed25519::ExpandedKeypair::from(&kp)))
}
/// Same derivation as `address`, but keeps the private half: the doorbell launches its own
/// onion service from its own seed and needs the keypair, not the printable address.
pub(crate) fn keypair(seed: &[u8]) -> Result<HsIdKeypair> {
    key(seed)
}
pub fn address(seed: &[u8]) -> Result<String> {
    Ok(HsIdKey::from(&key(seed)?)
        .id()
        .display_unredacted()
        .to_string())
}
pub fn validate_onion(onion: &str) -> Result<()> {
    ensure!(
        onion.len() == 62
            && onion.ends_with(".onion")
            && onion
                .bytes()
                .all(|c| c.is_ascii_lowercase() || (b'2'..=b'7').contains(&c) || c == b'.'),
        "Only canonical v3 onions are allowed"
    );
    let _: HsId = onion
        .parse()
        .map_err(|_| anyhow!("Invalid v3 onion checksum"))?;
    Ok(())
}
fn state_name(code: u8) -> &'static str {
    match code {
        STATE_BOOTSTRAPPING => "BOOTSTRAPPING",
        STATE_READY => "READY",
        STATE_PUBLISHING => "PUBLISHING",
        _ => "STOPPED",
    }
}
/// Moves the transport between PUBLISHING and READY without ever leaving STOPPED.
/// The onion-service status stream is asynchronous and can fire after `stop`.
fn advance_state(state: &AtomicU8, target: u8) {
    let mut observed = state.load(Ordering::Acquire);
    while observed != STATE_STOPPED && observed != target {
        match state.compare_exchange(observed, target, Ordering::AcqRel, Ordering::Acquire) {
            Ok(_) => return,
            Err(actual) => observed = actual,
        }
    }
}
pub fn status() -> &'static str {
    match current().map(|s| s.state.load(Ordering::Acquire)) {
        Ok(code) => state_name(code),
        Err(_) => state_name(STATE_STOPPED),
    }
}

/// Blocks until the onion descriptor is published and the service is reachable.
/// Returns the status reached; "PUBLISHING" means the deadline elapsed first, which is
/// not a failure: publication keeps running and a later call can observe READY.
pub fn await_ready(timeout_ms: i32) -> Result<&'static str> {
    ensure!(
        (0..=MAX_READY_TIMEOUT_MS).contains(&timeout_ms),
        "Invalid readiness timeout"
    );
    let s = current()?;
    s.host.handle.block_on(async {
        let mut cancelled = s.cancel.subscribe();
        ensure!(!*cancelled.borrow(), "Transport stopped");
        let mut ready = s.ready.subscribe();
        let published = async {
            loop {
                if *ready.borrow_and_update() {
                    return Ok::<(), anyhow::Error>(());
                }
                ready
                    .changed()
                    .await
                    .map_err(|_| anyhow!("Transport stopped"))?;
            }
        };
        tokio::select! {
            biased;
            _ = cancelled.changed() => return Err(anyhow!("Transport stopped")),
            outcome = tokio::time::timeout(Duration::from_millis(timeout_ms as u64), published) => {
                if let Ok(result) = outcome { result?; }
            }
        }
        ensure!(!*cancelled.borrow(), "Transport stopped");
        Ok(())
    })?;
    Ok(state_name(s.state.load(Ordering::Acquire)))
}
pub fn onion() -> Result<String> {
    Ok(current()?.onion.clone())
}

pub fn start(seed: &[u8], state_dir: &str, cache_dir: &str, bridges: &[String]) -> Result<String> {
    let hsid = key(seed)?;
    let onion = HsIdKey::from(&hsid).id().display_unredacted().to_string();
    // Reuses the Arti host the doorbell may already have bootstrapped, and validates the
    // paths/bridges either way.
    let host = host(state_dir, cache_dir, bridges)?;
    let (cancel, _) = watch::channel(false);
    let (ready, _) = watch::channel(false);
    let (events, incoming) = tokio::sync::mpsc::channel(32);
    let transport = Arc::new(Transport {
        host,
        onion: onion.clone(),
        state: Arc::new(AtomicU8::new(STATE_BOOTSTRAPPING)),
        ready,
        cancel,
        connections: Arc::new(Mutex::new(HashMap::new())),
        peers: Mutex::new(HashMap::new()),
        slots: Arc::new(Semaphore::new(MAX_CONNECTIONS)),
        events,
        incoming: Mutex::new(incoming),
        service: Mutex::new(None),
    });
    {
        let mut slot = CURRENT
            .get_or_init(|| Mutex::new(None))
            .lock()
            .map_err(|_| anyhow!("Transport unavailable"))?;
        ensure!(slot.is_none(), "Transport already started");
        *slot = Some(transport.clone());
    }
    let result = transport.host.handle.block_on(async {
        let mut cancelled = transport.cancel.subscribe();
        ensure!(!*cancelled.borrow(), "Transport stopped");
        tokio::select! {
            biased;
            _ = cancelled.changed() => return Err(anyhow!("Transport stopped")),
            result = tokio::time::timeout(Duration::from_secs(180), transport.host.client.bootstrap()) => result??,
        }
        ensure!(!*cancelled.borrow(), "Transport stopped");
        let config = tor_hsservice::config::OnionServiceConfigBuilder::default().nickname(NICKNAME.to_owned().try_into()?).build()?;
        let (service, requests) = transport.host.client.launch_onion_service_with_hsid(config, hsid)?.ok_or_else(|| anyhow!("Onion service disabled"))?;
        let status_events = service.status_events();
        *transport.service.lock().map_err(|_| anyhow!("Transport unavailable"))? = Some(service);
        let connections = transport.connections.clone(); let events = transport.events.clone(); let slots = transport.slots.clone();
        let handle = transport.host.handle.clone();
        transport.host.handle.spawn(async move {
            let requests = tor_hsservice::handle_rend_requests(requests);
            tokio::pin!(requests);
            loop {
                let request = tokio::select! {
                    biased;
                    _ = cancelled.changed() => break,
                    request = requests.next() => match request { Some(r) => r, None => break },
                };
                let accepted_port = matches!(request.request(), tor_proto::stream::IncomingStreamRequest::Begin(begin) if begin.port() == PORT);
                let permit = slots.clone().try_acquire_owned();
                if !accepted_port || permit.is_err() { let _ = request.shutdown_circuit(); continue; }
                let connections = connections.clone(); let events = events.clone(); let child_cancel = cancelled.clone();
                let child_handle = handle.clone();
                handle.spawn(async move {
                    if let Ok(Ok(stream)) = tokio::time::timeout(Duration::from_secs(30), request.accept(tor_cell::relaycell::msg::Connected::new_empty())).await {
                        let _ = register_stream(&child_handle, stream.compat(), connections, events, child_cancel, permit.expect("checked permit"));
                    }
                });
            }
        });
        ensure!(!*transport.cancel.borrow(), "Transport stopped");
        // Launching only starts introduction-point setup and descriptor upload. The service is
        // not reachable until tor-hsservice reports a fully reachable state, so PUBLISHING is
        // published here and READY is left to the status watcher below.
        transport.state.store(STATE_PUBLISHING, Ordering::Release);
        let status_state = transport.state.clone();
        let status_ready = transport.ready.clone();
        let mut status_cancel = transport.cancel.subscribe();
        transport.host.handle.spawn(async move {
            tokio::pin!(status_events);
            loop {
                let status = tokio::select! {
                    biased;
                    _ = status_cancel.changed() => break,
                    event = status_events.next() => match event { Some(status) => status, None => break },
                };
                // `is_fully_reachable` is tor-hsservice's own definition of "descriptor published
                // and introduction points satisfied" (State::Running or State::DegradedReachable).
                let reachable = status.state().is_fully_reachable();
                advance_state(&status_state, if reachable { STATE_READY } else { STATE_PUBLISHING });
                if status_state.load(Ordering::Acquire) != STATE_STOPPED {
                    status_ready.send_replace(reachable);
                }
            }
            status_ready.send_replace(false);
        });
        Ok::<(), anyhow::Error>(())
    });
    if let Err(error) = result {
        // Stop only our generation; a later replacement must not be affected.
        if let Some(failed) = take_if_current(CURRENT.get_or_init(|| Mutex::new(None)), &transport)?
        {
            shutdown_transport(failed)?;
        }
        return Err(error);
    }
    Ok(onion)
}

fn register_stream<S>(
    handle: &Handle,
    stream: S,
    connections: Connections,
    events: tokio::sync::mpsc::Sender<Frame>,
    mut cancel: watch::Receiver<bool>,
    permit: OwnedSemaphorePermit,
) -> Result<u64>
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let id = IDS.fetch_add(1, Ordering::Relaxed);
    ensure!(id != 0, "Connection counter exhausted");
    ensure!(!*cancel.borrow(), "Transport stopped");
    let (send, mut outgoing) = tokio::sync::mpsc::channel::<PendingWrite>(4);
    let (close, mut closed) = watch::channel(false);
    connections
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .insert(
            id,
            Connection {
                send,
                cancel: close,
            },
        );
    handle.spawn(async move {
        let _permit = permit;
        let (mut reader, mut writer) = tokio::io::split(stream);
        let (activity, mut activity_rx) = watch::channel(());
        let read = async {
            loop {
                let len = reader.read_u32().await? as usize;
                if len == 0 || len > MAX_FRAME {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "Invalid frame length",
                    ));
                }
                let mut payload = vec![0; len];
                reader.read_exact(&mut payload).await?;
                activity.send_replace(());
                if events
                    .send(Frame {
                        connection_id: id,
                        payload,
                    })
                    .await
                    .is_err()
                {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::WouldBlock,
                        "Receive queue closed",
                    ));
                }
            }
            #[allow(unreachable_code)]
            Ok::<(), std::io::Error>(())
        };
        let write = async {
            while let Some((payload, acknowledgement)) = outgoing.recv().await {
                let written = async {
                    writer.write_u32(payload.len() as u32).await?;
                    writer.write_all(&payload).await?;
                    writer.flush().await?;
                    Ok::<(), std::io::Error>(())
                }
                .await;
                let _ = acknowledgement.send(written.is_ok());
                written?;
                activity.send_replace(());
            }
            Ok::<(), std::io::Error>(())
        };
        let idle = async {
            loop {
                if !matches!(
                    tokio::time::timeout(Duration::from_secs(120), activity_rx.changed()).await,
                    Ok(Ok(()))
                ) {
                    break;
                }
            }
        };
        tokio::select! {
            biased;
            _ = cancel.changed() => {},
            _ = closed.changed() => {},
            _ = idle => {},
            _ = read => {},
            _ = write => {},
        }
        if let Ok(mut connections) = connections.lock() {
            connections.remove(&id);
        }
    });
    Ok(id)
}
pub fn send(onion: &str, frame: &[u8]) -> Result<u64> {
    validate_onion(onion)?;
    ensure!(
        !frame.is_empty() && frame.len() <= MAX_FRAME,
        "Invalid frame size"
    );
    let s = current()?;
    // Outbound rendezvous needs a bootstrapped client, not our own published descriptor, so
    // PUBLISHING still allows sending. Only inbound reachability waits for READY.
    ensure!(
        matches!(
            s.state.load(Ordering::Acquire),
            STATE_READY | STATE_PUBLISHING
        ),
        "Transport not ready"
    );
    let client = {
        let mut peers = s
            .peers
            .lock()
            .map_err(|_| anyhow!("Transport unavailable"))?;
        let used = PEER_USES.fetch_add(1, Ordering::Relaxed);
        if let Some((client, last_used)) = peers.get_mut(onion) {
            *last_used = used;
            client.clone()
        } else {
            if peers.len() >= 128 {
                if let Some(evicted) = peers
                    .iter()
                    .min_by_key(|(_, (_, last_used))| *last_used)
                    .map(|(name, _)| name.clone())
                {
                    peers.remove(&evicted);
                }
            }
            let client = s.host.client.isolated_client();
            peers.insert(onion.to_owned(), (client.clone(), used));
            client
        }
    };
    let permit = s
        .slots
        .clone()
        .try_acquire_owned()
        .map_err(|_| anyhow!("Connection limit exceeded"))?;
    s.host.handle.block_on(async {
        let mut cancelled = s.cancel.subscribe();
        ensure!(!*cancelled.borrow(), "Transport stopped");
        let connect_write = async {
            let mut stream = client.connect((onion, PORT)).await?.compat();
            stream.write_u32(frame.len() as u32).await?;
            stream.write_all(frame).await?;
            stream.flush().await?;
            Ok::<_, anyhow::Error>(stream)
        };
        let stream = tokio::select! {
            biased;
            _ = cancelled.changed() => return Err(anyhow!("Transport stopped")),
            value = tokio::time::timeout(Duration::from_secs(40), connect_write) => value??,
        };
        ensure!(!*cancelled.borrow(), "Transport stopped");
        register_stream(
            &s.host.handle,
            stream,
            s.connections.clone(),
            s.events.clone(),
            cancelled,
            permit,
        )
    })
}
pub fn poll(timeout_ms: i32) -> Result<Option<Frame>> {
    ensure!((0..=30000).contains(&timeout_ms), "Invalid poll timeout");
    let s = current()?;
    s.host.handle.block_on(async {
        let mut cancelled = s.cancel.subscribe();
        ensure!(!*cancelled.borrow(), "Transport stopped");
        let mut receiver = s.incoming.lock().map_err(|_| anyhow!("Transport unavailable"))?;
        tokio::select! {
            biased;
            _ = cancelled.changed() => Err(anyhow!("Transport stopped")),
            value = tokio::time::timeout(Duration::from_millis(timeout_ms as u64), receiver.recv()) => match value {
                Ok(Some(frame)) => Ok(Some(frame)), Ok(None) => Err(anyhow!("Transport stopped")), Err(_) => Ok(None),
            },
        }
    })
}
pub fn reply(id: u64, frame: &[u8]) -> Result<()> {
    ensure!(
        !frame.is_empty() && frame.len() <= MAX_FRAME,
        "Invalid frame size"
    );
    let s = current()?;
    let connection = s
        .connections
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .get(&id)
        .cloned()
        .ok_or_else(|| anyhow!("Connection closed"))?;
    let result = s.host.handle.block_on(async {
        let mut cancelled = s.cancel.subscribe();
        ensure!(!*cancelled.borrow(), "Transport stopped");
        let write = async {
            let (ack, written) = tokio::sync::oneshot::channel();
            connection
                .send
                .send((frame.to_vec(), ack))
                .await
                .map_err(|_| anyhow!("Connection closed"))?;
            ensure!(
                written.await.map_err(|_| anyhow!("Connection closed"))?,
                "Connection write failed"
            );
            Ok::<(), anyhow::Error>(())
        };
        tokio::select! {
            biased;
            _ = cancelled.changed() => Err(anyhow!("Transport stopped")),
            result = tokio::time::timeout(Duration::from_secs(40), write) => result?,
        }
    });
    if result.is_err() {
        connection.cancel.send_replace(true);
        if let Ok(mut connections) = s.connections.lock() {
            connections.remove(&id);
        }
    }
    result
}
pub fn close_connection(id: u64) -> Result<()> {
    let s = current()?;
    if let Some(connection) = s
        .connections
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .remove(&id)
    {
        connection.cancel.send_replace(true);
    }
    Ok(())
}
pub fn stop() -> Result<()> {
    let s = CURRENT
        .get_or_init(|| Mutex::new(None))
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .take();
    if let Some(s) = s {
        shutdown_transport(s)?;
    }
    Ok(())
}
fn shutdown_transport(s: Arc<Transport>) -> Result<()> {
    s.state.store(STATE_STOPPED, Ordering::Release);
    s.ready.send_replace(false);
    s.cancel.send_replace(true);
    s.connections
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .clear();
    s.peers
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .clear();
    s.service
        .lock()
        .map_err(|_| anyhow!("Transport unavailable"))?
        .take();
    // The service object and its keystore entry go together. Kept last, after every other
    // teardown step, so a keystore failure can never leave a half-stopped transport behind: by
    // this point the state is STOPPED, the cancel channel fired and the connections are gone.
    // Without this, a second `start` in the same process — the whole point of the doorbell
    // keeping the Arti host alive through a lock — died on "Key already exists".
    let keystore = forget_onion_key(&s.host, NICKNAME);
    // The tokio runtime is no longer torn down here. It belongs to the shared `TorHost`, which
    // shuts it down in `Drop` once the last owner — this transport and/or the doorbell — is
    // gone. Without a doorbell running, dropping `s` at the end of this call is that last drop,
    // so `stop` keeps its old "the whole Tor stack dies with the transport" behaviour.
    drop(s);
    keystore
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stale_start_cleanup_preserves_new_generation() {
        let failed = Arc::new(1);
        let replacement = Arc::new(1); // Identical configuration is still a different generation.
        let slot = Mutex::new(Some(replacement.clone()));
        assert!(take_if_current(&slot, &failed).unwrap().is_none());
        assert!(Arc::ptr_eq(
            slot.lock().unwrap().as_ref().unwrap(),
            &replacement
        ));
        assert!(Arc::ptr_eq(
            &take_if_current(&slot, &replacement).unwrap().unwrap(),
            &replacement
        ));
        assert!(slot.lock().unwrap().is_none());
    }
    #[test]
    fn onions_are_stable_distinct_and_only_destinations() {
        let a = address(&[1; 32]).unwrap();
        assert_eq!(a, address(&[1; 32]).unwrap());
        assert_ne!(a, address(&[2; 32]).unwrap());
        validate_onion(&a).unwrap();
        for bad in [
            "localhost",
            "127.0.0.1",
            "example.com",
            "https://example.onion",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion",
        ] {
            assert!(validate_onion(bad).is_err());
        }
        assert_eq!(status(), "STOPPED");
    }

    #[test]
    fn readiness_states_are_named_and_never_resurrect_a_stopped_transport() {
        assert_eq!(state_name(STATE_BOOTSTRAPPING), "BOOTSTRAPPING");
        assert_eq!(state_name(STATE_PUBLISHING), "PUBLISHING");
        assert_eq!(state_name(STATE_READY), "READY");
        assert_eq!(state_name(STATE_STOPPED), "STOPPED");
        assert_eq!(state_name(200), "STOPPED");
        let state = AtomicU8::new(STATE_PUBLISHING);
        advance_state(&state, STATE_READY);
        assert_eq!(state.load(Ordering::Acquire), STATE_READY);
        // A later status event may report the service as no longer reachable.
        advance_state(&state, STATE_PUBLISHING);
        assert_eq!(state.load(Ordering::Acquire), STATE_PUBLISHING);
        state.store(STATE_STOPPED, Ordering::Release);
        advance_state(&state, STATE_READY);
        advance_state(&state, STATE_PUBLISHING);
        assert_eq!(state.load(Ordering::Acquire), STATE_STOPPED);
    }

    /// Reproduces the defect fixed on 2026-09-18 without needing a live Tor.
    ///
    /// The collision has nothing to do with the network: it happens in the `TorClient`'s
    /// keystore, which exists on an unbootstrapped client, so the whole lock/unlock cycle can be
    /// modelled here as insert (launch) → forget (teardown) → insert (relaunch).
    #[test]
    fn relaunching_a_nickname_needs_its_key_forgotten_first() {
        use tor_keymgr::KeystoreSelector;
        let root = std::env::temp_dir().join(format!(
            "nomessages-keystore-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let state = root.join("state");
        let cache = root.join("cache");
        std::fs::create_dir_all(&state).unwrap();
        std::fs::create_dir_all(&cache).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            for dir in [&root, &state, &cache] {
                std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700)).unwrap();
            }
        }
        let host = host(
            state.to_str().unwrap(),
            cache.to_str().unwrap(),
            &[],
        )
        .unwrap();
        let seed = [7u8; 32];
        let spec = |nickname: &str| {
            tor_hsservice::HsIdKeypairSpecifier::new(nickname.to_owned().try_into().unwrap())
        };
        let insert = |nickname: &str| {
            host.client.keymgr().unwrap().insert::<HsIdKeypair>(
                key(&seed).unwrap(),
                &spec(nickname),
                KeystoreSelector::Primary,
                false,
            )
        };
        // Launch: the identity lands in the keystore.
        insert(NICKNAME).unwrap();
        // Relaunch without forgetting — this is the bug the doorbell exposed.
        assert!(insert(NICKNAME).is_err());
        // Teardown erases exactly one nickname and leaves every other service alone.
        insert("nomessages-doorbell").unwrap();
        forget_onion_key(&host, NICKNAME).unwrap();
        assert!(insert("nomessages-doorbell").is_err());
        // Relaunch now succeeds, and the identity it reinstalls is byte-for-byte the old one:
        // the key is derived from the vault seed, so the onion address does not move.
        insert(NICKNAME).unwrap();
        assert_eq!(
            HsIdKey::from(
                &host
                    .client
                    .keymgr()
                    .unwrap()
                    .get::<HsIdKeypair>(&spec(NICKNAME))
                    .unwrap()
                    .unwrap()
            )
            .id()
            .display_unredacted()
            .to_string(),
            address(&seed).unwrap()
        );
        // Forgetting a nickname that was never launched is a no-op, not an error: `stop` runs on
        // the failure path of `start` too, before any service exists.
        forget_onion_key(&host, "nomessages-absent").unwrap();
        drop(host);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn readiness_wait_rejects_timeouts_outside_its_own_budget() {
        // The readiness budget is independent of the 180s bootstrap timeout in `start`.
        assert_eq!(MAX_READY_TIMEOUT_MS, 300_000);
        for invalid in [-1, MAX_READY_TIMEOUT_MS + 1] {
            let error = await_ready(invalid).unwrap_err().to_string();
            assert_eq!(error, "Invalid readiness timeout");
        }
        // Valid bounds still fail, because no transport is running in this unit test.
        assert_eq!(
            await_ready(MAX_READY_TIMEOUT_MS).unwrap_err().to_string(),
            "Transport stopped"
        );
    }

    #[test]
    fn closing_connection_cancels_an_unflushed_reply() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let (server, _blocked_peer) = tokio::io::duplex(8);
            let connections = Arc::new(Mutex::new(HashMap::new()));
            let (events, _receive) = tokio::sync::mpsc::channel(2);
            let (_cancel, cancelled) = watch::channel(false);
            let slots = Arc::new(Semaphore::new(1));
            let id = register_stream(
                rt.handle(),
                server,
                connections.clone(),
                events,
                cancelled,
                slots.acquire_owned().await.unwrap(),
            )
            .unwrap();
            let connection = connections.lock().unwrap().get(&id).unwrap().clone();
            let (ack, mut written) = tokio::sync::oneshot::channel();
            connection.send.send((vec![1; 100], ack)).await.unwrap();
            tokio::task::yield_now().await;
            assert!(matches!(
                written.try_recv(),
                Err(tokio::sync::oneshot::error::TryRecvError::Empty)
            ));
            connection.cancel.send_replace(true);
            assert!(
                tokio::time::timeout(Duration::from_secs(1), written)
                    .await
                    .unwrap()
                    .is_err()
            );
            assert!(connections.lock().unwrap().is_empty());
        });
    }

    #[test]
    fn reply_acknowledges_write_and_rejects_oversized_frame() {
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        rt.block_on(async {
            let (server, mut peer) = tokio::io::duplex(16);
            let connections = Arc::new(Mutex::new(HashMap::new()));
            let (events, mut receive) = tokio::sync::mpsc::channel(2);
            let (cancel, cancelled) = watch::channel(false);
            let slots = Arc::new(Semaphore::new(1));
            let id = register_stream(
                rt.handle(),
                server,
                connections.clone(),
                events,
                cancelled,
                slots.acquire_owned().await.unwrap(),
            )
            .unwrap();
            peer.write_u32(3).await.unwrap();
            peer.write_all(b"abc").await.unwrap();
            let event = receive.recv().await.unwrap();
            assert_eq!(event.connection_id, id);
            assert_eq!(event.payload, b"abc");
            let writer = connections.lock().unwrap().get(&id).unwrap().send.clone();
            let (ack, mut written) = tokio::sync::oneshot::channel();
            writer.send((vec![9; 100], ack)).await.unwrap();
            tokio::task::yield_now().await;
            assert!(matches!(
                written.try_recv(),
                Err(tokio::sync::oneshot::error::TryRecvError::Empty)
            ));
            assert_eq!(peer.read_u32().await.unwrap(), 100);
            let mut bytes = vec![0; 100];
            peer.read_exact(&mut bytes).await.unwrap();
            assert_eq!(bytes, vec![9; 100]);
            assert!(written.await.unwrap());
            peer.write_u32((MAX_FRAME + 1) as u32).await.unwrap();
            assert!(
                tokio::time::timeout(Duration::from_secs(2), peer.read_u8())
                    .await
                    .unwrap()
                    .is_err()
            );
            assert!(connections.lock().unwrap().is_empty());
            cancel.send_replace(true);
        });
    }
}
