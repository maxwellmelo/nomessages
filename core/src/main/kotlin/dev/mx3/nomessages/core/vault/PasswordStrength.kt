package dev.mx3.nomessages.core.vault

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Offline estimate of how expensive a password is to guess.
 *
 * @param score monotonic ladder in `0..4` derived from [bitsEstimate]; [MINIMUM_ACCEPTABLE_SCORE]
 *   is the vault acceptance floor enforced by [PasswordPolicy.validate].
 * @param bitsEstimate rough guessing entropy in bits. It is an engineering estimate for a strength
 *   meter and an acceptance floor, never a cryptographic proof of work factor.
 * @param feedback stable `UPPER_SNAKE_CASE` keys from [PasswordFeedback], in a deterministic order.
 *   Core has no Android resources, so the UI layer maps every key to a localized string itself.
 */
data class PasswordStrength(val score: Int, val bitsEstimate: Double, val feedback: List<String>)

/**
 * The complete, closed set of keys [estimateStrength] can emit. The UI layer must have a string
 * resource for each one; an unknown key can only appear if this object grows, so a `when` over
 * these constants with an `else` branch is enough to stay forward compatible.
 */
object PasswordFeedback {
    /** Shorter than [MINIMUM_PASSWORD_LENGTH] characters after NFKC normalization. */
    const val TOO_SHORT: String = "TOO_SHORT"

    /** Ascending/descending run (`abcd`, `4321`) or a keyboard walk (`qwerty`, `asdfgh`). */
    const val SEQUENTIAL_CHARS: String = "SEQUENTIAL_CHARS"

    /** Repeated or alternating run (`aaaa`, `abab`, `abcabc`), or a repeated passphrase word. */
    const val REPEATED_CHARS: String = "REPEATED_CHARS"

    /** A term from the embedded PT/EN common-password list appears in the password. */
    const val COMMON_WORD: String = "COMMON_WORD"

    /** A 19xx/20xx year, or a 6/8 digit run that parses as a calendar date. */
    const val DATE_OR_YEAR: String = "DATE_OR_YEAR"

    /** Capitalized word followed only by digits/symbols, e.g. `Palavra1!`. */
    const val TRIVIAL_SUFFIX_PATTERN: String = "TRIVIAL_SUFFIX_PATTERN"

    /** Actionable advice: the cheapest fix for this password is more characters. */
    const val ADD_LENGTH: String = "ADD_LENGTH"

    /** Actionable advice: the password draws on two or fewer character classes. */
    const val ADD_VARIETY: String = "ADD_VARIETY"

    /** Positive, informational: acceptance floor cleared and nothing to warn about. */
    const val LOOKS_STRONG: String = "LOOKS_STRONG"
}

// --- Character-class alphabet sizes -------------------------------------------------------------
// The base estimate is `length * log2(alphabetSize)`, where alphabetSize counts only the classes the
// password actually uses. This is the textbook "brute force over the observed alphabet" model and it
// is an upper bound; the pattern penalties below pull it back down towards a realistic attack cost.
private const val LOWERCASE_ALPHABET = 26
private const val UPPERCASE_ALPHABET = 26
private const val DIGIT_ALPHABET = 10
private const val ASCII_SYMBOL_ALPHABET = 33 // 0x20-0x2F, 0x3A-0x40, 0x5B-0x60, 0x7B-0x7E

// Non-ASCII letters (diacritics, Cyrillic, Greek, CJK...). A real attacker who knows the script
// faces far more than 60 options, and one who does not faces far fewer relevant ones; 60 is a
// deliberately bounded, generous-but-not-absurd constant so that a single accented letter cannot by
// itself carry a weak password over the acceptance floor.
private const val OTHER_LETTER_ALPHABET = 60

// --- Pattern costs, in bits ---------------------------------------------------------------------
// A detected pattern covering k characters does not cost the attacker `k * bitsPerChar`; it costs
// only what it takes to describe the pattern. The estimator therefore subtracts
// `k * bitsPerChar - patternBits` for every matched region (never below zero).
private const val SEQUENCE_BITS = 8.0 // start character (~6.5) + direction + length
private const val KEYBOARD_BITS = 8.0 // which row/column walk, where it starts, how long
private const val REPEAT_FIXED_BITS = 4.0 // repetition count; the period itself is paid for
private const val YEAR_BITS = 7.6 // ~200 plausible years
private const val DATE_BITS = 15.0 // ~36500 plausible dates, times a couple of layouts
private const val TRIVIAL_SUFFIX_PENALTY_BITS = 6.0 // "Word" + trailing digits/symbols is a habit

private const val SEQUENCE_MINIMUM_RUN = 4
private const val KEYBOARD_MINIMUM_RUN = 4
private const val REPEAT_MINIMUM_RUN = 4

// Period is capped at 4 on purpose: longer "repetitions" such as `p4ssw0rdp4ssw0rd` are better
// explained (and more cheaply priced) by two dictionary matches than by one period-8 repeat.
private const val REPEAT_MAXIMUM_PERIOD = 4

// --- Passphrase model ---------------------------------------------------------------------------
private const val PASSPHRASE_SEPARATORS = " -_"
private const val PASSPHRASE_MINIMUM_TOKENS = 3
private const val PASSPHRASE_MINIMUM_TOKEN_LENGTH = 3

// The attacker also has to guess that this *is* a passphrase, how many words it has and which
// separator joins them. Two bits is a deliberately conservative credit for that structural choice.
private const val PASSPHRASE_STRUCTURE_BITS = 2.0

// A token identical to an earlier one adds only the choice of which earlier token to copy.
private const val REPEATED_TOKEN_BITS = 2.0

// 2^17 = 131072. An attacker who does not know which language, list or inflection set the user drew
// from has to span at least a full PT+EN lexicon with inflections and proper nouns. This is the
// diceware assumption made explicit; it is intentionally *not* the size of the small blocklist
// below, because that list describes what we consider bad, not what an attacker has to search.
private const val ASSUMED_VOCABULARY_BITS = 17.0

// --- Score ladder, in bits ----------------------------------------------------------------------
// 0: < 28   trivially crackable offline
// 1: 28..39 weak
// 2: 40..59 fair, still below the vault acceptance floor
// 3: 60..67 good; 60 bits is the acceptance floor (MINIMUM_ACCEPTABLE_SCORE)
// 4: >= 68  strong. 68 bits is exactly a four-word passphrase over the 2^17 assumed vocabulary
//           (4 * 17), which is the canonical "correct horse battery staple" shape, so that pattern
//           lands on 4 rather than being punished for being made of ordinary words.
private const val SCORE_1_BITS = 28.0
private const val SCORE_2_BITS = 40.0
private const val SCORE_3_BITS = 60.0
private const val SCORE_4_BITS = 68.0

private const val VARIETY_ADVICE_CLASSES = 2
private const val LENGTH_ADVICE_CHARS = 16

private val LN2 = ln(2.0)
private val LOG2_LATIN_ALPHABET = log2(LOWERCASE_ALPHABET.toDouble())

/**
 * Digit/symbol shapes users substitute for letters. Applied only when matching the embedded term
 * list, on a separate folded copy, so that `p4ssw0rd` matches `password` while digit-run and
 * sequence detection still see the original digits.
 */
private val LEET_FOLDING = mapOf(
    '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's',
    '7' to 't', '8' to 'b', '@' to 'a', '$' to 's', '!' to 'i', '|' to 'l',
)

private val KEYBOARD_ROWS = listOf(
    "qwertyuiop", "asdfghjkl", "zxcvbnm", "qwertzuiop", "azertyuiop",
    "qazwsxedcrfvtgb", "1234567890",
)
private val KEYBOARD_SEQUENCES = KEYBOARD_ROWS.flatMap { listOf(it, it.reversed()) }

/**
 * Common password terms, English and Portuguese, lowercase letters only.
 *
 * Letters only on purpose: matching runs against the leet-folded copy of the password, so the digit
 * and symbol variants (`p4ssw0rd`, `senha123`, `Amor!`) are reached by folding plus the separate
 * digit/date/suffix detectors, and every numeric variant does not need its own entry. Entries are
 * at least four characters so that a random alphanumeric password cannot collide with one by
 * accident. This list is a blocklist of terms *we* consider bad; it is deliberately not used as the
 * attacker's dictionary size in the passphrase model.
 */
private val COMMON_TERMS: List<String> = listOf(
    // English / international
    "password", "passwd", "qwerty", "qwertz", "azerty", "letmein", "dragon", "monkey", "football",
    "baseball", "basketball", "welcome", "admin", "administrator", "master", "iloveyou", "sunshine",
    "princess", "shadow", "superman", "batman", "spiderman", "trustno", "freedom", "whatever",
    "michael", "jennifer", "jordan", "hunter", "ranger", "buster", "soccer", "hockey", "killer",
    "george", "andrew", "charlie", "thomas", "robert", "daniel", "matthew", "joshua", "computer",
    "internet", "samsung", "google", "facebook", "twitter", "secret", "access", "login", "logon",
    "root", "pepper", "cookie", "chicken", "banana", "orange", "purple", "yellow", "silver",
    "golden", "summer", "winter", "spring", "autumn", "flower", "angel", "heaven", "hello", "love",
    "money", "happy", "smile", "peace", "music", "guitar", "ninja", "tiger", "eagle", "falcon",
    "phoenix", "thunder", "storm", "rocket", "galaxy", "matrix", "oracle", "server", "system",
    "network", "security", "private", "secure", "unlock", "forever", "dream", "magic", "mustang",
    "harley", "corvette", "ferrari", "porsche", "yankees", "cowboys", "lakers", "please", "nothing",
    "testing", "abcdef", "asdfgh", "zxcvbn", "qazwsx",
    // Portuguese
    "senha", "brasil", "brasileiro", "brasilia", "futebol", "amor", "familia", "deus", "jesus",
    "flamengo", "corinthians", "palmeiras", "saopaulo", "vasco", "gremio", "internacional",
    "cruzeiro", "santos", "botafogo", "fluminense", "atletico", "mineiro", "bahia", "sport",
    "ceara", "fortaleza", "goias", "parana", "coritiba", "natal", "carnaval", "copacabana",
    "ipanema", "maria", "joao", "jose", "pedro", "paulo", "lucas", "gabriel", "felipe", "rafael",
    "bruno", "carlos", "fernando", "ricardo", "roberto", "marcos", "junior", "neymar", "pele",
    "ronaldinho", "ronaldo", "vitoria", "liberdade", "esperanca", "saudade", "coracao", "amizade",
    "felicidade", "sabado", "domingo", "segunda", "verao", "inverno", "praia", "cerveja",
    "churrasco", "feijoada", "cachorro", "gatinho", "princesa", "meuamor", "teamo", "bebe", "casa",
    "vida", "estrela", "sonho", "beijo", "abraco", "mamae", "papai", "filho", "filha", "irmao",
    "amigo", "amiga", "escola", "trabalho", "dinheiro", "feliz", "alegria", "forca", "guerreiro",
    "campeao", "torcida", "mengo", "timao", "galo", "carioca", "banco", "telefone", "celular",
    "computador", "entrar", "acesso", "usuario", "administrador", "teste", "chave", "cofre",
    "segredo", "bloqueio", "mensagem", "conversa",
).distinct()

private val COMMON_TERM_BITS = log2(COMMON_TERMS.size.toDouble())

private val WARNING_ORDER = listOf(
    PasswordFeedback.TOO_SHORT,
    PasswordFeedback.COMMON_WORD,
    PasswordFeedback.SEQUENTIAL_CHARS,
    PasswordFeedback.REPEATED_CHARS,
    PasswordFeedback.DATE_OR_YEAR,
    PasswordFeedback.TRIVIAL_SUFFIX_PATTERN,
)

/**
 * Estimates how hard [password] is to guess. Pure, offline, allocation-local and free of Android,
 * network and third-party dependencies.
 *
 * The password is NFKC-normalized first, exactly as [normalizePassword] does before handing bytes
 * to Argon2id, so the meter judges the same characters the key is actually derived from. Every
 * mutable buffer allocated here is zero-filled before returning. The single unavoidable residue is
 * the immutable `String` produced by `java.text.Normalizer`, which the JVM gives no way to wipe -
 * the same caveat already documented on [normalizePassword].
 *
 * Input longer than [MAXIMUM_PASSWORD_LENGTH] is analyzed only up to that length. Such a password
 * is rejected by [PasswordPolicy.validate] anyway, and the cap keeps the O(n^2) detectors bounded
 * when a UI calls this on every keystroke.
 */
fun estimateStrength(password: CharArray): PasswordStrength {
    val normalized = normalizeChars(password)
    val analyzed = if (normalized.size > MAXIMUM_PASSWORD_LENGTH) {
        normalized.copyOf(MAXIMUM_PASSWORD_LENGTH)
    } else {
        normalized
    }
    val lowered = CharArray(analyzed.size) { analyzed[it].lowercaseChar() }
    val folded = CharArray(lowered.size) { LEET_FOLDING[lowered[it]] ?: lowered[it] }
    try {
        if (analyzed.isEmpty()) {
            return PasswordStrength(0, 0.0, listOf(PasswordFeedback.TOO_SHORT, PasswordFeedback.ADD_LENGTH))
        }
        val warnings = LinkedHashSet<String>()
        if (analyzed.size < MINIMUM_PASSWORD_LENGTH) warnings += PasswordFeedback.TOO_SHORT
        val classes = characterClasses(analyzed)
        val bits = passphraseBits(analyzed, lowered, folded, warnings)
            ?: characterBits(analyzed, lowered, folded, classes, warnings)
        val score = scoreFor(bits)
        val feedback = WARNING_ORDER.filterTo(mutableListOf()) { warnings.contains(it) }
        if (score < MINIMUM_ACCEPTABLE_SCORE) {
            if (analyzed.size < LENGTH_ADVICE_CHARS) feedback += PasswordFeedback.ADD_LENGTH
            if (classes.count { it } <= VARIETY_ADVICE_CLASSES) feedback += PasswordFeedback.ADD_VARIETY
        }
        if (feedback.isEmpty() && score >= MINIMUM_ACCEPTABLE_SCORE) feedback += PasswordFeedback.LOOKS_STRONG
        return PasswordStrength(score, bits, feedback)
    } finally {
        normalized.fill('\u0000')
        if (analyzed !== normalized) analyzed.fill('\u0000')
        lowered.fill('\u0000')
        folded.fill('\u0000')
    }
}

private fun scoreFor(bits: Double): Int = when {
    bits < SCORE_1_BITS -> 0
    bits < SCORE_2_BITS -> 1
    bits < SCORE_3_BITS -> 2
    bits < SCORE_4_BITS -> 3
    else -> 4
}

/** Ordered class flags: lowercase, uppercase, digit, ASCII symbol, other Unicode letter. */
private fun characterClasses(chars: CharArray): BooleanArray {
    val classes = BooleanArray(5)
    for (character in chars) {
        when {
            character in 'a'..'z' -> classes[0] = true
            character in 'A'..'Z' -> classes[1] = true
            character in '0'..'9' -> classes[2] = true
            character.code in 0x20..0x7E -> classes[3] = true
            else -> classes[4] = true
        }
    }
    return classes
}

private fun alphabetSize(classes: BooleanArray): Int {
    var size = 0
    if (classes[0]) size += LOWERCASE_ALPHABET
    if (classes[1]) size += UPPERCASE_ALPHABET
    if (classes[2]) size += DIGIT_ALPHABET
    if (classes[3]) size += ASCII_SYMBOL_ALPHABET
    if (classes[4]) size += OTHER_LETTER_ALPHABET
    return max(size, 2)
}

/** A region of the password explained more cheaply by a pattern than by brute force. */
private data class PatternMatch(
    val start: Int,
    val end: Int,
    val fixedBits: Double,
    val periodChars: Int,
    val key: String,
)

/**
 * Per-character model: brute force over the observed alphabet, minus what every detected pattern
 * saves the attacker. Matches are applied greedily, highest saving first, and each one is only paid
 * for over the characters no earlier match already explained, so overlapping detectors (a digit run
 * that is both a sequence and a keyboard row) cannot be counted twice.
 */
private fun characterBits(
    analyzed: CharArray,
    lowered: CharArray,
    folded: CharArray,
    classes: BooleanArray,
    warnings: MutableSet<String>,
): Double {
    val bitsPerChar = log2(alphabetSize(classes).toDouble())
    val base = analyzed.size * bitsPerChar
    val matches = ArrayList<PatternMatch>()
    matches += findSequences(analyzed)
    matches += findKeyboardRuns(lowered)
    matches += findRepeats(analyzed)
    matches += findDates(analyzed)
    matches += findCommonWords(folded)
    matches.sortByDescending {
        (it.end - it.start) * bitsPerChar - it.fixedBits - it.periodChars * bitsPerChar
    }
    val covered = BooleanArray(analyzed.size)
    var savings = 0.0
    for (match in matches) {
        var fresh = 0
        for (index in match.start until match.end) if (!covered[index]) fresh++
        if (fresh == 0) continue
        for (index in match.start until match.end) covered[index] = true
        warnings += match.key
        val gain = fresh * bitsPerChar - (match.fixedBits + match.periodChars * bitsPerChar)
        if (gain > 0.0) savings += gain
    }
    if (hasTrivialSuffix(analyzed)) {
        warnings += PasswordFeedback.TRIVIAL_SUFFIX_PATTERN
        savings += TRIVIAL_SUFFIX_PENALTY_BITS
    }
    return max(0.0, base - savings)
}

/**
 * Passphrase model, returning `null` when the password is not shaped like one.
 *
 * Three or more separator-joined alphabetic tokens are priced by word choice rather than by
 * character, because that is how they are actually attacked. This is the "correct horse battery
 * staple" case: the words are individually ordinary, but the search space is combinatorial over a
 * vocabulary the attacker has to guess, not over the handful of terms this file happens to embed.
 * Without this branch every readable passphrase would be punished once per word and users would be
 * pushed back towards short, unmemorable, symbol-stuffed passwords - the opposite of the goal.
 *
 * Two guards keep the branch from becoming a trivial bypass, and they are a deliberate deviation
 * from "never penalize a token": a token repeated from earlier in the phrase is worth almost
 * nothing, and a token that is itself in [COMMON_TERMS] or is a keyboard walk is priced at that
 * small list's size instead of at the assumed vocabulary. Otherwise `senha senha senha senha` or
 * `qwertyuiop asdfghjkl zxcvbnm` would score as strong.
 */
private fun passphraseBits(
    analyzed: CharArray,
    lowered: CharArray,
    folded: CharArray,
    warnings: MutableSet<String>,
): Double? {
    val tokens = ArrayList<IntArray>()
    var index = 0
    while (index < analyzed.size) {
        if (PASSPHRASE_SEPARATORS.indexOf(analyzed[index]) >= 0) {
            index++
            continue
        }
        val start = index
        while (index < analyzed.size && PASSPHRASE_SEPARATORS.indexOf(analyzed[index]) < 0) index++
        tokens += intArrayOf(start, index)
    }
    if (tokens.size < PASSPHRASE_MINIMUM_TOKENS) return null
    for (token in tokens) {
        if (token[1] - token[0] < PASSPHRASE_MINIMUM_TOKEN_LENGTH) return null
        for (position in token[0] until token[1]) if (!Character.isLetter(analyzed[position])) return null
    }
    var bits = PASSPHRASE_STRUCTURE_BITS
    val seen = ArrayList<IntArray>()
    for (token in tokens) {
        val length = token[1] - token[0]
        bits += when {
            seen.any { sameSlice(lowered, it, token) } -> {
                warnings += PasswordFeedback.REPEATED_CHARS
                REPEATED_TOKEN_BITS
            }
            KEYBOARD_SEQUENCES.any { containsSliceIn(it, lowered, token[0], length) } -> {
                warnings += PasswordFeedback.SEQUENTIAL_CHARS
                KEYBOARD_BITS
            }
            COMMON_TERMS.any { it.length == length && matchesSlice(folded, token[0], it) } -> {
                warnings += PasswordFeedback.COMMON_WORD
                COMMON_TERM_BITS
            }
            else -> min(length * LOG2_LATIN_ALPHABET, ASSUMED_VOCABULARY_BITS)
        }
        seen += token
    }
    return bits
}

private fun findSequences(chars: CharArray): List<PatternMatch> {
    val matches = ArrayList<PatternMatch>()
    var start = 0
    while (start < chars.size) {
        var end = start + 1
        var direction = 0
        while (end < chars.size) {
            val delta = chars[end].code - chars[end - 1].code
            if (delta != 1 && delta != -1) break
            if (direction == 0) direction = delta else if (direction != delta) break
            end++
        }
        if (end - start >= SEQUENCE_MINIMUM_RUN) {
            matches += PatternMatch(start, end, SEQUENCE_BITS, 0, PasswordFeedback.SEQUENTIAL_CHARS)
        }
        start = if (end - start >= 2) end - 1 else start + 1
    }
    return matches
}

private fun findKeyboardRuns(lowered: CharArray): List<PatternMatch> {
    val matches = ArrayList<PatternMatch>()
    var index = 0
    while (index < lowered.size) {
        var best = 0
        for (row in KEYBOARD_SEQUENCES) {
            var length = max(best + 1, KEYBOARD_MINIMUM_RUN)
            while (index + length <= lowered.size && containsSliceIn(row, lowered, index, length)) {
                best = length
                length++
            }
        }
        if (best >= KEYBOARD_MINIMUM_RUN) {
            matches += PatternMatch(index, index + best, KEYBOARD_BITS, 0, PasswordFeedback.SEQUENTIAL_CHARS)
            index += best
        } else {
            index++
        }
    }
    return matches
}

private fun findRepeats(chars: CharArray): List<PatternMatch> {
    val matches = ArrayList<PatternMatch>()
    for (period in 1..REPEAT_MAXIMUM_PERIOD) {
        var start = 0
        while (start + period < chars.size) {
            var end = start + period
            while (end < chars.size && chars[end] == chars[end - period]) end++
            val length = end - start
            if (length >= REPEAT_MINIMUM_RUN && length >= 2 * period) {
                matches += PatternMatch(start, end, REPEAT_FIXED_BITS, period, PasswordFeedback.REPEATED_CHARS)
            }
            start = max(start + 1, end - period + 1)
        }
    }
    return matches
}

private fun findDates(chars: CharArray): List<PatternMatch> {
    val matches = ArrayList<PatternMatch>()
    var index = 0
    while (index < chars.size) {
        if (chars[index] !in '0'..'9') {
            index++
            continue
        }
        val start = index
        while (index < chars.size && chars[index] in '0'..'9') index++
        val length = index - start
        if ((length == 6 || length == 8) && looksLikeDate(chars, start, length)) {
            matches += PatternMatch(start, index, DATE_BITS, 0, PasswordFeedback.DATE_OR_YEAR)
            continue
        }
        var window = start
        while (window + 4 <= index) {
            if (digits(chars, window, 4) in 1900..2099) {
                matches += PatternMatch(window, window + 4, YEAR_BITS, 0, PasswordFeedback.DATE_OR_YEAR)
                window += 4
            } else {
                window++
            }
        }
    }
    return matches
}

private fun looksLikeDate(chars: CharArray, start: Int, length: Int): Boolean {
    if (length == 8) {
        val yearFirst = digits(chars, start, 4) in 1900..2099 &&
            isMonth(digits(chars, start + 4, 2)) && isDay(digits(chars, start + 6, 2))
        val dayFirst = isDay(digits(chars, start, 2)) && isMonth(digits(chars, start + 2, 2)) &&
            digits(chars, start + 4, 4) in 1900..2099
        return yearFirst || dayFirst
    }
    val dayFirst = isDay(digits(chars, start, 2)) && isMonth(digits(chars, start + 2, 2))
    val yearFirst = isMonth(digits(chars, start + 2, 2)) && isDay(digits(chars, start + 4, 2))
    return dayFirst || yearFirst
}

private fun isMonth(value: Int) = value in 1..12

private fun isDay(value: Int) = value in 1..31

private fun digits(chars: CharArray, start: Int, length: Int): Int {
    var value = 0
    for (offset in 0 until length) value = value * 10 + (chars[start + offset] - '0')
    return value
}

private fun findCommonWords(folded: CharArray): List<PatternMatch> {
    val matches = ArrayList<PatternMatch>()
    var index = 0
    while (index < folded.size) {
        var best = 0
        for (term in COMMON_TERMS) {
            if (term.length > best && matchesSlice(folded, index, term)) best = term.length
        }
        if (best > 0) {
            matches += PatternMatch(index, index + best, COMMON_TERM_BITS, 0, PasswordFeedback.COMMON_WORD)
            index += best
        } else {
            index++
        }
    }
    return matches
}

/** `Palavra1!` - a capitalized word with everything non-alphabetic pushed to the end. */
private fun hasTrivialSuffix(chars: CharArray): Boolean {
    if (chars.size < 4 || !Character.isUpperCase(chars[0])) return false
    var index = 1
    while (index < chars.size && Character.isLowerCase(chars[index])) index++
    if (index < 3 || index == chars.size) return false
    for (position in index until chars.size) {
        val character = chars[position]
        val trailing = character in '0'..'9' || character.code in 0x21..0x2F ||
            character.code in 0x3A..0x40 || character.code in 0x5B..0x60 ||
            character.code in 0x7B..0x7E
        if (!trailing) return false
    }
    return true
}

private fun matchesSlice(chars: CharArray, start: Int, term: String): Boolean {
    if (start + term.length > chars.size) return false
    for (offset in term.indices) if (chars[start + offset] != term[offset]) return false
    return true
}

private fun sameSlice(chars: CharArray, first: IntArray, second: IntArray): Boolean {
    val length = first[1] - first[0]
    if (length != second[1] - second[0]) return false
    for (offset in 0 until length) {
        if (chars[first[0] + offset] != chars[second[0] + offset]) return false
    }
    return true
}

private fun containsSliceIn(row: String, chars: CharArray, start: Int, length: Int): Boolean {
    if (length > row.length || start + length > chars.size) return false
    for (offset in 0..row.length - length) {
        var matched = 0
        while (matched < length && row[offset + matched] == chars[start + matched]) matched++
        if (matched == length) return true
    }
    return false
}

private fun log2(value: Double): Double = ln(value) / LN2
