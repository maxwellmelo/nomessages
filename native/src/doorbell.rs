//! Doorbell: a second, minimal onion service that survives the vault lock.
//!
//! When the vault is locked the messaging transport is gone — its onion service, its session
//! state and its circuits are destroyed. A contact whose messaging onion is therefore
//! unreachable can still "knock" on this second onion service. The knock carries no content,
//! no identity and no session material: it is a 57-byte fixed-size frame authenticated by
//! HMAC-SHA256 under a per-contact token that was exchanged at pairing time.
//!
//! Everything below runs entirely in native code. The Kotlin layer never sees a token, never
//! sees which contact knocked and never learns why a knock was refused; it only drains a queue
//! of opaque "someone rang" markers so it can raise a notification.
//!
//! The doorbell shares the process-wide Arti host (`crate::tor::TorHost`) with the messaging
//! transport but owns its own `Arc` of it, so the two have independent lifecycles: stopping
//! messaging leaves the doorbell serving, and stopping the doorbell leaves messaging serving.
use crate::tor::{self, TorHost};
use anyhow::{Result, anyhow, ensure};
use futures::StreamExt;
use hmac::{Hmac, Mac};
use safelog::DisplayRedacted;
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    sync::{Arc, Mutex, OnceLock},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::{Semaphore, watch},
};
use tokio_util::compat::FuturesAsyncReadCompatExt;
use tor_hscrypto::pk::HsIdKey;
use zeroize::Zeroizing;

/// Virtual port the knock is delivered on. Distinct from the messaging port (4242) so a probe
/// of one service can never be answered by the other, even if both run on the same host.
pub const DOORBELL_PORT: u16 = 4243;
/// Onion service nickname of the doorbell. A distinct nickname is required: two services on one
/// Arti client may not share one. It is also the keystore entry `shutdown` has to erase.
const NICKNAME: &str = "nomessages-doorbell";
/// Only version of the knock frame. Bumping it invalidates every older frame, because the
/// version byte is inside the HMAC input.
const KNOCK_VERSION: u8 = 1;
const NONCE_LEN: usize = 16;
const MAC_LEN: usize = 32;
/// `version(1) || timestamp(8) || nonce(16) || mac(32)`. Fixed size: a length field would be an
/// extra parser state to get wrong, and a variable size would leak information through padding.
pub const KNOCK_LEN: usize = 1 + 8 + NONCE_LEN + MAC_LEN;
/// Bytes covered by the HMAC: everything except the HMAC itself.
const SIGNED_LEN: usize = KNOCK_LEN - MAC_LEN;
/// Single byte written back on a knock that passed every check.
const ACK: u8 = 0x01;
/// Accepted clock skew, in seconds, in either direction.
const WINDOW_SECS: u64 = 300;
/// How long a used nonce keeps blocking replays. Longer than the window (`WINDOW_SECS`), so a
/// frame always expires by timestamp before its nonce is forgotten — there is no gap in which a
/// captured frame becomes replayable again.
const NONCE_TTL_SECS: u64 = 600;
/// At most one accepted knock per token per this many seconds.
const RATE_LIMIT_SECS: u64 = 600;
const MAX_TOKENS: usize = 256;
const MIN_TOKEN_LEN: usize = 16;
const MAX_TOKEN_LEN: usize = 64;
/// Concurrent inbound knocks. A knock is 57 bytes and closes immediately, so this is generous.
const MAX_KNOCKS: usize = 32;
/// Pending "someone rang" markers. A burst beyond this is dropped: the notification is the same
/// either way, and an unbounded queue would be a memory oracle for a flooder.
const MAX_EVENTS: usize = 64;
/// Upper bound on how long a peer may take to deliver its 57 bytes once the stream is open.
const READ_TIMEOUT: Duration = Duration::from_secs(15);
/// Zero-ish poll used only to notice bytes that arrived *with* the frame, so an over-long frame
/// is rejected without making an honest 57-byte knock wait for an end-of-stream that never comes.
const TRAILER_PROBE: Duration = Duration::from_millis(50);

type HmacSha256 = Hmac<Sha256>;

static DOORBELL: OnceLock<Mutex<Option<Arc<Doorbell>>>> = OnceLock::new();
/// The verifier outlives any individual doorbell generation on purpose: stopping and restarting
/// the service must not reset the replay cache or the per-token rate limit, or "stop; start"
/// would be a free bypass for both.
static VERIFIER: OnceLock<Arc<Mutex<Verifier>>> = OnceLock::new();

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|since| since.as_secs())
        .unwrap_or(0)
}

/// Constant-time equality. Slice `==` short-circuits on the first differing byte, which turns a
/// tag comparison into a byte-at-a-time oracle; this accumulates every difference first.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut difference = 0u8;
    for (x, y) in a.iter().zip(b.iter()) {
        difference |= x ^ y;
    }
    // `black_box` keeps the optimiser from reintroducing an early exit.
    std::hint::black_box(difference) == 0
}

/// HMAC-SHA256 over the signed prefix of a knock. `None` only if the HMAC construction rejects
/// the key, which cannot happen for the lengths accepted by `set_tokens`; returning `Option`
/// rather than unwrapping keeps a panic out of the JNI path.
fn tag(token: &[u8], signed: &[u8]) -> Option<[u8; MAC_LEN]> {
    let mut mac = <HmacSha256 as Mac>::new_from_slice(token).ok()?;
    mac.update(signed);
    Some(mac.finalize().into_bytes().into())
}

/// Stateful knock verifier. Deliberately free of I/O and of `SystemTime`: `now` is injected by
/// the caller so the replay window, the nonce TTL and the rate limit are all testable offline.
pub struct Verifier {
    /// Opaque per-contact tokens. The verifier never learns which contact a token belongs to.
    tokens: Vec<Zeroizing<Vec<u8>>>,
    /// nonce -> second at which it was accepted.
    nonces: HashMap<[u8; NONCE_LEN], u64>,
    /// SHA-256(token) -> second of that token's last accepted knock. Keyed by digest rather than
    /// by index so the rate limit survives a token-set reload that reorders the list.
    accepted: HashMap<[u8; 32], u64>,
}

impl Verifier {
    pub fn new() -> Self {
        Self {
            tokens: Vec::new(),
            nonces: HashMap::new(),
            accepted: HashMap::new(),
        }
    }

    /// Replaces the accepted token set. The replay cache and the rate-limit ledger are kept:
    /// reloading tokens is a routine event (a new contact was paired) and must not hand an
    /// attacker a way to reset either defence.
    pub fn set_tokens(&mut self, tokens: Vec<Vec<u8>>) -> Result<()> {
        ensure!(tokens.len() <= MAX_TOKENS, "Too many doorbell tokens");
        ensure!(
            tokens
                .iter()
                .all(|t| (MIN_TOKEN_LEN..=MAX_TOKEN_LEN).contains(&t.len())),
            "Invalid doorbell token"
        );
        self.tokens = tokens.into_iter().map(Zeroizing::new).collect();
        Ok(())
    }

    pub fn clear(&mut self) {
        self.tokens.clear();
    }

    #[cfg(test)]
    fn token_count(&self) -> usize {
        self.tokens.len()
    }

    /// Drops nonces and rate-limit entries that can no longer refuse anything. Lazy: called on
    /// every verification, so no cleanup thread is needed. Both maps are bounded by the token
    /// count anyway, because only an *accepted* knock ever writes to them.
    fn expire(&mut self, now: u64) {
        self.nonces
            .retain(|_, seen| now.saturating_sub(*seen) < NONCE_TTL_SECS);
        self.accepted
            .retain(|_, last| now.saturating_sub(*last) < RATE_LIMIT_SECS);
    }

    /// Returns true only for a knock that passes every check. The caller must not distinguish
    /// the failure reasons: a single boolean is the whole point, so the closing behaviour is
    /// identical for a wrong length, an unknown token, a stale timestamp, a replay and a
    /// throttled contact.
    pub fn verify(&mut self, frame: &[u8], now: u64) -> bool {
        self.expire(now);
        if frame.len() != KNOCK_LEN || frame[0] != KNOCK_VERSION {
            return false;
        }
        let signed = &frame[..SIGNED_LEN];
        let timestamp = match frame[1..9].try_into() {
            Ok(bytes) => u64::from_be_bytes(bytes),
            Err(_) => return false,
        };
        let mut nonce = [0u8; NONCE_LEN];
        nonce.copy_from_slice(&frame[9..9 + NONCE_LEN]);
        let mac = &frame[SIGNED_LEN..];

        // Every token is tried, with no early exit, so the time spent here does not reveal the
        // knocker's position in the token list (nor whether any token matched at all).
        let mut matched: Option<usize> = None;
        for (index, token) in self.tokens.iter().enumerate() {
            if let Some(expected) = tag(token, signed)
                && constant_time_eq(&expected, mac)
            {
                matched = Some(index);
            }
        }
        let Some(index) = matched else {
            return false;
        };

        // Replay window. `abs_diff` covers both a stale frame and one from a fast clock; the
        // bound is inclusive, so exactly WINDOW_SECS of skew is still accepted.
        if timestamp.abs_diff(now) > WINDOW_SECS {
            return false;
        }
        if self.nonces.contains_key(&nonce) {
            return false;
        }
        let digest: [u8; 32] = Sha256::digest(self.tokens[index].as_slice()).into();
        if let Some(last) = self.accepted.get(&digest) {
            // `saturating_sub` also refuses a knock that arrives "before" the last accepted one,
            // which is what a clock stepped backwards looks like.
            if now.saturating_sub(*last) < RATE_LIMIT_SECS {
                return false;
            }
        }
        // Only an accepted knock is recorded. A refused one leaves no trace, so a flooder cannot
        // grow either map, and a nonce is never burned by a knock that was rejected anyway.
        self.nonces.insert(nonce, now);
        self.accepted.insert(digest, now);
        true
    }
}

impl Default for Verifier {
    fn default() -> Self {
        Self::new()
    }
}

/// Builds the wire frame for an outbound knock.
pub fn frame(token: &[u8], timestamp: u64, nonce: &[u8]) -> Result<Vec<u8>> {
    ensure!(nonce.len() == NONCE_LEN, "Invalid doorbell nonce");
    let mut out = Vec::with_capacity(KNOCK_LEN);
    out.push(KNOCK_VERSION);
    out.extend_from_slice(&timestamp.to_be_bytes());
    out.extend_from_slice(nonce);
    let mac = tag(token, &out).ok_or_else(|| anyhow!("Invalid doorbell token"))?;
    out.extend_from_slice(&mac);
    Ok(out)
}

struct Doorbell {
    host: Arc<TorHost>,
    onion: String,
    cancel: watch::Sender<bool>,
    events: tokio::sync::mpsc::Sender<()>,
    incoming: Mutex<tokio::sync::mpsc::Receiver<()>>,
    service: Mutex<Option<Arc<tor_hsservice::RunningOnionService>>>,
}

fn verifier() -> Arc<Mutex<Verifier>> {
    VERIFIER
        .get_or_init(|| Arc::new(Mutex::new(Verifier::new())))
        .clone()
}

fn slot() -> &'static Mutex<Option<Arc<Doorbell>>> {
    DOORBELL.get_or_init(|| Mutex::new(None))
}

fn current() -> Result<Arc<Doorbell>> {
    slot()
        .lock()
        .map_err(|_| anyhow!("Doorbell unavailable"))?
        .clone()
        .ok_or_else(|| anyhow!("Doorbell stopped"))
}

/// Replaces the set of tokens the doorbell accepts. Valid before the service is started, so the
/// engine can load the vault's tokens and only then open the onion service.
pub fn set_tokens(tokens: Vec<Vec<u8>>) -> Result<()> {
    verifier()
        .lock()
        .map_err(|_| anyhow!("Doorbell unavailable"))?
        .set_tokens(tokens)
}

/// Onion address of the running doorbell service.
pub fn onion() -> Result<String> {
    Ok(current()?.onion.clone())
}

/// Starts the doorbell onion service, or returns the address of the one already running.
///
/// Idempotent for the same seed; a different seed is an error rather than a silent rebind,
/// because the address is published in pairing offers and must not change under the peers.
/// The state/cache directories and bridges are only used when no Arti host exists yet (a
/// doorbell-only process); otherwise the messaging transport's host is reused.
pub fn start(seed: &[u8], state_dir: &str, cache_dir: &str, bridges: &[String]) -> Result<String> {
    let hsid = tor::keypair(seed)?;
    let onion = HsIdKey::from(&hsid).id().display_unredacted().to_string();
    if let Some(running) = slot()
        .lock()
        .map_err(|_| anyhow!("Doorbell unavailable"))?
        .clone()
    {
        ensure!(running.onion == onion, "Doorbell already started");
        return Ok(onion);
    }
    let host = tor::host(state_dir, cache_dir, bridges)?;
    let (cancel, _) = watch::channel(false);
    let (events, incoming) = tokio::sync::mpsc::channel(MAX_EVENTS);
    let doorbell = Arc::new(Doorbell {
        host,
        onion: onion.clone(),
        cancel,
        events,
        incoming: Mutex::new(incoming),
        service: Mutex::new(None),
    });
    {
        let mut slot = slot().lock().map_err(|_| anyhow!("Doorbell unavailable"))?;
        ensure!(slot.is_none(), "Doorbell already started");
        *slot = Some(doorbell.clone());
    }
    let result = launch(&doorbell, hsid);
    if let Err(error) = result {
        // Tear down only our own generation, exactly as `tor::start` does.
        let mut slot = slot().lock().map_err(|_| anyhow!("Doorbell unavailable"))?;
        if slot.as_ref().is_some_and(|live| Arc::ptr_eq(live, &doorbell)) {
            slot.take();
        }
        drop(slot);
        shutdown(&doorbell);
        return Err(error);
    }
    Ok(onion)
}

fn launch(doorbell: &Arc<Doorbell>, hsid: tor_hscrypto::pk::HsIdKeypair) -> Result<()> {
    let verifier = verifier();
    doorbell.host.handle.block_on(async {
        let mut cancelled = doorbell.cancel.subscribe();
        ensure!(!*cancelled.borrow(), "Doorbell stopped");
        // Idempotent: returns immediately when the messaging transport already bootstrapped
        // this same client.
        tokio::select! {
            biased;
            _ = cancelled.changed() => return Err(anyhow!("Doorbell stopped")),
            result = tokio::time::timeout(Duration::from_secs(180), doorbell.host.client.bootstrap()) => result??,
        }
        ensure!(!*cancelled.borrow(), "Doorbell stopped");
        // A distinct nickname is required: two services on one client may not share one.
        let config = tor_hsservice::config::OnionServiceConfigBuilder::default()
            .nickname(NICKNAME.to_owned().try_into()?)
            .build()?;
        let (service, requests) = doorbell
            .host
            .client
            .launch_onion_service_with_hsid(config, hsid)?
            .ok_or_else(|| anyhow!("Onion service disabled"))?;
        *doorbell
            .service
            .lock()
            .map_err(|_| anyhow!("Doorbell unavailable"))? = Some(service);
        let events = doorbell.events.clone();
        let handle = doorbell.host.handle.clone();
        let slots = Arc::new(Semaphore::new(MAX_KNOCKS));
        doorbell.host.handle.spawn(async move {
            let requests = tor_hsservice::handle_rend_requests(requests);
            tokio::pin!(requests);
            loop {
                let request = tokio::select! {
                    biased;
                    _ = cancelled.changed() => break,
                    request = requests.next() => match request { Some(r) => r, None => break },
                };
                let accepted_port = matches!(
                    request.request(),
                    tor_proto::stream::IncomingStreamRequest::Begin(begin)
                        if begin.port() == DOORBELL_PORT
                );
                let permit = slots.clone().try_acquire_owned();
                if !accepted_port || permit.is_err() {
                    let _ = request.shutdown_circuit();
                    continue;
                }
                let verifier = verifier.clone();
                let events = events.clone();
                handle.spawn(async move {
                    let _permit = permit;
                    let Ok(Ok(stream)) = tokio::time::timeout(
                        Duration::from_secs(30),
                        request.accept(tor_cell::relaycell::msg::Connected::new_empty()),
                    )
                    .await
                    else {
                        return;
                    };
                    serve_knock(stream.compat(), verifier, events).await;
                });
            }
        });
        Ok::<(), anyhow::Error>(())
    })
}

/// Handles exactly one knock on one stream and closes it.
///
/// Every rejection path returns without writing a single byte, so a knocker cannot tell a wrong
/// length from an unknown token from a replay from a throttle. Only a fully valid knock is
/// answered, with one `ACK` byte.
async fn serve_knock<S>(
    mut stream: S,
    verifier: Arc<Mutex<Verifier>>,
    events: tokio::sync::mpsc::Sender<()>,
) where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let mut frame = [0u8; KNOCK_LEN];
    if !matches!(
        tokio::time::timeout(READ_TIMEOUT, stream.read_exact(&mut frame)).await,
        Ok(Ok(_))
    ) {
        return; // Short frame or a stalled peer: both are simply invalid.
    }
    // Exactly one frame per connection. Anything already queued behind the 57 bytes makes this
    // an over-long frame, which the protocol defines as invalid.
    let mut trailer = [0u8; 1];
    if let Ok(Ok(extra)) = tokio::time::timeout(TRAILER_PROBE, stream.read(&mut trailer)).await
        && extra > 0
    {
        return;
    }
    // The lock is taken and released synchronously; it is never held across an await.
    let accepted = verifier
        .lock()
        .map(|mut verifier| verifier.verify(&frame, now_secs()))
        .unwrap_or(false);
    if !accepted {
        return;
    }
    if stream.write_all(&[ACK]).await.is_ok() {
        let _ = stream.flush().await;
    }
    // `try_send`, not `send`: a queue that is already full carries the same notification this
    // event would, and blocking here would let a flooder pin a connection slot.
    let _ = events.try_send(());
}

/// Drains accepted-knock markers.
///
/// Blocking with a deadline, like `tor::poll`: in minimal mode the `:tor` process has nothing
/// else to do, and parking one thread costs far less battery than a polling loop. A zero timeout
/// makes the call non-blocking. Returns how many knocks were accepted since the last drain —
/// nothing about who knocked, or when.
pub fn poll_events(timeout_ms: i32) -> Result<u32> {
    ensure!((0..=30000).contains(&timeout_ms), "Invalid poll timeout");
    let d = current()?;
    d.host.handle.block_on(async {
        let mut cancelled = d.cancel.subscribe();
        ensure!(!*cancelled.borrow(), "Doorbell stopped");
        let mut receiver = d
            .incoming
            .lock()
            .map_err(|_| anyhow!("Doorbell unavailable"))?;
        let mut count: u32 = tokio::select! {
            biased;
            _ = cancelled.changed() => return Err(anyhow!("Doorbell stopped")),
            value = tokio::time::timeout(Duration::from_millis(timeout_ms as u64), receiver.recv()) => match value {
                Ok(Some(())) => 1,
                Ok(None) => return Err(anyhow!("Doorbell stopped")),
                Err(_) => 0,
            },
        };
        if count > 0 {
            // Collapse a burst into one drain: the UI shows one notification either way.
            while receiver.try_recv().is_ok() {
                count = count.saturating_add(1);
            }
        }
        Ok(count)
    })
}

/// Sends a knock to a contact's doorbell onion and reports whether it was acknowledged.
///
/// The token never leaves native code: the caller hands it in, the frame is built and sent here.
/// A refused knock is indistinguishable from a closed connection by design, so `false` covers
/// "rejected", "not listening" and "closed without answering" alike.
pub fn knock(onion: &str, token: &[u8]) -> Result<bool> {
    tor::validate_onion(onion)?;
    ensure!(
        (MIN_TOKEN_LEN..=MAX_TOKEN_LEN).contains(&token.len()),
        "Invalid doorbell token"
    );
    let host = tor::active_host().ok_or_else(|| anyhow!("Transport stopped"))?;
    let nonce = crate::crypto::random(NONCE_LEN).map_err(|_| anyhow!("Doorbell nonce failed"))?;
    let payload = Zeroizing::new(frame(token, now_secs(), &nonce)?);
    let client = host.client.clone();
    host.handle.block_on(async move {
        tokio::time::timeout(Duration::from_secs(180), client.bootstrap()).await??;
        // A fresh isolated client per knock: a doorbell circuit must not be linked to the
        // messaging circuits of the same contact.
        let exchange = async {
            let mut stream = client
                .isolated_client()
                .connect((onion, DOORBELL_PORT))
                .await?
                .compat();
            stream.write_all(&payload).await?;
            stream.flush().await?;
            let mut ack = [0u8; 1];
            Ok::<bool, anyhow::Error>(stream.read_exact(&mut ack).await.is_ok() && ack[0] == ACK)
        };
        tokio::time::timeout(Duration::from_secs(60), exchange).await?
    })
}

/// Drops the messaging transport but keeps the doorbell serving: this is the lock path.
///
/// Requires a running doorbell, so the vault can never be locked into a state where neither
/// service is reachable without that being an explicit `stop`.
pub fn minimal_mode() -> Result<()> {
    ensure!(current().is_ok(), "Doorbell stopped");
    // Exactly `tor::stop`: onion service, connections, per-peer isolated clients and session
    // state are destroyed. The shared Arti host survives only because this doorbell holds it.
    tor::stop()
}

/// Stops the doorbell and forgets the loaded tokens. The replay cache and the rate-limit ledger
/// are kept on purpose (see `VERIFIER`); without tokens they cannot accept anything anyway.
pub fn stop() -> Result<()> {
    let taken = slot()
        .lock()
        .map_err(|_| anyhow!("Doorbell unavailable"))?
        .take();
    if let Some(doorbell) = taken {
        shutdown(&doorbell);
    }
    if let Ok(mut verifier) = verifier().lock() {
        verifier.clear();
    }
    Ok(())
}

fn shutdown(doorbell: &Arc<Doorbell>) {
    doorbell.cancel.send_replace(true);
    if let Ok(mut service) = doorbell.service.lock() {
        service.take();
    }
    // Same reason as `tor::shutdown_transport`: dropping the service does not drop the identity
    // key Arti filed under this nickname, and re-launching over a leftover entry is refused with
    // "Key already exists". This path is walked on every unlock (`NoMessagesController` stops the
    // doorbell as soon as messaging has adopted the host), so without this the *second* lock in
    // one process — the first one the transport fix makes reachable — would fail to open the
    // doorbell at all. Best effort: `stop` must tear the doorbell down whatever the keystore
    // says, and the key is derived from the vault seed, so a failure here costs a later relaunch,
    // never data.
    let _ = tor::forget_onion_key(&doorbell.host, NICKNAME);
    // The tokio runtime belongs to the shared host and is shut down by its `Drop`, once the
    // messaging transport has let go of it too.
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: u64 = 1_800_000_000;
    const TOKEN: &[u8] = b"doorbell-token-0123456789abcdef!";
    const OTHER: &[u8] = b"doorbell-token-fedcba9876543210?";

    fn verifier_with(tokens: &[&[u8]]) -> Verifier {
        let mut verifier = Verifier::new();
        verifier
            .set_tokens(tokens.iter().map(|t| t.to_vec()).collect())
            .unwrap();
        verifier
    }

    fn knock_frame(token: &[u8], timestamp: u64, nonce: u8) -> Vec<u8> {
        frame(token, timestamp, &[nonce; NONCE_LEN]).unwrap()
    }

    #[test]
    fn frame_layout_is_fixed_at_fifty_seven_bytes() {
        assert_eq!(KNOCK_LEN, 57);
        assert_eq!(SIGNED_LEN, 25);
        let frame = knock_frame(TOKEN, NOW, 1);
        assert_eq!(frame.len(), KNOCK_LEN);
        assert_eq!(frame[0], KNOCK_VERSION);
        assert_eq!(u64::from_be_bytes(frame[1..9].try_into().unwrap()), NOW);
        assert_eq!(&frame[9..25], &[1u8; NONCE_LEN]);
        // The MAC really covers version || timestamp || nonce, nothing else.
        assert_eq!(&frame[25..], &tag(TOKEN, &frame[..25]).unwrap());
    }

    #[test]
    fn valid_knock_from_a_known_token_is_accepted() {
        let mut verifier = verifier_with(&[OTHER, TOKEN]);
        assert!(verifier.verify(&knock_frame(TOKEN, NOW, 1), NOW));
        assert!(verifier.verify(&knock_frame(OTHER, NOW, 2), NOW));
    }

    #[test]
    fn unknown_token_is_always_rejected() {
        let mut verifier = verifier_with(&[OTHER]);
        // Fresh nonce, perfect timestamp: only the token is wrong.
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW, 1), NOW));
        // And a tampered MAC over a known token is rejected too.
        let mut tampered = knock_frame(OTHER, NOW, 2);
        tampered[KNOCK_LEN - 1] ^= 0x01;
        assert!(!verifier.verify(&tampered, NOW));
    }

    #[test]
    fn empty_token_set_rejects_everything_without_panicking() {
        let mut verifier = Verifier::new();
        assert_eq!(verifier.token_count(), 0);
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW, 1), NOW));
        assert!(!verifier.verify(&[], NOW));
        assert!(!verifier.verify(&[0; KNOCK_LEN], NOW));
    }

    #[test]
    fn timestamps_outside_the_window_are_rejected_on_both_sides() {
        let mut verifier = verifier_with(&[TOKEN]);
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW - WINDOW_SECS - 1, 1), NOW));
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW + WINDOW_SECS + 1, 2), NOW));
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW - 3600, 3), NOW));
        assert!(!verifier.verify(&knock_frame(TOKEN, 0, 4), NOW));
    }

    #[test]
    fn window_edges_are_inclusive_to_the_second() {
        // Exactly at the edge, both directions: accepted. Each uses a fresh verifier so the
        // per-token rate limit does not mask the window behaviour under test.
        assert!(verifier_with(&[TOKEN]).verify(&knock_frame(TOKEN, NOW - WINDOW_SECS, 1), NOW));
        assert!(verifier_with(&[TOKEN]).verify(&knock_frame(TOKEN, NOW + WINDOW_SECS, 2), NOW));
        // One second inside: accepted.
        assert!(verifier_with(&[TOKEN]).verify(&knock_frame(TOKEN, NOW - WINDOW_SECS + 1, 3), NOW));
        assert!(verifier_with(&[TOKEN]).verify(&knock_frame(TOKEN, NOW + WINDOW_SECS - 1, 4), NOW));
        // One second outside: rejected.
        assert!(!verifier_with(&[TOKEN]).verify(&knock_frame(TOKEN, NOW - WINDOW_SECS - 1, 5), NOW));
        assert!(!verifier_with(&[TOKEN]).verify(&knock_frame(TOKEN, NOW + WINDOW_SECS + 1, 6), NOW));
    }

    #[test]
    fn a_replayed_nonce_is_refused() {
        let mut verifier = verifier_with(&[TOKEN]);
        let frame = knock_frame(TOKEN, NOW, 7);
        assert!(verifier.verify(&frame, NOW));
        assert!(!verifier.verify(&frame, NOW));
        // Still refused once the rate limit no longer applies but the nonce TTL does.
        assert!(!verifier.verify(&frame, NOW + RATE_LIMIT_SECS));
    }

    #[test]
    fn a_token_is_limited_to_one_accepted_knock_per_ten_minutes() {
        let mut verifier = verifier_with(&[TOKEN]);
        assert!(verifier.verify(&knock_frame(TOKEN, NOW, 1), NOW));
        // Different nonce, different timestamp, still inside the window: refused anyway.
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW + 60, 2), NOW + 60));
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW + 599, 3), NOW + 599));
        // A different token is unaffected by the first one's throttle.
        let mut pair = verifier_with(&[TOKEN, OTHER]);
        assert!(pair.verify(&knock_frame(TOKEN, NOW, 4), NOW));
        assert!(pair.verify(&knock_frame(OTHER, NOW, 5), NOW));
    }

    #[test]
    fn the_rate_limit_lifts_after_ten_minutes_of_injected_clock() {
        let mut verifier = verifier_with(&[TOKEN]);
        assert!(verifier.verify(&knock_frame(TOKEN, NOW, 1), NOW));
        let later = NOW + RATE_LIMIT_SECS;
        assert!(verifier.verify(&knock_frame(TOKEN, later, 2), later));
        // And the ledger is not reset by the second acceptance either.
        assert!(!verifier.verify(&knock_frame(TOKEN, later + 1, 3), later + 1));
    }

    #[test]
    fn reloading_tokens_does_not_reset_the_rate_limit() {
        let mut verifier = verifier_with(&[TOKEN]);
        assert!(verifier.verify(&knock_frame(TOKEN, NOW, 1), NOW));
        verifier
            .set_tokens(vec![OTHER.to_vec(), TOKEN.to_vec()])
            .unwrap();
        assert!(!verifier.verify(&knock_frame(TOKEN, NOW + 1, 2), NOW + 1));
        assert!(verifier.verify(&knock_frame(OTHER, NOW + 1, 3), NOW + 1));
    }

    #[test]
    fn frames_of_the_wrong_size_or_version_are_rejected_without_panicking() {
        let mut verifier = verifier_with(&[TOKEN]);
        let valid = knock_frame(TOKEN, NOW, 1);
        for length in [0usize, 1, 8, 24, 56] {
            assert!(!verifier.verify(&valid[..length], NOW));
        }
        let mut long = valid.clone();
        long.push(0);
        assert!(!verifier.verify(&long, NOW));
        long.extend_from_slice(&[0; 128]);
        assert!(!verifier.verify(&long, NOW));
        let mut wrong_version = valid.clone();
        wrong_version[0] = KNOCK_VERSION.wrapping_add(1);
        assert!(!verifier.verify(&wrong_version, NOW));
        // The untouched frame still works afterwards: no rejection consumed its nonce.
        assert!(verifier.verify(&valid, NOW));
    }

    #[test]
    fn expiry_bounds_both_caches() {
        let mut verifier = verifier_with(&[TOKEN]);
        assert!(verifier.verify(&knock_frame(TOKEN, NOW, 1), NOW));
        assert_eq!(verifier.nonces.len(), 1);
        assert_eq!(verifier.accepted.len(), 1);
        // A rejected knock adds nothing, so a flooder cannot grow either map.
        assert!(!verifier.verify(&knock_frame(OTHER, NOW, 2), NOW));
        assert_eq!(verifier.nonces.len(), 1);
        // Both entries fall out once their TTL passes.
        verifier.expire(NOW + NONCE_TTL_SECS);
        assert!(verifier.nonces.is_empty());
        assert!(verifier.accepted.is_empty());
    }

    #[test]
    fn token_sets_are_bounded_and_validated() {
        let mut verifier = Verifier::new();
        assert!(verifier.set_tokens(vec![vec![7; MIN_TOKEN_LEN - 1]]).is_err());
        assert!(verifier.set_tokens(vec![vec![7; MAX_TOKEN_LEN + 1]]).is_err());
        assert!(verifier.set_tokens(vec![Vec::new()]).is_err());
        assert!(
            verifier
                .set_tokens(vec![vec![7; 32]; MAX_TOKENS + 1])
                .is_err()
        );
        assert!(verifier.set_tokens(vec![vec![7; 32]; MAX_TOKENS]).is_ok());
        assert_eq!(verifier.token_count(), MAX_TOKENS);
        verifier.clear();
        assert_eq!(verifier.token_count(), 0);
    }

    #[test]
    fn constant_time_comparison_matches_plain_equality() {
        assert!(constant_time_eq(b"", b""));
        assert!(constant_time_eq(&[1, 2, 3], &[1, 2, 3]));
        assert!(!constant_time_eq(&[1, 2, 3], &[1, 2, 4]));
        assert!(!constant_time_eq(&[1, 2, 3], &[9, 2, 3]));
        assert!(!constant_time_eq(&[1, 2, 3], &[1, 2, 3, 4]));
    }

    #[test]
    fn frame_rejects_a_nonce_of_the_wrong_length() {
        assert!(frame(TOKEN, NOW, &[0; NONCE_LEN - 1]).is_err());
        assert!(frame(TOKEN, NOW, &[0; NONCE_LEN + 1]).is_err());
        assert!(frame(TOKEN, NOW, &[0; NONCE_LEN]).is_ok());
    }

    #[test]
    fn the_doorbell_port_is_not_the_messaging_port() {
        assert_eq!(DOORBELL_PORT, 4243);
        assert_ne!(DOORBELL_PORT, 4242);
    }

    #[tokio::test]
    async fn an_accepted_knock_is_acknowledged_and_an_invalid_one_is_not() {
        let verifier = Arc::new(Mutex::new(verifier_with(&[TOKEN])));
        let (events, mut received) = tokio::sync::mpsc::channel(4);

        let (server, mut peer) = tokio::io::duplex(128);
        let task = tokio::spawn(serve_knock(server, verifier.clone(), events.clone()));
        // The verifier's clock is the real one here, so the frame must carry a real timestamp.
        peer.write_all(&knock_frame(TOKEN, now_secs(), 1))
            .await
            .unwrap();
        let mut ack = [0u8; 1];
        peer.read_exact(&mut ack).await.unwrap();
        assert_eq!(ack[0], ACK);
        assert!(received.recv().await.is_some());
        task.await.unwrap();

        // Unknown token: the stream closes with no byte written and no event queued.
        let (server, mut peer) = tokio::io::duplex(128);
        let task = tokio::spawn(serve_knock(server, verifier, events));
        peer.write_all(&knock_frame(OTHER, now_secs(), 2))
            .await
            .unwrap();
        task.await.unwrap();
        assert_eq!(peer.read(&mut ack).await.unwrap(), 0);
        assert!(received.try_recv().is_err());
    }

    #[tokio::test]
    async fn an_over_long_frame_is_refused_even_with_a_valid_prefix() {
        let verifier = Arc::new(Mutex::new(verifier_with(&[TOKEN])));
        let (events, mut received) = tokio::sync::mpsc::channel(4);
        let (server, mut peer) = tokio::io::duplex(256);
        let task = tokio::spawn(serve_knock(server, verifier, events));
        let mut oversized = knock_frame(TOKEN, now_secs(), 3);
        oversized.push(0xFF);
        peer.write_all(&oversized).await.unwrap();
        task.await.unwrap();
        let mut ack = [0u8; 1];
        assert_eq!(peer.read(&mut ack).await.unwrap(), 0);
        assert!(received.try_recv().is_err());
    }
}
