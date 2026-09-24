package dev.mx3.nomessages.core.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Acceptance rules of the relaxed vault password policy (T4.10): 12..128 NFKC characters, printable
 * ASCII plus Unicode letters, no control characters, no edge whitespace, and `estimateStrength`
 * score >= 3. Control characters are built with [Int.toChar] rather than written as escapes so the
 * test file stays plain ASCII and no literal password material is ever pasted into a log.
 */
class PasswordPolicyTest {
    private val policy = PasswordPolicy()

    private fun rejection(password: String): String = rejection(password.toCharArray())

    private fun rejection(password: CharArray): String =
        assertThrows(IllegalArgumentException::class.java) { policy.validate(password) }.message!!

    private fun pairRejection(real: String, panic: String): String =
        assertThrows(IllegalArgumentException::class.java) {
            policy.validatePair(real.toCharArray(), panic.toCharArray())
        }.message!!

    @Test fun rejectsBelowTwelveCharactersAndAcceptsAStrongTwelveCharacterPassword() {
        // 11 characters: strong per character, still under the floor, so the floor is what fires.
        assertEquals(LENGTH_MESSAGE, rejection("Q7vfN2rT8bL"))
        assertEquals(LENGTH_MESSAGE, rejection("Q7vfN2rT"))
        assertEquals("Password exceeds maximum length", rejection(CharArray(MAXIMUM + 1) { 'a' }))
        policy.validate("Q7vfN2rT8bLp".toCharArray())
    }

    @Test fun rejectsTwelveToFifteenCharacterPasswordsThatDoNotClearTheStrengthBar() {
        // Long enough for the new floor, but priced far below 60 bits by the estimator.
        assertEquals(WEAK_MESSAGE, rejection("amordeus2024"))
        assertEquals(WEAK_MESSAGE, rejection("Senha123456!"))
        assertEquals(WEAK_MESSAGE, rejection("Flamengo2019"))
        assertEquals(WEAK_MESSAGE, rejection("Verao2024Praia!"))
    }

    @Test fun dictionaryAndKeyboardWalkPasswordsAreStillRejectedAboveTheLengthFloor() {
        // The vectors VaultTest also asserts, kept here so they run without the native library.
        assertEquals(WEAK_MESSAGE, rejection("p4ssw0rdp4ssw0rd"))
        assertEquals(WEAK_MESSAGE, rejection("qwertyuiopasdfghjkl"))
        assertEquals(WEAK_MESSAGE, rejection("aaaaaaaaaaaaaaaa"))
    }

    @Test fun composedAndDecomposedFormsOfTheSamePasswordAreOneSecret() {
        val composed = "Q7vfN2rT8bLp4WzK6sH" + 0x00E9.toChar()
        val decomposed = "Q7vfN2rT8bLp4WzK6sHe" + 0x0301.toChar()
        policy.validate(composed.toCharArray())
        policy.validate(decomposed.toCharArray())
        assertEquals("Passwords must be distinct", pairRejection(composed, decomposed))
    }

    @Test fun acceptsSymbolsInternalSpacesAndNonLatinLetters() {
        policy.validate("Q7vf!N2rT@8bL".toCharArray())
        policy.validate("Q7vfN2rT8bLp4WzK!".toCharArray()) // rejected by the old alphanumeric rule
        policy.validate("correto cavalo bateria grampo".toCharArray())
        policy.validate("Gato Preto Anda Rapido".toCharArray())
        // 14 Cyrillic letters: the policy allows any Unicode letter, not just printable ASCII.
        policy.validate(CharArray(14) { (0x0430 + (it * 5 + 3) % 32).toChar() })
    }

    @Test fun rejectsControlCharactersAndLeadingOrTrailingWhitespace() {
        assertEquals(CONTROL_MESSAGE, rejection("Q7vfN2rT8bLp" + 7.toChar() + "WzK"))
        assertEquals(CONTROL_MESSAGE, rejection("Q7vfN2rT8bLp" + 9.toChar() + "WzK"))
        assertEquals(CONTROL_MESSAGE, rejection("Q7vfN2rT8bLp" + 10.toChar() + "WzK"))
        assertEquals(WHITESPACE_MESSAGE, rejection(" Q7vfN2rT8bLp4WzK"))
        assertEquals(WHITESPACE_MESSAGE, rejection("Q7vfN2rT8bLp4WzK "))
    }

    @Test fun existingSixteenPlusAlphanumericPasswordsStayValid() {
        // Backward compatibility: every password the previous policy could accept still passes.
        policy.validate("Q7vfN2rT8bLp4WzK6sHx".toCharArray())
        policy.validate("M9kP3vX7rB2nQ5sT8wLc".toCharArray())
        policy.validate("A6mR9xH2kV5zL8pQ3nTc".toCharArray())
        policy.validate("Q7vfN2rT8bLp4WzK".toCharArray())
    }

    @Test fun nfkcFoldsCompatibilityFormsSoAnotherKeyboardDerivesTheSameKey() {
        // Full-width Latin is what several CJK IMEs emit; NFC would keep it distinct from ASCII and
        // derive a different Argon2id key on the second device, breaking the export promise.
        val ascii = "Q7vfN2rT8bLp".toCharArray()
        val fullWidth = CharArray(ascii.size) { (ascii[it].code + 0xFEE0).toChar() }
        assertArrayEquals(normalizePassword(ascii), normalizePassword(fullWidth))
        policy.validate(fullWidth)
    }

    @Test fun identicalPasswordsAreRejectedAsNotDistinct() {
        assertEquals("Passwords must be distinct", pairRejection(REAL, REAL))
    }

    @Test fun trivialVariationsOfTheRealPasswordAreRejectedAsTooSimilar() {
        assertEquals(SIMILAR_MESSAGE, pairRejection(REAL, "Q7vfN2rT8bLp4WzK6sHy")) // 1 substitution
        assertEquals(SIMILAR_MESSAGE, pairRejection(REAL, "Q7vfN2rT8bLp4WzK6sJy")) // 2 substitutions
        // Edit distance 7, but the real password is a prefix of it: the substring rule catches it.
        assertEquals(SIMILAR_MESSAGE, pairRejection(REAL, "Q7vfN2rT8bLp4WzK6sHxZ9mR4tK"))
    }

    @Test fun twoStrongAndUnrelatedPasswordsAreAccepted() {
        policy.validatePair(REAL.toCharArray(), "M9kP3vX7rB2nQ5sT8wLc".toCharArray())
        policy.validatePair(
            "correto cavalo bateria grampo".toCharArray(),
            "Gato Preto Anda Rapido".toCharArray(),
        )
    }

    private companion object {
        const val REAL = "Q7vfN2rT8bLp4WzK6sHx"
        const val MAXIMUM = 128
        const val LENGTH_MESSAGE = "Password must contain 12 to 128 characters"
        const val CONTROL_MESSAGE = "Password must not contain control characters"
        const val WHITESPACE_MESSAGE = "Password must not start or end with whitespace"
        const val WEAK_MESSAGE = "Password is too predictable"
        const val SIMILAR_MESSAGE = "Panic password is too similar to the real password"
    }
}
