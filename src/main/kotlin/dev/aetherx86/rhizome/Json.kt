package dev.aetherx86.rhizome

/**
 * A small JSON writer for the command line's `--json` output, so that the library needs no
 * dependency beyond coroutines. Torrent and file names come from untrusted files, so every string
 * is escaped, including control characters and unpaired surrogates.
 */
object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Double -> if (value.isFinite()) out.append(value) else out.append("null")
            is Float -> write(out, value.toDouble())
            is CharSequence -> string(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    string(out, requireNotNull(key) { "a JSON object key cannot be null" }.toString())
                    out.append(':')
                    write(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.asList())
            else -> throw IllegalArgumentException("cannot write ${value::class.simpleName} as JSON")
        }
    }

    private fun string(out: StringBuilder, text: CharSequence) {
        out.append('"')
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c.code < 0x20 || c == ' ' || c == ' ' || c == '\u007F' -> unicode(out, c)
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    out.append(c).append(text[i + 1])
                    i++
                }
                c.isSurrogate() -> unicode(out, c)
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun unicode(out: StringBuilder, c: Char) {
        out.append("\\u").append("%04x".format(c.code))
    }
}
