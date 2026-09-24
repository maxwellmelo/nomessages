//! Known-answer tests (KAT) for the primitives behind `nomessages::crypto`.
//!
//! Sources, digests and the mapping between each vector file and the exported
//! symbols are documented in `docs/development/crypto-report.md`.
//!
//! The suite is dependency free on purpose: the Wycheproof files are embedded
//! verbatim and parsed by the minimal reader in [`json`], so no crate is added
//! to `Cargo.lock` and the tests run under every feature combination.

use libsodium_rs::crypto_aead::{chacha20poly1305_ietf as chacha, xchacha20poly1305 as xchacha};
use libsodium_rs::crypto_scalarmult::curve25519;
use nomessages::crypto;

const CHACHA_VECTORS: &str = include_str!("vectors/chacha20_poly1305_test.json");
const CHACHA_DIGEST: &str = "fe61d25f90e1bde4461d00eafe61049e5f29bd999f36b766df9cda90906ad53d";
const XCHACHA_VECTORS: &str = include_str!("vectors/xchacha20_poly1305_test.json");
const XCHACHA_DIGEST: &str = "a79de072571b90eb40c3a63ce0c7f75dcb4b62323c8870228e1f61dcc61d63a9";
const X25519_VECTORS: &str = include_str!("vectors/x25519_test.json");
const X25519_DIGEST: &str = "35c3f5231cf25cc640b524d403461deee9e49441d5d915a3a25b2c8ff5adbe7d";
const ED25519_VECTORS: &str = include_str!("vectors/ed25519_test.json");
const ED25519_DIGEST: &str = "752d2ea7d7c6cf4736381b6cbacb61f8182b126ab7cd9b058f00c50084975536";

/// Minimal JSON reader. Wycheproof files only use objects, arrays, strings and
/// integers, but escapes and unicode sequences are handled for completeness.
mod json {
    #[derive(Debug, Clone, PartialEq)]
    pub enum Value {
        Null,
        Bool(bool),
        Number(f64),
        Text(String),
        Array(Vec<Value>),
        Object(Vec<(String, Value)>),
    }

    impl Value {
        pub fn field(&self, name: &str) -> Option<&Value> {
            match self {
                Value::Object(entries) => entries
                    .iter()
                    .find(|(key, _)| key == name)
                    .map(|(_, value)| value),
                _ => None,
            }
        }

        /// Required string field.
        pub fn text(&self, name: &str) -> &str {
            match self.field(name) {
                Some(Value::Text(text)) => text,
                _ => panic!("missing string field `{name}`"),
            }
        }

        /// Optional string field; Wycheproof omits empty `aad`/`msg` never, but
        /// schema evolution should not break the suite.
        pub fn text_or_empty(&self, name: &str) -> &str {
            match self.field(name) {
                Some(Value::Text(text)) => text,
                None => "",
                _ => panic!("field `{name}` is not a string"),
            }
        }

        pub fn number(&self, name: &str) -> u64 {
            match self.field(name) {
                Some(Value::Number(value)) => *value as u64,
                _ => panic!("missing numeric field `{name}`"),
            }
        }

        pub fn items(&self, name: &str) -> &[Value] {
            match self.field(name) {
                Some(Value::Array(items)) => items,
                _ => panic!("missing array field `{name}`"),
            }
        }
    }

    pub fn parse(input: &str) -> Value {
        let bytes = input.as_bytes();
        let mut cursor = 0usize;
        let value = value(bytes, &mut cursor);
        whitespace(bytes, &mut cursor);
        assert_eq!(cursor, bytes.len(), "trailing data after JSON document");
        value
    }

    fn whitespace(bytes: &[u8], cursor: &mut usize) {
        while *cursor < bytes.len() && matches!(bytes[*cursor], b' ' | b'\t' | b'\n' | b'\r') {
            *cursor += 1;
        }
    }

    fn expect(bytes: &[u8], cursor: &mut usize, literal: &[u8]) {
        assert!(
            bytes[*cursor..].starts_with(literal),
            "expected `{}` at byte {}",
            String::from_utf8_lossy(literal),
            cursor
        );
        *cursor += literal.len();
    }

    fn value(bytes: &[u8], cursor: &mut usize) -> Value {
        whitespace(bytes, cursor);
        match bytes.get(*cursor) {
            Some(b'{') => object(bytes, cursor),
            Some(b'[') => array(bytes, cursor),
            Some(b'"') => Value::Text(string(bytes, cursor)),
            Some(b't') => {
                expect(bytes, cursor, b"true");
                Value::Bool(true)
            }
            Some(b'f') => {
                expect(bytes, cursor, b"false");
                Value::Bool(false)
            }
            Some(b'n') => {
                expect(bytes, cursor, b"null");
                Value::Null
            }
            Some(_) => number(bytes, cursor),
            None => panic!("unexpected end of JSON document"),
        }
    }

    fn object(bytes: &[u8], cursor: &mut usize) -> Value {
        expect(bytes, cursor, b"{");
        let mut entries = Vec::new();
        whitespace(bytes, cursor);
        if bytes[*cursor] == b'}' {
            *cursor += 1;
            return Value::Object(entries);
        }
        loop {
            whitespace(bytes, cursor);
            let key = string(bytes, cursor);
            whitespace(bytes, cursor);
            expect(bytes, cursor, b":");
            let item = value(bytes, cursor);
            entries.push((key, item));
            whitespace(bytes, cursor);
            match bytes[*cursor] {
                b',' => *cursor += 1,
                b'}' => {
                    *cursor += 1;
                    return Value::Object(entries);
                }
                other => panic!("unexpected byte `{}` inside object", other as char),
            }
        }
    }

    fn array(bytes: &[u8], cursor: &mut usize) -> Value {
        expect(bytes, cursor, b"[");
        let mut items = Vec::new();
        whitespace(bytes, cursor);
        if bytes[*cursor] == b']' {
            *cursor += 1;
            return Value::Array(items);
        }
        loop {
            items.push(value(bytes, cursor));
            whitespace(bytes, cursor);
            match bytes[*cursor] {
                b',' => *cursor += 1,
                b']' => {
                    *cursor += 1;
                    return Value::Array(items);
                }
                other => panic!("unexpected byte `{}` inside array", other as char),
            }
        }
    }

    fn string(bytes: &[u8], cursor: &mut usize) -> String {
        expect(bytes, cursor, b"\"");
        let mut text = String::new();
        loop {
            let byte = bytes[*cursor];
            *cursor += 1;
            match byte {
                b'"' => return text,
                b'\\' => {
                    let escape = bytes[*cursor];
                    *cursor += 1;
                    match escape {
                        b'"' => text.push('"'),
                        b'\\' => text.push('\\'),
                        b'/' => text.push('/'),
                        b'b' => text.push('\u{0008}'),
                        b'f' => text.push('\u{000c}'),
                        b'n' => text.push('\n'),
                        b'r' => text.push('\r'),
                        b't' => text.push('\t'),
                        b'u' => text.push(unicode(bytes, cursor)),
                        other => panic!("unsupported escape `\\{}`", other as char),
                    }
                }
                _ => {
                    // Multi-byte UTF-8 sequences are copied verbatim.
                    let start = *cursor - 1;
                    let width = utf8_width(byte);
                    *cursor = start + width;
                    text.push_str(std::str::from_utf8(&bytes[start..*cursor]).unwrap());
                }
            }
        }
    }

    /// Length in bytes of the UTF-8 sequence that starts with `byte`.
    ///
    /// Only lead bytes are valid here: a continuation byte (`0x80..=0xbf`) or an
    /// overlong lead (`0xc0..=0xc1`, `0xf5..=0xff`) would desynchronise the
    /// scanner and slice mid-character, so it aborts with a readable message
    /// instead of a bare `Utf8Error` further down.
    fn utf8_width(byte: u8) -> usize {
        match byte {
            0x00..=0x7f => 1,
            0xc2..=0xdf => 2,
            0xe0..=0xef => 3,
            0xf0..=0xf4 => 4,
            other => panic!("invalid UTF-8 lead byte {other:#04x}"),
        }
    }

    fn unicode(bytes: &[u8], cursor: &mut usize) -> char {
        let first = code_unit(bytes, cursor);
        if (0xd800..0xdc00).contains(&first) {
            expect(bytes, cursor, b"\\u");
            let second = code_unit(bytes, cursor);
            let combined = 0x10000 + ((first - 0xd800) << 10) + (second - 0xdc00);
            return char::from_u32(combined).expect("invalid surrogate pair");
        }
        char::from_u32(first).expect("invalid unicode escape")
    }

    fn code_unit(bytes: &[u8], cursor: &mut usize) -> u32 {
        let text = std::str::from_utf8(&bytes[*cursor..*cursor + 4]).unwrap();
        *cursor += 4;
        u32::from_str_radix(text, 16).expect("invalid unicode escape")
    }

    fn number(bytes: &[u8], cursor: &mut usize) -> Value {
        let start = *cursor;
        while *cursor < bytes.len()
            && matches!(bytes[*cursor], b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9')
        {
            *cursor += 1;
        }
        let text = std::str::from_utf8(&bytes[start..*cursor]).unwrap();
        Value::Number(text.parse().expect("invalid number"))
    }
}

fn hex(value: &str) -> Vec<u8> {
    assert!(value.len() % 2 == 0, "odd-length hex string");
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let text = std::str::from_utf8(pair).unwrap();
            u8::from_str_radix(text, 16).expect("invalid hex digit")
        })
        .collect()
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// Confirms that an embedded vector file still is the downloaded artifact.
/// Carriage returns are removed first so a CRLF checkout cannot fake a
/// mismatch; the repository forces LF through `.gitattributes`.
fn assert_digest(name: &str, contents: &str, expected: &str) {
    let canonical: Vec<u8> = contents.bytes().filter(|byte| *byte != b'\r').collect();
    let digest = crypto::hash(&canonical).expect("sha-256 of the vector file");
    assert_eq!(to_hex(&digest), expected, "{name} was modified");
}

/// Loads a Wycheproof file and checks its digest and advertised test count.
fn wycheproof(name: &str, contents: &str, digest: &str) -> json::Value {
    assert_digest(name, contents, digest);
    let suite = json::parse(contents);
    let counted: usize = suite
        .items("testGroups")
        .iter()
        .map(|group| group.items("tests").len())
        .sum();
    assert_eq!(
        counted,
        suite.number("numberOfTests") as usize,
        "{name} declares a different number of tests"
    );
    suite
}

/// Outcome of one AEAD or signature case, independent of the expected result.
#[derive(Debug, PartialEq)]
enum Accepted {
    /// The primitive returned the expected plaintext or a valid signature.
    Yes,
    /// The primitive rejected the case (bad tag, bad length, bad key).
    No,
    /// The primitive authenticated the input but returned a plaintext that is
    /// not the one in the vector. For an AEAD this means the tag verified over
    /// a ciphertext the vector rejects, i.e. a forgery, so it is never treated
    /// as a valid rejection: it is counted per suite and asserted to be zero.
    Mismatch,
}

/// Applies the Wycheproof result semantics: `valid` must be accepted,
/// `invalid` must be rejected outright and `acceptable` may go either way (it
/// marks legacy or edge-case behaviour that implementations are free to
/// refuse).
///
/// `invalid` demands exactly [`Accepted::No`]: accepting [`Accepted::Mismatch`]
/// here would let a forged-but-authenticating ciphertext pass as a rejection.
fn judge(result: &str, accepted: Accepted, case: &str, acceptable: &mut usize) {
    match result {
        "valid" => assert_eq!(accepted, Accepted::Yes, "{case} should be accepted"),
        "invalid" => assert_eq!(accepted, Accepted::No, "{case} should be rejected"),
        "acceptable" => *acceptable += 1,
        other => panic!("{case} has unknown result `{other}`"),
    }
}

fn sodium() {
    libsodium_rs::ensure_init().expect("libsodium initialization");
}

// ---------------------------------------------------------------------------
// Explicit specification vectors
// ---------------------------------------------------------------------------

/// RFC 8439 section 2.8.2 (ChaCha20-Poly1305 AEAD).
///
/// `nomessages::crypto` exposes only the XChaCha20 variant, so the vector runs
/// against the IETF construction of the same bundled libsodium build, which is
/// the HChaCha20 core plus the identical ChaCha20-Poly1305 pipeline.
#[test]
fn rfc8439_chacha20_poly1305_aead_vector() {
    sodium();
    let key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
    let nonce = hex("070000004041424344454647");
    let aad = hex("50515253c0c1c2c3c4c5c6c7");
    let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    let expected = hex(concat!(
        "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6",
        "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36",
        "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc",
        "3ff4def08e4b7a9de576d26586cec64b6116",
        "1ae10b594f09e26a7e902ecbd0600691",
    ));

    let key = chacha::Key::from_bytes(&key).unwrap();
    let nonce = chacha::Nonce::try_from_slice(&nonce).unwrap();
    let sealed = chacha::encrypt(plaintext, Some(&aad), &nonce, &key).unwrap();
    assert_eq!(sealed, expected);
    assert_eq!(
        chacha::decrypt(&sealed, Some(&aad), &nonce, &key).unwrap(),
        plaintext
    );
}

/// draft-irtf-cfrg-xchacha section A.3.1 (XChaCha20-Poly1305 AEAD).
///
/// The sealed frame is assembled exactly as `crypto::seal` would emit it
/// (`nonce24 || ciphertext || tag16`) and is opened through the exported
/// `crypto::open`, so the vector covers the shipping wire format too.
#[test]
fn xchacha_draft_a_3_1_aead_vector() {
    sodium();
    let key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
    let nonce = hex("404142434445464748494a4b4c4d4e4f5051525354555657");
    let aad = hex("50515253c0c1c2c3c4c5c6c7");
    let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    let expected = hex(concat!(
        "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb",
        "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452",
        "2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9",
        "21f9664c97637da9768812f615c68b13b52e",
        "c0875924c1c7987947deafd8780acf49",
    ));

    let sodium_key = xchacha::Key::from_bytes(&key).unwrap();
    let sodium_nonce = xchacha::Nonce::try_from_slice(&nonce).unwrap();
    let sealed = xchacha::encrypt(plaintext, Some(&aad), &sodium_nonce, &sodium_key).unwrap();
    assert_eq!(sealed, expected);

    let mut frame = nonce.clone();
    frame.extend_from_slice(&sealed);
    assert_eq!(
        crypto::open(&key, &frame, &aad).unwrap(),
        Some(plaintext.to_vec())
    );

    // The same frame with a different AAD must not authenticate.
    assert_eq!(crypto::open(&key, &frame, b"other").unwrap(), None);
}

// ---------------------------------------------------------------------------
// Wycheproof suites
// ---------------------------------------------------------------------------

/// Wycheproof `chacha20_poly1305_test.json`, decrypt and encrypt directions.
#[test]
fn wycheproof_chacha20_poly1305() {
    sodium();
    let suite = wycheproof(
        "chacha20_poly1305_test.json",
        CHACHA_VECTORS,
        CHACHA_DIGEST,
    );
    let mut executed = 0usize;
    let mut acceptable = 0usize;
    let mut rejected_by_length = 0usize;
    let mut mismatched = 0usize;
    let mut skipped = 0usize;

    for group in suite.items("testGroups") {
        if group.number("keySize") != 256 || group.number("tagSize") != 128 {
            skipped += group.items("tests").len();
            continue;
        }
        for test in group.items("tests") {
            executed += 1;
            let case = format!("chacha20_poly1305 tcId {}", test.number("tcId"));
            let key = hex(test.text("key"));
            let nonce = hex(test.text("iv"));
            let aad = hex(test.text_or_empty("aad"));
            let message = hex(test.text_or_empty("msg"));
            let mut sealed = hex(test.text_or_empty("ct"));
            sealed.extend_from_slice(&hex(test.text("tag")));

            let key = chacha::Key::from_bytes(&key).ok();
            let nonce = chacha::Nonce::try_from_slice(&nonce).ok();
            let accepted = match (&key, &nonce) {
                (Some(key), Some(nonce)) => {
                    match chacha::decrypt(&sealed, Some(&aad), nonce, key) {
                        Ok(plaintext) if plaintext == message => {
                            // Determinism: the same inputs must re-encrypt.
                            assert_eq!(
                                chacha::encrypt(&message, Some(&aad), nonce, key).unwrap(),
                                sealed,
                                "{case} did not re-encrypt to the vector ciphertext"
                            );
                            Accepted::Yes
                        }
                        Ok(_) => {
                            mismatched += 1;
                            Accepted::Mismatch
                        }
                        Err(_) => Accepted::No,
                    }
                }
                _ => {
                    // Key or nonce length outside the primitive's contract.
                    rejected_by_length += 1;
                    Accepted::No
                }
            };
            judge(test.text("result"), accepted, &case, &mut acceptable);
        }
    }

    println!(
        "chacha20_poly1305: executed {executed}, acceptable {acceptable}, \
         rejected by length {rejected_by_length}, mismatched {mismatched}, \
         skipped {skipped}"
    );
    assert_eq!(executed, 325);
    // Pinned because `crypto-report.md` publishes this number as gate-13
    // evidence: 9 `invalid` cases carry a nonce that is not 96 bits.
    assert_eq!(rejected_by_length, 9);
    assert_eq!(mismatched, 0, "a ciphertext authenticated to a wrong plaintext");
    assert_eq!(skipped, 0);
}

/// Wycheproof `xchacha20_poly1305_test.json`.
///
/// Every 24-byte-nonce case is opened through the exported `crypto::open`;
/// the nine malformed-nonce cases cannot be expressed in that frame format and
/// are rejected by construction, which is recorded separately.
#[test]
fn wycheproof_xchacha20_poly1305() {
    sodium();
    let suite = wycheproof(
        "xchacha20_poly1305_test.json",
        XCHACHA_VECTORS,
        XCHACHA_DIGEST,
    );
    let mut executed = 0usize;
    let mut acceptable = 0usize;
    let mut through_public_api = 0usize;
    let mut rejected_by_length = 0usize;
    let mut mismatched = 0usize;
    let mut skipped = 0usize;

    for group in suite.items("testGroups") {
        if group.number("keySize") != 256 || group.number("tagSize") != 128 {
            skipped += group.items("tests").len();
            continue;
        }
        for test in group.items("tests") {
            executed += 1;
            let case = format!("xchacha20_poly1305 tcId {}", test.number("tcId"));
            let key = hex(test.text("key"));
            let nonce = hex(test.text("iv"));
            let aad = hex(test.text_or_empty("aad"));
            let message = hex(test.text_or_empty("msg"));
            let mut sealed = hex(test.text_or_empty("ct"));
            sealed.extend_from_slice(&hex(test.text("tag")));

            let accepted = if nonce.len() == xchacha::NPUBBYTES {
                through_public_api += 1;
                let mut frame = nonce.clone();
                frame.extend_from_slice(&sealed);
                match crypto::open(&key, &frame, &aad) {
                    Ok(Some(plaintext)) if plaintext == message => {
                        let sodium_key = xchacha::Key::from_bytes(&key).unwrap();
                        let sodium_nonce = xchacha::Nonce::try_from_slice(&nonce).unwrap();
                        assert_eq!(
                            xchacha::encrypt(&message, Some(&aad), &sodium_nonce, &sodium_key)
                                .unwrap(),
                            sealed,
                            "{case} did not re-encrypt to the vector ciphertext"
                        );
                        Accepted::Yes
                    }
                    Ok(Some(_)) => {
                        mismatched += 1;
                        Accepted::Mismatch
                    }
                    Ok(None) | Err(_) => Accepted::No,
                }
            } else {
                // `crypto::seal`/`crypto::open` fix the nonce at 24 bytes, so a
                // malformed-nonce frame cannot even be built.
                rejected_by_length += 1;
                Accepted::No
            };
            judge(test.text("result"), accepted, &case, &mut acceptable);
        }
    }

    println!(
        "xchacha20_poly1305: executed {executed}, through crypto::open {through_public_api}, \
         acceptable {acceptable}, rejected by length {rejected_by_length}, \
         mismatched {mismatched}, skipped {skipped}"
    );
    assert_eq!(executed, 315);
    assert_eq!(rejected_by_length, 9);
    assert_eq!(mismatched, 0, "a frame authenticated to a wrong plaintext");
    assert_eq!(skipped, 0);
}

/// Wycheproof `x25519_test.json`.
///
/// `nomessages::crypto` exposes no X25519 entry point, so the vectors run against
/// `crypto_scalarmult_curve25519` of the bundled libsodium build.
///
/// Scope, stated exactly: **no product code path uses that implementation
/// today.** `grep -rn 'scalarmult\|curve25519' native/src/` returns nothing;
/// MLS gets its DH from `openmls_rust_crypto` (x25519-dalek) for the
/// `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519` ciphersuite, and Arti gets it
/// from `tor-llcrypto` (curve25519-dalek). This suite therefore pins the
/// low-order, twist and non-canonical behaviour of libsodium only; equivalent
/// vectors for the two providers the product does use are still missing and are
/// recorded as such in `docs/release-checklist.md`.
#[test]
fn wycheproof_x25519() {
    sodium();
    let suite = wycheproof("x25519_test.json", X25519_VECTORS, X25519_DIGEST);
    let mut executed = 0usize;
    let mut acceptable = 0usize;
    let mut zero_shared = 0usize;

    for group in suite.items("testGroups") {
        assert_eq!(group.text("curve"), "curve25519");
        for test in group.items("tests") {
            executed += 1;
            let case = format!("x25519 tcId {}", test.number("tcId"));
            let public = hex(test.text("public"));
            let private = hex(test.text("private"));
            let shared = hex(test.text("shared"));
            let result = test.text("result");
            let all_zero = shared.iter().all(|byte| *byte == 0);

            match curve25519::scalarmult(&private, &public) {
                Ok(computed) => {
                    assert!(!all_zero, "{case} accepted an all-zero shared secret");
                    assert_eq!(computed.as_slice(), shared.as_slice(), "{case} mismatched");
                    assert_ne!(result, "invalid", "{case} should have been rejected");
                }
                Err(_) => {
                    // libsodium refuses low-order points, which Wycheproof marks
                    // `acceptable` with an all-zero shared secret.
                    assert!(all_zero, "{case} was rejected with a non-zero secret");
                    assert_ne!(result, "valid", "{case} should have been accepted");
                    zero_shared += 1;
                }
            }
            if result == "acceptable" {
                acceptable += 1;
            }
        }
    }

    println!(
        "x25519: executed {executed}, acceptable {acceptable}, \
         low-order rejections {zero_shared}"
    );
    assert_eq!(executed, 518);
    assert_eq!(zero_shared, 31);
}

/// Wycheproof `ed25519_test.json`, verification direction through
/// `crypto::verify`. Wycheproof publishes no Ed25519 signing vectors in this
/// file, so the signing direction stays covered by RFC 8032 vector 1 in
/// `crypto.rs`.
#[test]
fn wycheproof_ed25519_verify() {
    sodium();
    let suite = wycheproof("ed25519_test.json", ED25519_VECTORS, ED25519_DIGEST);
    let mut executed = 0usize;
    let mut acceptable = 0usize;
    let mut rejected_by_length = 0usize;

    for group in suite.items("testGroups") {
        let key = group.field("publicKey").expect("group public key");
        assert_eq!(key.text("curve"), "edwards25519");
        let public = hex(key.text("pk"));
        for test in group.items("tests") {
            executed += 1;
            let case = format!("ed25519 tcId {}", test.number("tcId"));
            let message = hex(test.text_or_empty("msg"));
            let signature = hex(test.text("sig"));

            // `crypto::verify` returns `InvalidArgument` for three distinct
            // reasons: an oversized message, a public key that is not 32 bytes
            // and a signature that is not 64 bytes. Only the last one is what
            // `rejected_by_length` claims to count, so the expected cause is
            // decided here and any other error is a failure rather than a
            // silent increment of the wrong counter.
            let bad_signature_length = signature.len() != 64;
            let accepted = match crypto::verify(&public, &message, &signature) {
                Ok(true) => Accepted::Yes,
                Ok(false) => Accepted::No,
                Err(_) if bad_signature_length => {
                    // Such a signature never reaches libsodium; the bridge
                    // rejects it as an invalid argument.
                    rejected_by_length += 1;
                    Accepted::No
                }
                Err(error) => panic!("{case} errored for a non-length reason: {error:?}"),
            };
            assert!(
                !bad_signature_length || accepted == Accepted::No,
                "{case} has a {}-byte signature and should not have been verified",
                signature.len()
            );
            judge(test.text("result"), accepted, &case, &mut acceptable);
        }
    }

    println!(
        "ed25519: executed {executed}, acceptable {acceptable}, \
         rejected by length {rejected_by_length}"
    );
    assert_eq!(executed, 151);
    // Pinned because `crypto-report.md` publishes this number as gate-13
    // evidence: 12 `invalid` cases carry a signature that is not 64 bytes.
    assert_eq!(rejected_by_length, 12);
}

/// Sanity check on the embedded corpus: all four files load, keep their digest
/// and together provide the case count reported in `crypto-report.md`.
#[test]
fn embedded_vector_corpus_is_intact() {
    sodium();
    let total: usize = [
        wycheproof(
            "chacha20_poly1305_test.json",
            CHACHA_VECTORS,
            CHACHA_DIGEST,
        ),
        wycheproof(
            "xchacha20_poly1305_test.json",
            XCHACHA_VECTORS,
            XCHACHA_DIGEST,
        ),
        wycheproof("x25519_test.json", X25519_VECTORS, X25519_DIGEST),
        wycheproof("ed25519_test.json", ED25519_VECTORS, ED25519_DIGEST),
    ]
    .iter()
    .map(|suite| suite.number("numberOfTests") as usize)
    .sum();
    assert_eq!(total, 1309);
}
