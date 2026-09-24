#![cfg(feature = "tor")]
use std::time::{Duration, Instant};
use nomessages::tor;

struct TestSession(std::path::PathBuf);
impl Drop for TestSession {
    fn drop(&mut self) {
        let _ = tor::stop();
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// Explicit opt-in integration gate. Creates only a self-addressed Tor exchange.
#[test]
#[ignore = "requires live Tor bootstrap and descriptor publication"]
fn onion_self_roundtrip_replies_and_stops() {
    // Diagnostics are installed only by this explicitly selected synthetic test.
    let _ = tracing_subscriber::fmt()
        .with_ansi(false)
        .without_time()
        .with_test_writer()
        .try_init();
    let root = std::env::temp_dir().join(format!("nomessages-tor-test-{}", std::process::id()));
    std::fs::create_dir_all(&root).unwrap();
    let _cleanup = TestSession(root.clone());
    let state = root.join("state");
    let cache = root.join("cache");
    let onion = tor::start(
        &[91; 32],
        state.to_str().unwrap(),
        cache.to_str().unwrap(),
        &[],
    )
    .unwrap();
    eprintln!("Tor bootstrap completed; waiting for the synthetic onion service");
    assert_eq!(onion, tor::address(&[91; 32]).unwrap());
    // The gate is "start does not claim readiness it has not got", not "the status is frozen at
    // PUBLISHING": the status watcher runs on its own task and a fast directory can legitimately
    // flip this to READY before the assertion is reached. Demanding equality here would fail T2.2
    // on a *good* network, which is the worst possible direction for a live gate to fail in.
    assert!(
        matches!(tor::status(), "PUBLISHING" | "READY"),
        "Launch must reach publication, and must never report BOOTSTRAPPING or STOPPED"
    );
    // No `send` before the descriptor is published: an early attempt only measures how long the
    // publisher still needs, and its failure says nothing about the transport.
    let publishing = Instant::now();
    // `await_ready` returns the state it actually observed, so this single value is the gate:
    // "PUBLISHING" means the 300s budget elapsed first, which is a real failure of T2.2.
    let reached = tor::await_ready(300_000).unwrap();
    assert_eq!(
        reached, "READY",
        "Onion descriptor was not published within 300s"
    );
    // Re-reading the status is a second sample of a reversible state: tor-hsservice may already
    // have dropped back to publishing. Only a drop to BOOTSTRAPPING/STOPPED would be a defect.
    assert!(matches!(tor::status(), "PUBLISHING" | "READY"));
    eprintln!(
        "Descriptor published after {:.1}s; starting the synthetic self-connect",
        publishing.elapsed().as_secs_f64()
    );
    let first_frame = Instant::now();
    let deadline = Instant::now() + Duration::from_secs(180);
    let connection = loop {
        match tor::send(&onion, b"opaque authenticated ciphertext") {
            Ok(id) => break id,
            Err(error) if Instant::now() < deadline => {
                eprintln!("Synthetic self-connect pending: {error:#}");
                std::thread::sleep(Duration::from_secs(3));
            }
            Err(error) => {
                tor::stop().unwrap();
                panic!("Tor self-connect failed: {error}")
            }
        }
    };
    eprintln!(
        "Time to first frame after publication: {:.1}s",
        first_frame.elapsed().as_secs_f64()
    );
    let received = tor::poll(30000).unwrap().expect("Incoming ciphertext");
    assert_ne!(received.connection_id, connection);
    assert_eq!(received.payload, b"opaque authenticated ciphertext");
    tor::reply(received.connection_id, b"authenticated ack").unwrap();
    let ack = tor::poll(30000).unwrap().expect("Reply ciphertext");
    assert_eq!(ack.connection_id, connection);
    assert_eq!(ack.payload, b"authenticated ack");
    tor::stop().unwrap();
    assert_eq!(tor::status(), "STOPPED");
    assert!(tor::send(&onion, b"closed").is_err());
    assert!(tor::await_ready(0).is_err());
    assert!(
        !state.join("keystore").exists(),
        "Ephemeral secrets must not create a keystore"
    );
    std::fs::remove_dir_all(root).unwrap();
}
