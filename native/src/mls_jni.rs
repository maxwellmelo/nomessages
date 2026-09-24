use crate::{
    mls,
    mls::MAX_FRAME,
    wire::{MAX_STATE, Reader},
};
use anyhow::{Result, ensure};
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::{jbyteArray, jint},
};
use zeroize::Zeroizing;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_nativebridge_MlsNative_transact(
    mut env: JNIEnv,
    _: JObject,
    operation: jint,
    state: JByteArray,
    arguments: JByteArray,
) -> jbyteArray {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Vec<u8>> {
        ensure!(
            env.get_array_length(&state)? as usize <= MAX_STATE,
            "State too large"
        );
        ensure!(
            env.get_array_length(&arguments)? as usize <= MAX_STATE,
            "Arguments too large"
        );
        let state = Zeroizing::new(env.convert_byte_array(&state)?);
        let args = Zeroizing::new(env.convert_byte_array(&arguments)?);
        let mut r = Reader::new(&args)?;
        let output = match operation {
            0 => {
                let seed = r.bytes(32)?;
                r.finish()?;
                mls::key_package(seed)?
            }
            1 => {
                r.finish()?;
                mls::create(&state)?
            }
            2 => {
                let packages = r.list(99, MAX_FRAME)?;
                let approved = r.list(100, 32)?;
                r.finish()?;
                mls::add(&state, &packages, &approved)?
            }
            3 => {
                let welcome = r.bytes(MAX_FRAME)?;
                let approved = r.list(100, 32)?;
                let coordinator = r.bytes(32)?;
                r.finish()?;
                mls::join(&state, welcome, &approved, coordinator)?
            }
            4 => {
                let plaintext = r.bytes(MAX_FRAME)?;
                r.finish()?;
                mls::encrypt(&state, plaintext)?
            }
            5 => {
                let message = r.bytes(MAX_FRAME)?;
                let approved = r.list(100, 32)?;
                let coordinator = r.bytes(32)?;
                r.finish()?;
                mls::process(&state, message, &approved, coordinator)?
            }
            6 => {
                let removed = r.list(99, 32)?;
                let approved = r.list(100, 32)?;
                r.finish()?;
                mls::remove(&state, &removed, &approved)?
            }
            _ => anyhow::bail!("Unknown MLS operation"),
        };
        output.encode()
    }));
    match result {
        Ok(Ok(bytes)) => {
            let bytes = Zeroizing::new(bytes);
            match env.byte_array_from_slice(&bytes) {
                Ok(array) => array.into_raw(),
                Err(_) => {
                    let _ =
                        env.throw_new("java/lang/IllegalStateException", "MLS output unavailable");
                    std::ptr::null_mut()
                }
            }
        }
        _ => {
            let _ = env.throw_new("java/lang/IllegalStateException", "MLS operation rejected");
            std::ptr::null_mut()
        }
    }
}
