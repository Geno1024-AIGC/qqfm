package com.geno1024.ai.qqfm.json

/**
 * A minimal recursive-descent JSON reader, because nothing here needs a library.
 *
 * Decodes into [Map] / [List] / [String] / [Double] / [Boolean] / null and throws on
 * anything malformed, which is the right way round: a release feed that is not what it
 * claims to be should stop the check, not quietly produce an empty update.
 */
object Json {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        check(reader.ended()) { "trailing content at index ${reader.position}" }
        return value
    }

    class Reader(private val text: String) {

        var position: Int = 0
            private set

        fun ended(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        fun value(): Any? {
            skipWhitespace()
            return when (text.getOrNull(position)) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            check(text.startsWith(word, position)) { "expected '$word' at index $position" }
            position += word.length
            return value
        }

        private fun obj(): Map<String, Any?> {
            skipWhitespace()
            check(take('{')) { "expected '{' at index $position" }
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (take('}')) return map
            while (true) {
                skipWhitespace()
                val key = string()
                skipWhitespace()
                check(take(':')) { "expected ':' at index $position" }
                map[key] = value()
                skipWhitespace()
                if (take('}')) return map
                check(take(',')) { "expected ',' or '}' at index $position" }
            }
        }

        private fun array(): List<Any?> {
            skipWhitespace()
            check(take('[')) { "expected '[' at index $position" }
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (take(']')) return list
            while (true) {
                list.add(value())
                skipWhitespace()
                if (take(']')) return list
                check(take(',')) { "expected ',' or ']' at index $position" }
            }
        }

        private fun string(): String {
            skipWhitespace()
            check(take('"')) { "expected '\"' at index $position" }
            val out = StringBuilder()
            while (true) {
                val c = text[position]
                position++
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        val escape = text[position]
                        position++
                        when (escape) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                val hex = text.substring(position, position + 4)
                                out.append(hex.toInt(16).toChar())
                                position += 4
                            }
                            else -> error("bad escape '\\$escape' at index ${position - 1}")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Double {
            skipWhitespace()
            val start = position
            while (position < text.length && text[position] in "-+.eE0123456789") position++
            check(position > start) { "expected a number at index $position" }
            return text.substring(start, position).toDouble()
        }

        private fun take(c: Char): Boolean {
            if (text.getOrNull(position) != c) return false
            position++
            return true
        }
    }
}
