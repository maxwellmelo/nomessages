pub mod crypto;
pub mod crypto_jni;
#[cfg(feature = "tor")]
pub mod doorbell;
#[cfg(feature = "mls")]
pub mod mls;
#[cfg(feature = "mls")]
mod mls_jni;
#[cfg(feature = "tor")]
pub mod tor;
#[cfg(feature = "tor")]
mod tor_jni;
mod wire;

// Panic payloads from cryptographic dependencies must never enter Android logs.
// JNI entrypoints catch unwinds and translate them to fixed public errors.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(
    _: *mut jni::sys::JavaVM,
    _: *mut std::ffi::c_void,
) -> jni::sys::jint {
    std::panic::set_hook(Box::new(|_| {}));
    jni::sys::JNI_VERSION_1_6
}
