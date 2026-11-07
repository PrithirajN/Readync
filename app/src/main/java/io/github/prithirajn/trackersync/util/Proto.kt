package io.github.prithirajn.trackersync.util

/**
 * Just enough of Protocol Buffers to read a file: each field's number, wire type and value, in
 * order. Unknown fields are skipped, so a newer file with more fields still reads.
 */
class Proto(private val b: ByteArray, private var pos: Int = 0, private val end: Int = b.size) {

    sealed interface Value {
        data class Varint(val v: Long) : Value
        data class Fixed64(val v: Long) : Value
        data class Fixed32(val v: Int) : Value
        class Bytes(val data: ByteArray, val from: Int, val to: Int) : Value {
            fun message() = Proto(data, from, to)
            fun string() = String(data, from, to - from, Charsets.UTF_8)
        }
    }

    fun hasMore() = pos < end

    private fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            if (pos >= end) throw IllegalStateException("cut short")
            val byte = b[pos++].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalStateException("bad varint")
        }
    }

    /** The next field: (number, value). */
    fun next(): Pair<Int, Value> {
        val key = varint()
        val field = (key ushr 3).toInt()
        return field to when ((key and 7).toInt()) {
            0 -> Value.Varint(varint())
            1 -> { var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 8; Value.Fixed64(v) }
            2 -> { val len = varint().toInt(); val from = pos; pos += len; if (pos > end) throw IllegalStateException("cut short"); Value.Bytes(b, from, from + len) }
            5 -> { var v = 0; for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i)); pos += 4; Value.Fixed32(v) }
            else -> throw IllegalStateException("unsupported wire type")
        }
    }

    /** Every field, grouped by number (repeated fields keep their order). */
    fun fields(): Map<Int, List<Value>> {
        val out = LinkedHashMap<Int, MutableList<Value>>()
        while (hasMore()) { val (n, v) = next(); out.getOrPut(n) { ArrayList() } += v }
        return out
    }
}

fun Map<Int, List<Proto.Value>>.str(n: Int): String? = (this[n]?.lastOrNull() as? Proto.Value.Bytes)?.string()
fun Map<Int, List<Proto.Value>>.long(n: Int): Long? = (this[n]?.lastOrNull() as? Proto.Value.Varint)?.v
fun Map<Int, List<Proto.Value>>.bool(n: Int): Boolean = (this[n]?.lastOrNull() as? Proto.Value.Varint)?.v?.let { it != 0L } ?: false
fun Map<Int, List<Proto.Value>>.float(n: Int): Float? = (this[n]?.lastOrNull() as? Proto.Value.Fixed32)?.v?.let { Float.fromBits(it) }
fun Map<Int, List<Proto.Value>>.messages(n: Int): List<Map<Int, List<Proto.Value>>> =
    this[n].orEmpty().mapNotNull { (it as? Proto.Value.Bytes)?.message()?.fields() }
