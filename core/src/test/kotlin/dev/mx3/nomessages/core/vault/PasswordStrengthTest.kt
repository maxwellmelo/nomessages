package dev.mx3.nomessages.core.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the offline strength estimator that replaced the zxcvbn score gate. The assertions
 * are about ordering and thresholds, never about exact bit counts, so the constants inside
 * `PasswordStrength.kt` can be retuned without rewriting the suite.
 */
class PasswordStrengthTest {
    private fun score(password: String) = estimateStrength(password.toCharArray()).score

    private fun feedback(password: String) = estimateStrength(password.toCharArray()).feedback

    @Test fun weakMediumAndStrongVectorsAreOrderedAndLandInTheirBands() {
        // Weak: the common Portuguese word "senha" plus an ascending digit run, capitalized with a
        // trailing symbol - three independent patterns, so it must stay far below acceptance.
        val weak = score(WEAK)
        // Medium: deliberately pattern-free but only ten characters. Over the 62-symbol alphanumeric
        // alphabet that is ~59.5 bits, half a bit under the 60-bit acceptance floor. It is the shape
        // of a password a user believes is fine, so it is the interesting middle of the ladder.
        val medium = score(MEDIUM)
        // Strong: the classic diceware shape, four ordinary words. The passphrase branch prices it
        // by word choice instead of penalizing each token as a dictionary hit.
        val strong = score(STRONG)
        assertTrue(weak < MINIMUM_ACCEPTABLE_SCORE)
        assertTrue(weak <= medium)
        assertTrue(medium <= strong)
        assertTrue(medium in 1..3)
        assertEquals(4, strong)
    }

    @Test fun existingTwentyCharacterAlphanumericPasswordsClearTheAcceptanceFloor() {
        assertTrue(score("Q7vfN2rT8bLp4WzK6sHx") >= MINIMUM_ACCEPTABLE_SCORE)
        assertTrue(score("M9kP3vX7rB2nQ5sT8wLc") >= MINIMUM_ACCEPTABLE_SCORE)
        assertTrue(score("A6mR9xH2kV5zL8pQ3nTc") >= MINIMUM_ACCEPTABLE_SCORE)
    }

    @Test fun bitsEstimateIsMonotonicWithTheScoreLadder() {
        val ladder = listOf(WEAK, MEDIUM, STRONG).map { estimateStrength(it.toCharArray()) }
        for (index in 1 until ladder.size) {
            assertTrue(ladder[index - 1].bitsEstimate <= ladder[index].bitsEstimate)
            assertTrue(ladder[index - 1].score <= ladder[index].score)
        }
    }

    @Test fun patternDetectorsEmitTheirOwnFeedbackKeys() {
        assertTrue(feedback(WEAK).contains(PasswordFeedback.COMMON_WORD))
        assertTrue(feedback(WEAK).contains(PasswordFeedback.SEQUENTIAL_CHARS))
        assertTrue(feedback(WEAK).contains(PasswordFeedback.TRIVIAL_SUFFIX_PATTERN))
        assertTrue(feedback("amordeus2024").contains(PasswordFeedback.DATE_OR_YEAR))
        assertTrue(feedback("aaaaaaaaaaaaaaaa").contains(PasswordFeedback.REPEATED_CHARS))
        assertTrue(feedback("qwertyuiopasdfghjkl").contains(PasswordFeedback.SEQUENTIAL_CHARS))
        assertTrue(feedback("Q7vfN2rT8bL").contains(PasswordFeedback.TOO_SHORT))
        assertTrue(feedback("senhasenha12").contains(PasswordFeedback.ADD_VARIETY))
        assertEquals(listOf(PasswordFeedback.LOOKS_STRONG), feedback(STRONG))
        assertEquals(listOf(PasswordFeedback.LOOKS_STRONG), feedback("Q7vfN2rT8bLp4WzK6sHx"))
    }

    @Test fun everyEmittedKeyBelongsToTheDocumentedSet() {
        val known = setOf(
            PasswordFeedback.TOO_SHORT, PasswordFeedback.SEQUENTIAL_CHARS,
            PasswordFeedback.REPEATED_CHARS, PasswordFeedback.COMMON_WORD,
            PasswordFeedback.DATE_OR_YEAR, PasswordFeedback.TRIVIAL_SUFFIX_PATTERN,
            PasswordFeedback.ADD_LENGTH, PasswordFeedback.ADD_VARIETY, PasswordFeedback.LOOKS_STRONG,
        )
        val samples = listOf(
            "", "a", WEAK, MEDIUM, STRONG, "p4ssw0rdp4ssw0rd", "qwertyuiopasdfghjkl",
            "Q7vfN2rT8bLp4WzK6sHx", "Verao2024Praia!", "senha senha senha senha", "red dog cat",
        )
        for (sample in samples) assertTrue(known.containsAll(feedback(sample)))
    }

    @Test fun passphraseBranchIsNotABypassForRepeatedOrCommonWords() {
        assertTrue(score("senha senha senha senha") < MINIMUM_ACCEPTABLE_SCORE)
        assertTrue(score("senha brasil futebol amor") < MINIMUM_ACCEPTABLE_SCORE)
        assertTrue(score("qwertyuiop asdfghjkl zxcvbnm") < MINIMUM_ACCEPTABLE_SCORE)
        assertTrue(score("red dog cat") < MINIMUM_ACCEPTABLE_SCORE)
        // Hyphens join a passphrase just like spaces do.
        assertEquals(4, score("correto-cavalo-bateria-grampo"))
    }

    @Test fun strengthIsJudgedAgainstTheNfkcFormThatIsActuallyDerived() {
        val ascii = "Q7vfN2rT8bLp"
        val fullWidth = String(CharArray(ascii.length) { (ascii[it].code + 0xFEE0).toChar() })
        assertEquals(
            estimateStrength(ascii.toCharArray()).bitsEstimate,
            estimateStrength(fullWidth.toCharArray()).bitsEstimate,
            0.0,
        )
    }

    @Test fun estimationNeverMutatesOrLeaksTheCallersBuffer() {
        val password = STRONG.toCharArray()
        val copy = password.copyOf()
        val result = estimateStrength(password)
        assertTrue(password.contentEquals(copy))
        assertTrue(result.feedback.none { key -> STRONG.contains(key, ignoreCase = true) })
    }

    private companion object {
        const val WEAK = "Senha123456!"
        const val MEDIUM = "Kp3rVn8qLz"
        const val STRONG = "correto cavalo bateria grampo"
    }
}
