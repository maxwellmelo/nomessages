use libsodium_rs::{
    crypto_aead::xchacha20poly1305 as xchacha, crypto_generichash::blake2b, crypto_hash::sha256,
    crypto_pwhash::argon2id, crypto_sign, random as sodium_random,
};
use std::sync::OnceLock;
use zeroize::{Zeroize, Zeroizing};

pub const MAX_ALLOCATION: usize = 64 * 1024 * 1024;
pub const MAX_INPUT: usize = 64 * 1024 * 1024;
pub const MAX_PASSWORD: usize = 1024 * 1024;
const KDF_OUTPUT_BYTES: usize = 32;
const MIN_MEMORY_KIB: usize = 65_536;
const MAX_MEMORY_KIB: usize = 262_144;
const MIN_ITERATIONS: usize = 1;
const MAX_ITERATIONS: usize = 20;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CryptoError {
    InvalidArgument,
    OperationFailed,
}

pub struct SigningKeyPair {
    pub public_key: Vec<u8>,
    pub secret_key: Vec<u8>,
}

impl Drop for SigningKeyPair {
    fn drop(&mut self) {
        self.secret_key.zeroize();
    }
}

fn ensure_initialized() -> Result<(), CryptoError> {
    static INITIALIZED: OnceLock<bool> = OnceLock::new();
    if *INITIALIZED.get_or_init(|| libsodium_rs::ensure_init().is_ok()) {
        Ok(())
    } else {
        Err(CryptoError::OperationFailed)
    }
}

fn valid_input(bytes: &[u8]) -> Result<(), CryptoError> {
    if bytes.len() <= MAX_INPUT {
        Ok(())
    } else {
        Err(CryptoError::InvalidArgument)
    }
}

pub fn random(size: usize) -> Result<Vec<u8>, CryptoError> {
    ensure_initialized()?;
    if size > MAX_ALLOCATION {
        return Err(CryptoError::InvalidArgument);
    }
    Ok(sodium_random::bytes(size))
}

pub fn derive(
    password: &[u8],
    salt: &[u8],
    memory_kib: usize,
    iterations: usize,
) -> Result<Vec<u8>, CryptoError> {
    ensure_initialized()?;
    if password.len() > MAX_PASSWORD
        || salt.len() != argon2id::SALTBYTES
        || !(MIN_MEMORY_KIB..=MAX_MEMORY_KIB).contains(&memory_kib)
        || !(MIN_ITERATIONS..=MAX_ITERATIONS).contains(&iterations)
    {
        return Err(CryptoError::InvalidArgument);
    }
    let memory_bytes = memory_kib
        .checked_mul(1024)
        .ok_or(CryptoError::InvalidArgument)?;
    let output = Zeroizing::new(
        argon2id::pwhash(
            KDF_OUTPUT_BYTES,
            password,
            salt,
            iterations as u64,
            memory_bytes,
        )
        .map_err(|_| CryptoError::OperationFailed)?,
    );
    Ok(output.to_vec())
}

pub fn seal(key: &[u8], plaintext: &[u8], aad: &[u8]) -> Result<Vec<u8>, CryptoError> {
    ensure_initialized()?;
    valid_input(plaintext)?;
    valid_input(aad)?;
    let key = xchacha::Key::from_bytes(key).map_err(|_| CryptoError::InvalidArgument)?;
    let nonce = xchacha::Nonce::generate();
    let ciphertext = xchacha::encrypt(plaintext, Some(aad), &nonce, &key)
        .map_err(|_| CryptoError::OperationFailed)?;
    let output_len = xchacha::NPUBBYTES
        .checked_add(ciphertext.len())
        .ok_or(CryptoError::InvalidArgument)?;
    if output_len > MAX_ALLOCATION + xchacha::NPUBBYTES + xchacha::ABYTES {
        return Err(CryptoError::InvalidArgument);
    }
    let mut output = Vec::with_capacity(output_len);
    output.extend_from_slice(nonce.as_ref());
    output.extend_from_slice(&ciphertext);
    Ok(output)
}

pub fn open(key: &[u8], sealed: &[u8], aad: &[u8]) -> Result<Option<Vec<u8>>, CryptoError> {
    ensure_initialized()?;
    valid_input(aad)?;
    let minimum = xchacha::NPUBBYTES + xchacha::ABYTES;
    if sealed.len() < minimum || sealed.len() > MAX_INPUT + minimum {
        return Err(CryptoError::InvalidArgument);
    }
    let key = xchacha::Key::from_bytes(key).map_err(|_| CryptoError::InvalidArgument)?;
    let (nonce, ciphertext) = sealed.split_at(xchacha::NPUBBYTES);
    let nonce = xchacha::Nonce::try_from_slice(nonce).map_err(|_| CryptoError::InvalidArgument)?;
    Ok(xchacha::decrypt(ciphertext, Some(aad), &nonce, &key).ok())
}

pub fn hash(message: &[u8]) -> Result<Vec<u8>, CryptoError> {
    ensure_initialized()?;
    valid_input(message)?;
    Ok(sha256::hash(message).to_vec())
}

pub fn mac(key: &[u8], message: &[u8]) -> Result<Vec<u8>, CryptoError> {
    ensure_initialized()?;
    valid_input(message)?;
    if !(16..=64).contains(&key.len()) {
        return Err(CryptoError::InvalidArgument);
    }
    Ok(blake2b::hash_with_key(message, key, 32))
}

pub fn signing_key_pair() -> Result<SigningKeyPair, CryptoError> {
    ensure_initialized()?;
    let key_pair = crypto_sign::KeyPair::generate().map_err(|_| CryptoError::OperationFailed)?;
    Ok(SigningKeyPair {
        public_key: key_pair.public_key.as_bytes().to_vec(),
        secret_key: key_pair.secret_key.as_bytes().to_vec(),
    })
}

pub fn signing_public_key_from_seed(seed: &[u8]) -> Result<Vec<u8>, CryptoError> {
    ensure_initialized()?;
    if seed.len() != crypto_sign::SEEDBYTES {
        return Err(CryptoError::InvalidArgument);
    }
    let key_pair =
        crypto_sign::KeyPair::from_seed(seed).map_err(|_| CryptoError::OperationFailed)?;
    Ok(key_pair.public_key.as_bytes().to_vec())
}

pub fn sign(secret_key: &[u8], message: &[u8]) -> Result<Vec<u8>, CryptoError> {
    ensure_initialized()?;
    valid_input(message)?;
    let secret_key =
        crypto_sign::SecretKey::from_bytes(secret_key).map_err(|_| CryptoError::InvalidArgument)?;
    Ok(crypto_sign::sign_detached(message, &secret_key)
        .map_err(|_| CryptoError::OperationFailed)?
        .to_vec())
}

pub fn verify(public_key: &[u8], message: &[u8], signature: &[u8]) -> Result<bool, CryptoError> {
    ensure_initialized()?;
    valid_input(message)?;
    let public_key =
        crypto_sign::PublicKey::from_bytes(public_key).map_err(|_| CryptoError::InvalidArgument)?;
    if signature.len() != crypto_sign::BYTES {
        return Err(CryptoError::InvalidArgument);
    }
    let signature: &[u8; crypto_sign::BYTES] = signature
        .try_into()
        .map_err(|_| CryptoError::InvalidArgument)?;
    Ok(crypto_sign::verify_detached(
        signature,
        message,
        &public_key,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(value: &str) -> Vec<u8> {
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| {
                let text = std::str::from_utf8(pair).unwrap();
                u8::from_str_radix(text, 16).unwrap()
            })
            .collect()
    }

    #[test]
    fn sha256_matches_fips_vector() {
        assert_eq!(
            hash(b"abc").unwrap(),
            hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        );
    }

    #[test]
    fn keyed_blake2b_256_matches_independent_vector() {
        let key: Vec<u8> = (0..32).collect();
        assert_eq!(
            mac(&key, b"abc").unwrap(),
            hex("d63a32d3e44738d7907f964316c241adaba0abfeabc32349677578a15a203f7f")
        );
    }

    #[test]
    fn argon2id_uses_exact_memory_iterations_and_parallelism() {
        let salt: Vec<u8> = (0..16).collect();
        assert_eq!(
            derive(b"password", &salt, 65_536, 2).unwrap(),
            hex("3fde0023e9c90652edac14784971e4258e084751bce00b5965d995c639b15cff")
        );
    }

    #[test]
    fn argon2id_rejects_parameters_outside_the_contract() {
        let salt = [7; 16];
        // The first case is also the RFC 9106 section 5.3 memory parameter.
        // That official vector pins m=32 KiB, t=3, p=4, an 8-byte secret key
        // and 12-byte associated data; of those only memory and iterations are
        // expressible through `derive`, because libsodium fixes parallelism at
        // 1 and exposes neither the secret nor the associated data. Asserting
        // the floor keeps the deviation observable: nothing here proves the
        // other three parameters are unreachable (that stays a documented API
        // fact in `crypto-report.md`), but widening the contract down to
        // 32 KiB would break this test instead of silently changing what the
        // report claims.
        assert!(MIN_MEMORY_KIB > 32, "RFC 9106 section 5.3 uses m=32 KiB");
        assert_eq!(
            derive(&[1; 32], &salt, 32, 3),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            derive(b"password", &salt, 65_535, 2),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            derive(b"password", &salt, 262_145, 2),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            derive(b"password", &salt, 65_536, 0),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            derive(b"password", &salt, 65_536, 21),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            derive(b"password", &[7; 15], 65_536, 2),
            Err(CryptoError::InvalidArgument)
        );
    }

    #[test]
    fn xchacha_round_trip_binds_aad_and_rejects_tampering() {
        let key = [11; 32];
        let plaintext = b"private message";
        let sealed = seal(&key, plaintext, b"header").unwrap();

        assert_eq!(sealed.len(), 24 + plaintext.len() + 16);
        assert_eq!(
            open(&key, &sealed, b"header").unwrap(),
            Some(plaintext.to_vec())
        );
        assert_eq!(open(&key, &sealed, b"wrong header").unwrap(), None);

        let mut tampered = sealed;
        *tampered.last_mut().unwrap() ^= 1;
        assert_eq!(open(&key, &tampered, b"header").unwrap(), None);
    }

    #[test]
    fn xchacha_rejects_bad_key_and_truncated_input() {
        assert_eq!(
            seal(&[0; 31], b"message", b""),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            open(&[0; 31], &[0; 40], b""),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            open(&[0; 32], &[0; 39], b""),
            Err(CryptoError::InvalidArgument)
        );
    }

    #[test]
    fn ed25519_matches_rfc8032_test_vector_one() {
        let seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        let public_key = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        let mut secret_key = seed.clone();
        secret_key.extend_from_slice(&public_key);
        let signature = hex(concat!(
            "e5564300c360ac729086e2cc806e828a",
            "84877f1eb8e5d974d873e06522490155",
            "5fb8821590a33bacc61e39701cf9b46b",
            "d25bf5f0595bbe24655141438e7a100b"
        ));

        assert_eq!(signing_public_key_from_seed(&seed).unwrap(), public_key);
        assert_eq!(sign(&secret_key, b"").unwrap(), signature);
        assert_eq!(verify(&public_key, b"", &signature).unwrap(), true);
    }

    #[test]
    fn ed25519_rejects_tampering_and_bad_lengths() {
        let keys = signing_key_pair().unwrap();
        let mut signature = sign(&keys.secret_key, b"message").unwrap();
        signature[0] ^= 1;
        assert_eq!(
            verify(&keys.public_key, b"message", &signature).unwrap(),
            false
        );
        assert_eq!(
            sign(&[0; 63], b"message"),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            verify(&[0; 31], b"message", &[0; 64]),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            verify(&[0; 32], b"message", &[0; 63]),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            signing_public_key_from_seed(&[0; 31]),
            Err(CryptoError::InvalidArgument)
        );
    }

    #[test]
    fn random_and_generated_signing_keys_have_contract_lengths() {
        let bytes = random(32).unwrap();
        assert_eq!(bytes.len(), 32);
        assert!(bytes.iter().any(|byte| *byte != 0));

        let keys = signing_key_pair().unwrap();
        assert_eq!(keys.public_key.len(), 32);
        assert_eq!(keys.secret_key.len(), 64);
    }

    #[test]
    fn allocation_limits_are_rejected_before_work() {
        assert_eq!(
            random(MAX_ALLOCATION + 1),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            hash(&vec![0; MAX_INPUT + 1]),
            Err(CryptoError::InvalidArgument)
        );
        assert_eq!(
            seal(&[0; 32], &vec![0; MAX_INPUT + 1], b""),
            Err(CryptoError::InvalidArgument)
        );
    }
}
