use crate::{
    doorbell, tor,
    wire::{MAX_FRAME, MAX_STATE, Reader},
};
use anyhow::{Result, ensure};
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::{jbyteArray, jint},
};
use zeroize::Zeroizing;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_nativebridge_TorNative_transact(
    mut env: JNIEnv,
    _: JObject,
    operation: jint,
    arguments: JByteArray,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Vec<u8>> {
        ensure!(
            env.get_array_length(&arguments)? as usize <= MAX_STATE,
            "Arguments too large"
        );
        let args = Zeroizing::new(env.convert_byte_array(&arguments)?);
        let mut r = Reader::new(&args)?;
        Ok(match operation {
            0 => {
                let seed = r.bytes(32)?;
                r.finish()?;
                tor::address(seed)?.into_bytes()
            }
            1 => {
                let seed = r.bytes(32)?;
                let state = std::str::from_utf8(r.bytes(4096)?)?;
                let cache = std::str::from_utf8(r.bytes(4096)?)?;
                let bridges = r
                    .list(16, 1024)?
                    .into_iter()
                    .map(String::from_utf8)
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                r.finish()?;
                tor::start(seed, state, cache, &bridges)?.into_bytes()
            }
            2 => {
                r.finish()?;
                tor::onion()?.into_bytes()
            }
            3 => {
                r.finish()?;
                tor::status().as_bytes().to_vec()
            }
            4 => {
                let onion = std::str::from_utf8(r.bytes(62)?)?;
                let payload = r.bytes(MAX_FRAME)?;
                r.finish()?;
                tor::send(onion, payload)?.to_be_bytes().to_vec()
            }
            5 => {
                let timeout = r.u32()? as i32;
                r.finish()?;
                if let Some(frame) = tor::poll(timeout)? {
                    let mut bytes = frame.connection_id.to_be_bytes().to_vec();
                    bytes.extend_from_slice(&frame.payload);
                    bytes
                } else {
                    Vec::new()
                }
            }
            6 => {
                let id = ((r.u32()? as u64) << 32) | r.u32()? as u64;
                let payload = r.bytes(MAX_FRAME)?;
                r.finish()?;
                tor::reply(id, payload)?;
                Vec::new()
            }
            7 => {
                let id = ((r.u32()? as u64) << 32) | r.u32()? as u64;
                r.finish()?;
                tor::close_connection(id)?;
                Vec::new()
            }
            8 => {
                r.finish()?;
                tor::stop()?;
                Vec::new()
            }
            // Readiness wait. Kept after `stop` so every pre-existing opcode keeps its number.
            9 => {
                let timeout = r.u32()? as i32;
                r.finish()?;
                tor::await_ready(timeout)?.as_bytes().to_vec()
            }
            // Doorbell (T4.17), opcodes 10-16. Appended, never interleaved: opcodes 0-9 keep
            // their numbers and their behaviour.
            //
            // 10 — doorbellStart(seed[32], stateDir, cacheDir, bridges) -> onion address bytes.
            // Same argument shape as opcode 1, because the doorbell may have to build the Arti
            // host itself when the messaging transport is not running (minimal mode).
            10 => {
                let seed = r.bytes(32)?;
                let state = std::str::from_utf8(r.bytes(4096)?)?;
                let cache = std::str::from_utf8(r.bytes(4096)?)?;
                let bridges = r
                    .list(16, 1024)?
                    .into_iter()
                    .map(String::from_utf8)
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                r.finish()?;
                doorbell::start(seed, state, cache, &bridges)?.into_bytes()
            }
            // 11 — doorbellOnion() -> onion address bytes. Mirrors opcode 2; also the cheapest
            // way for Kotlin to ask whether the doorbell is up (it fails when it is not).
            11 => {
                r.finish()?;
                doorbell::onion()?.into_bytes()
            }
            // 12 — doorbellTokens(list of opaque token blobs) -> empty. Replaces the whole set.
            12 => {
                let tokens = r.list(256, 64)?;
                r.finish()?;
                doorbell::set_tokens(tokens)?;
                Vec::new()
            }
            // 13 — doorbellPoll(timeoutMs) -> empty when nothing rang, else u32 big-endian with
            // the number of accepted knocks drained. Carries no identity and no timestamp.
            13 => {
                let timeout = r.u32()? as i32;
                r.finish()?;
                match doorbell::poll_events(timeout)? {
                    0 => Vec::new(),
                    count => count.to_be_bytes().to_vec(),
                }
            }
            // 14 — doorbellMinimal() -> empty. Destroys the messaging transport exactly as
            // opcode 8 does, while the doorbell keeps serving on the shared Arti host.
            14 => {
                r.finish()?;
                doorbell::minimal_mode()?;
                Vec::new()
            }
            // 15 — doorbellStop() -> empty. Stops the doorbell and forgets its tokens.
            15 => {
                r.finish()?;
                doorbell::stop()?;
                Vec::new()
            }
            // 16 — doorbellKnock(onion[62], token) -> 1 byte, 1 acknowledged / 0 not. The token
            // is consumed here and never crosses back into Kotlin.
            16 => {
                let onion = std::str::from_utf8(r.bytes(62)?)?;
                let token = Zeroizing::new(r.bytes(64)?.to_vec());
                r.finish()?;
                vec![u8::from(doorbell::knock(onion, &token)?)]
            }
            _ => anyhow::bail!("Unknown Tor operation"),
        })
    }));
    // Fixed strings only, chosen by opcode range. No error, token, knock or address byte ever
    // reaches a Java exception message.
    let (failure, unavailable) = if (10..=16).contains(&operation) {
        ("Doorbell operation failed", "Doorbell output unavailable")
    } else {
        ("Tor operation failed", "Tor output unavailable")
    };
    match result {
        Ok(Ok(bytes)) => {
            let bytes = Zeroizing::new(bytes);
            match env.byte_array_from_slice(&bytes) {
                Ok(a) => a.into_raw(),
                Err(_) => {
                    let _ = env.throw_new("java/lang/IllegalStateException", unavailable);
                    std::ptr::null_mut()
                }
            }
        }
        Ok(Err(_)) => {
            let _ = env.throw_new("java/lang/IllegalStateException", failure);
            std::ptr::null_mut()
        }
        Err(_) => {
            // A panic leaves unknown state behind, so both services go down, not just the one
            // whose opcode panicked.
            let _ = std::panic::catch_unwind(|| {
                let _ = doorbell::stop();
                let _ = tor::stop();
            });
            let _ = env.throw_new("java/lang/IllegalStateException", failure);
            std::ptr::null_mut()
        }
    }
}
