package dev.starfect.quill.io.vscode

/**
 * JSON as VS Code actually writes it.
 *
 * VS Code's `settings.json` is JSON with comments — the format its own editor calls "jsonc" — and it
 * ships commented lines in the default file, encourages people to annotate their settings, and
 * tolerates a trailing comma before a closing brace. A strict JSON parser fails on the first real
 * file it meets, which would make the import feature work only for people who did not use the
 * feature it is importing from.
 *
 * So: comments are skipped, trailing commas are allowed, and everything else is ordinary JSON.
 *
 * Hand-written rather than pulled in. The alternative is a JSON library, a ProGuard keep rule for
 * whatever it reflects over, and a shrinking configuration to maintain — for one file read once,
 * when a user clicks a button.
 *
 * Values come back as the plainest Kotlin equivalent: [Boolean], [Double], [String], [List], [Map],
 * or null. There is no schema here and there should not be; the mapping onto Quill's own settings is
 * [VsCodeSettings]' business.
 */
internal object Jsonc {

    /** What went wrong, and where — a settings file with a typo should say which line. */
    class MalformedException(message: String, val offset: Int) : Exception(message)

    /** Parses [text]. Throws [MalformedException] on anything it cannot read. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipBlanks()
        val value = reader.readValue()
        reader.skipBlanks()
        if (!reader.done) reader.fail("trailing content after the top-level value")
        return value
    }

    /** Parses [text], or returns null rather than throwing. */
    fun parseOrNull(text: String): Any? = runCatching { parse(text) }.getOrNull()

    private class Reader(private val text: String) {
        private var at = 0

        val done: Boolean get() = at >= text.length

        fun fail(message: String): Nothing = throw MalformedException(
            "$message at offset $at (line ${lineOf(at)})",
            at,
        )

        private fun lineOf(offset: Int) = text.take(offset).count { it == '\n' } + 1

        /** Whitespace and comments, which are the same thing as far as everything else is concerned. */
        fun skipBlanks() {
            while (at < text.length) {
                val character = text[at]
                when {
                    character.isWhitespace() -> at++
                    character == '/' && at + 1 < text.length && text[at + 1] == '/' -> {
                        while (at < text.length && text[at] != '\n') at++
                    }
                    character == '/' && at + 1 < text.length && text[at + 1] == '*' -> {
                        val end = text.indexOf("*/", at + 2)
                        at = if (end < 0) text.length else end + 2
                    }
                    else -> return
                }
            }
        }

        fun readValue(): Any? {
            if (done) fail("expected a value")
            return when (val character = text[at]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readWord("true", true)
                'f' -> readWord("false", false)
                'n' -> readWord("null", null)
                else ->
                    if (character == '-' || character.isDigit()) readNumber() else fail("unexpected '$character'")
            }
        }

        private fun <T> readWord(word: String, value: T): T {
            if (!text.startsWith(word, at)) fail("expected '$word'")
            at += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            at++ // {
            val result = LinkedHashMap<String, Any?>()
            skipBlanks()
            if (!done && text[at] == '}') {
                at++
                return result
            }

            while (true) {
                skipBlanks()
                // A trailing comma leaves us looking at the closing brace.
                if (!done && text[at] == '}') {
                    at++
                    return result
                }
                if (done || text[at] != '"') fail("expected a quoted key")
                val key = readString()

                skipBlanks()
                if (done || text[at] != ':') fail("expected ':' after the key '$key'")
                at++

                skipBlanks()
                result[key] = readValue()

                skipBlanks()
                when {
                    done -> fail("unterminated object")
                    text[at] == ',' -> at++
                    text[at] == '}' -> {
                        at++
                        return result
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            at++ // [
            val result = ArrayList<Any?>()
            skipBlanks()
            if (!done && text[at] == ']') {
                at++
                return result
            }

            while (true) {
                skipBlanks()
                if (!done && text[at] == ']') {
                    at++
                    return result
                }
                result += readValue()

                skipBlanks()
                when {
                    done -> fail("unterminated array")
                    text[at] == ',' -> at++
                    text[at] == ']' -> {
                        at++
                        return result
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            at++ // opening quote
            val builder = StringBuilder()
            while (true) {
                if (done) fail("unterminated string")
                when (val character = text[at]) {
                    '"' -> {
                        at++
                        return builder.toString()
                    }
                    '\\' -> {
                        at++
                        if (done) fail("unterminated escape")
                        when (val escape = text[at]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (at + 4 >= text.length) fail("truncated unicode escape")
                                val hex = text.substring(at + 1, at + 5)
                                val code = hex.toIntOrNull(16) ?: fail("'$hex' is not a unicode escape")
                                builder.append(code.toChar())
                                at += 4
                            }
                            else -> fail("unknown escape '\\$escape'")
                        }
                        at++
                    }
                    else -> {
                        builder.append(character)
                        at++
                    }
                }
            }
        }

        private fun readNumber(): Double {
            val start = at
            if (!done && text[at] == '-') at++
            while (!done && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val literal = text.substring(start, at)
            return literal.toDoubleOrNull() ?: run {
                at = start
                fail("'$literal' is not a number")
            }
        }
    }
}
