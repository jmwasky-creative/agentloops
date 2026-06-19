package com.agentsloop.json

class JsonParseException(message: String) : IllegalArgumentException(message)

object SimpleJson {
    fun parse(source: String): Any? = Parser(source).parse()

    @Suppress("UNCHECKED_CAST")
    fun parseObject(source: String): Map<String, Any?> =
        parse(source) as? Map<String, Any?> ?: throw JsonParseException("Expected JSON object")

    fun stringify(value: Any?): String = buildString { writeValue(value) }

    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> writeString(value)
            is Number, is Boolean -> append(value.toString())
            is Map<*, *> -> {
                append("{")
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(",")
                    writeString(entry.key.toString())
                    append(":")
                    writeValue(entry.value)
                }
                append("}")
            }
            is Iterable<*> -> {
                append("[")
                value.forEachIndexed { index, item ->
                    if (index > 0) append(",")
                    writeValue(item)
                }
                append("]")
            }
            else -> writeString(value.toString())
        }
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u${ch.code.toString(16).padStart(4, '0')}") else append(ch)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != source.length) throw JsonParseException("Unexpected trailing content at $index")
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (index >= source.length) throw JsonParseException("Unexpected end of JSON")
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonParseException("Unexpected character '${source[index]}' at $index")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (peek('}')) {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return result
                    }
                    else -> throw JsonParseException("Expected ',' or '}' at $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (peek(']')) {
                index++
                return result
            }
            while (true) {
                result += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return result
                    }
                    else -> throw JsonParseException("Expected ',' or ']' at $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < source.length) {
                when (val ch = source[index++]) {
                    '"' -> return out.toString()
                    '\\' -> out.append(parseEscape())
                    else -> out.append(ch)
                }
            }
            throw JsonParseException("Unterminated string")
        }

        private fun parseEscape(): Char {
            if (index >= source.length) throw JsonParseException("Invalid escape at $index")
            return when (val escaped = source[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > source.length) throw JsonParseException("Invalid unicode escape")
                    val hex = source.substring(index, index + 4)
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> throw JsonParseException("Unknown escape \\$escaped at $index")
            }
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek('-')) index++
            readDigits()
            if (peek('.')) {
                index++
                readDigits()
            }
            if (peek('e') || peek('E')) {
                index++
                if (peek('+') || peek('-')) index++
                readDigits()
            }
            val raw = source.substring(start, index)
            return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) raw.toDouble() else raw.toLong()
        }

        private fun readDigits() {
            val start = index
            while (index < source.length && source[index].isDigit()) index++
            if (start == index) throw JsonParseException("Expected digit at $index")
        }

        private fun readLiteral(raw: String, value: Any?): Any? {
            if (!source.startsWith(raw, index)) throw JsonParseException("Expected $raw at $index")
            index += raw.length
            return value
        }

        private fun expect(ch: Char) {
            if (!peek(ch)) throw JsonParseException("Expected '$ch' at $index")
            index++
        }

        private fun peek(ch: Char): Boolean = index < source.length && source[index] == ch

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }
    }
}
