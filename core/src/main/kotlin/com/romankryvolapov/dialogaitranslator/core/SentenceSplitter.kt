package com.romankryvolapov.dialogaitranslator.core

/**
 * Cuts a transcript that grows until the speaker pauses into phrases at its sentence ends, so that a speaker who makes
 * no pauses is still translated as they go. A sentence is taken once [SETTLING_WORDS] more words have followed it: the
 * service rewrites the end of what it heard, seldom more. What was taken is remembered as a number of words and the last
 * of them, so it is found again when an earlier rewrite ("let us" into "let's") moved it. Not thread-safe.
 */
class SentenceSplitter(
    /** With no sentence end in sight, a phrase is taken at this many words. */
    private val maxWords: Int = 40,
) {

    private companion object {
        /** Shorter sentences go together: each phrase is a request, and a free tier allows about twelve a minute. */
        const val MIN_PHRASE_CHARS = 50
        const val SETTLING_WORDS = 2
        const val TAIL_WORDS = 2
        /** How far a rewrite may have moved the place the taken words end. */
        const val DRIFT_WORDS = 5
        /** The service joins two of its segments without a space: "application?Hello". */
        val GLUED = Regex("""(?<=[\p{Ll}\d][.?!])(?=\p{Lu})""")
    }

    private var taken = 0
    /** The last words taken, without punctuation and case. */
    private var tail = emptyList<String>()

    class Result(val phrases: List<String>, val rest: String)

    /** [transcript]: everything heard since the last [finish]. */
    fun grow(transcript: String): Result {
        val words = words(transcript)
        val phrases = ArrayList<String>()
        var from = start(words)
        var at = from
        while (at < words.size - SETTLING_WORDS) {
            val sentence = words[at].last() in ".?!"
            at += 1
            val phrase = words.subList(from, at).joinToString(" ")
            if ((sentence && phrase.length >= MIN_PHRASE_CHARS) || at - from >= maxWords) {
                phrases += phrase
                from = at
                taken = at
                tail = words.subList(maxOf(0, at - TAIL_WORDS), at).map(::bare)
            }
        }
        return Result(phrases, words.drop(from).joinToString(" "))
    }

    /** The settled transcript: what of it has not been taken yet. */
    fun finish(transcript: String): String {
        val words = words(transcript)
        val rest = words.drop(start(words)).joinToString(" ")
        taken = 0
        tail = emptyList()
        return rest
    }

    /** Where the words not taken yet begin: after [tail], looked for around the place it was. */
    private fun start(words: List<String>): Int {
        if (tail.isEmpty()) return 0
        val places = (0..DRIFT_WORDS).flatMap { listOf(taken - it, taken + it) }
        return places.firstOrNull { at ->
            at - tail.size >= 0 && at <= words.size && words.subList(at - tail.size, at).map(::bare) == tail
        } ?: taken.coerceAtMost(words.size)
    }

    private fun bare(word: String) = word.filter { it.isLetterOrDigit() }.lowercase()

    private fun words(text: String): List<String> = GLUED.replace(text, " ").split(Regex("""\s+""")).filter { it.isNotEmpty() }
}
