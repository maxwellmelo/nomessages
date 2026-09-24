package dev.mx3.nomessages.core.vault

import java.nio.CharBuffer
import java.security.MessageDigest
import java.text.Normalizer

/** Shortest accepted password, counted in NFKC-normalized characters. */
internal const val MINIMUM_PASSWORD_LENGTH = 12

/** Longest accepted password. Bounds every buffer this file and `PasswordStrength.kt` allocate. */
internal const val MAXIMUM_PASSWORD_LENGTH = 128

/**
 * Acceptance floor on the [estimateStrength] ladder. Score 3 starts at roughly 60 bits of estimated
 * guessing entropy, which a 12-character password only reaches when it has no exploitable pattern,
 * and which any 16-character alphanumeric password (the previous hard minimum) clears comfortably.
 */
internal const val MINIMUM_ACCEPTABLE_SCORE = 3

/**
 * Real and panic passwords within this edit distance are a trivial variation of one another, not
 * two independent secrets: an adversary who extracts one under pressure gets the other for free.
 */
private const val MAXIMUM_PANIC_EDIT_DISTANCE = 2

/**
 * Vault password rules.
 *
 * ### Why the previous policy was alphanumeric-only, and why it is being relaxed
 *
 * **Inferred rationale, not a recovered decision record.** Nothing in the git history or in the
 * documents explains the original `16..128` alphanumeric rule; the two commits that ever touched
 * this file are bulk/rename commits. The reconstruction below is drawn from the surrounding code
 * and from `SPEC.md`, and is recorded here so the relaxation can be argued about on the merits:
 *
 * 1. [normalizePassword] feeds Argon2id directly, and `SPEC.md` promises that an exported vault
 *    opens on another phone with the same password. Unicode normalization is only *guaranteed*
 *    byte-stable across devices, keyboards and IMEs for a restricted character set. Letters and
 *    digits normalize identically almost everywhere; many symbols - especially non-ASCII ones -
 *    have layout/IME-dependent input paths and compatibility decompositions, so the "same" password
 *    typed on a different device could have derived a different key and bricked the export promise.
 * 2. A 16-character minimum over a 62-symbol alphabet is ~95 bits, which trivially cleared the old
 *    zxcvbn score-4 gate without anyone needing a nuanced per-password entropy model.
 *
 * Both concerns are now addressed rather than avoided. (1) is handled by normalizing with **NFKC**
 * instead of NFC (see [normalizePassword]), which folds the compatibility forms that differ between
 * keyboards, so symbols can be allowed without endangering cross-device derivation. (2) is handled
 * by [estimateStrength], which prices each password instead of assuming length implies entropy, so
 * the length floor could drop to 12 without accepting `Senha123456!`.
 */
class PasswordPolicy {
    /**
     * Accepts a password when, after NFKC normalization, it is [MINIMUM_PASSWORD_LENGTH] to
     * [MAXIMUM_PASSWORD_LENGTH] characters long, contains only printable ASCII (`0x20..0x7E`) or
     * Unicode letters, contains no control character, neither starts nor ends with whitespace, and
     * scores at least [MINIMUM_ACCEPTABLE_SCORE] on [estimateStrength].
     *
     * Internal whitespace is deliberately allowed so that passphrases work; leading and trailing
     * whitespace is rejected because it is invisible in the field, trivially lost to a copy/paste or
     * to an IME, and would silently change the derived key.
     *
     * @throws IllegalArgumentException with a message that never contains password material.
     */
    fun validate(password: CharArray) {
        require(password.size <= MAXIMUM_PASSWORD_LENGTH) { "Password exceeds maximum length" }
        val normalized = normalizeChars(password)
        try {
            require(normalized.size in MINIMUM_PASSWORD_LENGTH..MAXIMUM_PASSWORD_LENGTH) {
                "Password must contain $MINIMUM_PASSWORD_LENGTH to $MAXIMUM_PASSWORD_LENGTH characters"
            }
            require(normalized.none { Character.isISOControl(it) }) {
                "Password must not contain control characters"
            }
            require(normalized.all { isPasswordCharacter(it) }) {
                "Password contains an unsupported character"
            }
            require(!normalized.first().isWhitespace() && !normalized.last().isWhitespace()) {
                "Password must not start or end with whitespace"
            }
            require(estimateStrength(normalized).score >= MINIMUM_ACCEPTABLE_SCORE) {
                "Password is too predictable"
            }
        } finally {
            normalized.fill('\u0000')
        }
    }

    /**
     * Validates both vault passwords and requires them to be genuinely different secrets.
     *
     * Three rejections, with distinct messages so the UI layer can show distinct strings:
     * - identical after normalization -> `"Passwords must be distinct"`;
     * - one contained in the other, or within [MAXIMUM_PANIC_EDIT_DISTANCE] edits of the other ->
     *   `"Panic password is too similar to the real password"`.
     *
     * The equality test stays a constant-time [MessageDigest.isEqual]. The similarity tests are
     * inherently data-dependent, which is acceptable here: both inputs belong to the same user, this
     * runs once at setup on the local device, and no attacker is on the other end of an oracle.
     */
    fun validatePair(real: CharArray, panic: CharArray) {
        validate(real)
        validate(panic)
        val first = normalizePassword(real)
        val second = normalizePassword(panic)
        try {
            require(!MessageDigest.isEqual(first, second)) { "Passwords must be distinct" }
            require(!containsSlice(first, second) && !containsSlice(second, first)) {
                "Panic password is too similar to the real password"
            }
            require(editDistance(first, second) > MAXIMUM_PANIC_EDIT_DISTANCE) {
                "Panic password is too similar to the real password"
            }
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }
}

/** Printable ASCII, plus any Unicode letter so non-Latin scripts and diacritics are usable. */
private fun isPasswordCharacter(character: Char): Boolean =
    character.code in 0x20..0x7E || Character.isLetter(character)

/** Classic O(n*m) Levenshtein distance over two rolling rows. No dependency, no recursion. */
private fun editDistance(first: ByteArray, second: ByteArray): Int {
    if (first.isEmpty()) return second.size
    if (second.isEmpty()) return first.size
    var previous = IntArray(second.size + 1) { it }
    var current = IntArray(second.size + 1)
    for (row in 1..first.size) {
        current[0] = row
        for (column in 1..second.size) {
            val substitution = previous[column - 1] + if (first[row - 1] == second[column - 1]) 0 else 1
            current[column] = minOf(substitution, previous[column] + 1, current[column - 1] + 1)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[second.size]
}

/** True when [needle] occurs contiguously inside [haystack]. */
private fun containsSlice(haystack: ByteArray, needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > haystack.size) return false
    for (offset in 0..haystack.size - needle.size) {
        var matched = 0
        while (matched < needle.size && haystack[offset + matched] == needle[matched]) matched++
        if (matched == needle.size) return true
    }
    return false
}

/**
 * The exact bytes handed to Argon2id, normalized with **NFKC** (compatibility composition).
 *
 * NFC alone is not enough. `SPEC.md` promises that copying `vault/` to another phone and typing the
 * same password opens it, so the derived key has to be a function of what the user believes they
 * typed, not of how a particular keyboard or IME happened to encode it. Different input methods emit
 * visually identical characters as different Unicode compatibility forms - full-width versus
 * half-width digits and Latin letters on CJK IMEs, U+FB01 (the fi-ligature) versus plain `fi`,
 * U+2116 (the numero sign) versus `No`, non-breaking versus ordinary space, superscript digits, the
 * several distinct code points that render as a hyphen. NFC preserves all of those distinctions and would derive a different key on
 * the other device; NFKC folds them to one canonical form, so the same password derives the same
 * key. That guarantee is what makes it safe to accept symbols at all.
 *
 * The change is backward compatible: NFC and NFKC are identical on plain ASCII, which is the entire
 * existing password corpus, every current test vector, and every password the previous
 * alphanumeric-only policy could ever have accepted. No existing vault changes its derived key.
 *
 * `Normalizer` necessarily produces an immutable JVM `String`; there is no managed-runtime
 * zeroization guarantee for it.
 */
internal fun normalizePassword(password: CharArray): ByteArray {
    require(password.size <= MAXIMUM_PASSWORD_LENGTH) { "Password exceeds maximum length" }
    return Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8)
}

/**
 * The character-level twin of [normalizePassword], used by validation and by [estimateStrength] so
 * that the rules, the strength meter and the KDF all judge exactly the same characters. Callers own
 * the returned array and must zero-fill it; the same immutable-`String` caveat applies.
 */
internal fun normalizeChars(password: CharArray): CharArray =
    Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFKC).toCharArray()
