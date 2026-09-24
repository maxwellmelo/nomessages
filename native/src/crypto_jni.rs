use crate::crypto::{self, CryptoError};
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::{JNI_FALSE, JNI_TRUE, jboolean, jbyteArray, jint},
};
use std::{
    panic::{AssertUnwindSafe, catch_unwind},
    ptr,
};
use zeroize::Zeroizing;

#[derive(Clone, Copy)]
enum BridgeError {
    InvalidArgument,
    OperationFailed,
}

impl From<CryptoError> for BridgeError {
    fn from(value: CryptoError) -> Self {
        match value {
            CryptoError::InvalidArgument => Self::InvalidArgument,
            CryptoError::OperationFailed => Self::OperationFailed,
        }
    }
}

impl From<jni::errors::Error> for BridgeError {
    fn from(_: jni::errors::Error) -> Self {
        Self::OperationFailed
    }
}

type BridgeResult<T> = Result<T, BridgeError>;

fn read_bytes(
    env: &mut JNIEnv<'_>,
    array: &JByteArray<'_>,
    maximum: usize,
) -> BridgeResult<Zeroizing<Vec<u8>>> {
    if array.as_raw().is_null() {
        return Err(BridgeError::InvalidArgument);
    }
    let length =
        usize::try_from(env.get_array_length(array)?).map_err(|_| BridgeError::InvalidArgument)?;
    if length > maximum {
        return Err(BridgeError::InvalidArgument);
    }
    Ok(Zeroizing::new(env.convert_byte_array(array)?))
}

fn nonnegative(value: jint) -> BridgeResult<usize> {
    usize::try_from(value).map_err(|_| BridgeError::InvalidArgument)
}

fn throw_error(env: &mut JNIEnv<'_>, error: BridgeError) {
    if env.exception_check().unwrap_or(true) {
        return;
    }
    let (class, message) = match error {
        BridgeError::InvalidArgument => (
            "java/lang/IllegalArgumentException",
            "Invalid cryptographic argument",
        ),
        BridgeError::OperationFailed => (
            "java/lang/IllegalStateException",
            "Native cryptographic operation failed",
        ),
    };
    let _ = env.throw_new(class, message);
}

fn byte_array_call<'local, F>(mut env: JNIEnv<'local>, call: F) -> jbyteArray
where
    F: FnOnce(&mut JNIEnv<'local>) -> BridgeResult<Vec<u8>>,
{
    match catch_unwind(AssertUnwindSafe(|| call(&mut env))) {
        Ok(Ok(bytes)) => {
            let bytes = Zeroizing::new(bytes);
            match env.byte_array_from_slice(&bytes) {
                Ok(array) => array.into_raw(),
                Err(error) => {
                    throw_error(&mut env, error.into());
                    ptr::null_mut()
                }
            }
        }
        Ok(Err(error)) => {
            throw_error(&mut env, error);
            ptr::null_mut()
        }
        Err(_) => {
            throw_error(&mut env, BridgeError::OperationFailed);
            ptr::null_mut()
        }
    }
}

fn nullable_byte_array_call<'local, F>(mut env: JNIEnv<'local>, call: F) -> jbyteArray
where
    F: FnOnce(&mut JNIEnv<'local>) -> BridgeResult<Option<Vec<u8>>>,
{
    match catch_unwind(AssertUnwindSafe(|| call(&mut env))) {
        Ok(Ok(Some(bytes))) => {
            let bytes = Zeroizing::new(bytes);
            match env.byte_array_from_slice(&bytes) {
                Ok(array) => array.into_raw(),
                Err(error) => {
                    throw_error(&mut env, error.into());
                    ptr::null_mut()
                }
            }
        }
        Ok(Ok(None)) => ptr::null_mut(),
        Ok(Err(error)) => {
            throw_error(&mut env, error);
            ptr::null_mut()
        }
        Err(_) => {
            throw_error(&mut env, BridgeError::OperationFailed);
            ptr::null_mut()
        }
    }
}

fn boolean_call<'local, F>(mut env: JNIEnv<'local>, call: F) -> jboolean
where
    F: FnOnce(&mut JNIEnv<'local>) -> BridgeResult<bool>,
{
    match catch_unwind(AssertUnwindSafe(|| call(&mut env))) {
        Ok(Ok(true)) => JNI_TRUE,
        Ok(Ok(false)) => JNI_FALSE,
        Ok(Err(error)) => {
            throw_error(&mut env, error);
            JNI_FALSE
        }
        Err(_) => {
            throw_error(&mut env, BridgeError::OperationFailed);
            JNI_FALSE
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeRandom<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    size: jint,
) -> jbyteArray {
    byte_array_call(env, |_| Ok(crypto::random(nonnegative(size)?)?))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeDerive<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    password: JByteArray<'local>,
    salt: JByteArray<'local>,
    memory_kib: jint,
    iterations: jint,
) -> jbyteArray {
    byte_array_call(env, |env| {
        let password = read_bytes(env, &password, crypto::MAX_PASSWORD)?;
        let salt = read_bytes(env, &salt, 16)?;
        Ok(crypto::derive(
            &password,
            &salt,
            nonnegative(memory_kib)?,
            nonnegative(iterations)?,
        )?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeSeal<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JByteArray<'local>,
    plaintext: JByteArray<'local>,
    aad: JByteArray<'local>,
) -> jbyteArray {
    byte_array_call(env, |env| {
        let key = read_bytes(env, &key, 32)?;
        let plaintext = read_bytes(env, &plaintext, crypto::MAX_INPUT)?;
        let aad = read_bytes(env, &aad, crypto::MAX_INPUT)?;
        Ok(crypto::seal(&key, &plaintext, &aad)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeOpen<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JByteArray<'local>,
    sealed: JByteArray<'local>,
    aad: JByteArray<'local>,
) -> jbyteArray {
    nullable_byte_array_call(env, |env| {
        let key = read_bytes(env, &key, 32)?;
        let sealed = read_bytes(env, &sealed, crypto::MAX_INPUT + 40)?;
        let aad = read_bytes(env, &aad, crypto::MAX_INPUT)?;
        Ok(crypto::open(&key, &sealed, &aad)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeHash<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    message: JByteArray<'local>,
) -> jbyteArray {
    byte_array_call(env, |env| {
        let message = read_bytes(env, &message, crypto::MAX_INPUT)?;
        Ok(crypto::hash(&message)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeMac<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JByteArray<'local>,
    message: JByteArray<'local>,
) -> jbyteArray {
    byte_array_call(env, |env| {
        let key = read_bytes(env, &key, 64)?;
        let message = read_bytes(env, &message, crypto::MAX_INPUT)?;
        Ok(crypto::mac(&key, &message)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeSigningKeyPair<
    'local,
>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jbyteArray {
    byte_array_call(env, |_| {
        let keys = crypto::signing_key_pair()?;
        let mut encoded = Zeroizing::new(Vec::with_capacity(96));
        encoded.extend_from_slice(&keys.public_key);
        encoded.extend_from_slice(&keys.secret_key);
        Ok(encoded.to_vec())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeSign<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    secret_key: JByteArray<'local>,
    message: JByteArray<'local>,
) -> jbyteArray {
    byte_array_call(env, |env| {
        let secret_key = read_bytes(env, &secret_key, 64)?;
        let message = read_bytes(env, &message, crypto::MAX_INPUT)?;
        Ok(crypto::sign(&secret_key, &message)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_mx3_nomessages_core_crypto_NativeCrypto_nativeVerify<'local>(
    env: JNIEnv<'local>,
    _this: JObject<'local>,
    public_key: JByteArray<'local>,
    message: JByteArray<'local>,
    signature: JByteArray<'local>,
) -> jboolean {
    boolean_call(env, |env| {
        let public_key = read_bytes(env, &public_key, 32)?;
        let message = read_bytes(env, &message, crypto::MAX_INPUT)?;
        let signature = read_bytes(env, &signature, 64)?;
        Ok(crypto::verify(&public_key, &message, &signature)?)
    })
}
