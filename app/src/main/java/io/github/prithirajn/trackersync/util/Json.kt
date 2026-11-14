package io.github.prithirajn.trackersync.util

import org.json.JSONArray
import org.json.JSONObject

// org.json turns a JSON null into the string "null" in optString, so these
// helpers treat missing and null the same way: as Kotlin null.

fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONObject.num(key: String): Double? {
    if (isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}

fun JSONObject.long(key: String): Long? = num(key)?.toLong()

fun JSONObject.int(key: String): Int? = num(key)?.toInt()

fun JSONObject.bool(key: String, fallback: Boolean = false): Boolean =
    if (isNull(key)) fallback else optBoolean(key, fallback)

fun JSONObject.obj(key: String): JSONObject? = if (isNull(key)) null else optJSONObject(key)

fun JSONObject.arr(key: String): JSONArray? = if (isNull(key)) null else optJSONArray(key)

/** A value that may arrive as a number or a string, as text ("123", not "123.0"). */
fun JSONObject.idString(key: String): String? {
    if (isNull(key)) return null
    return when (val v = opt(key)) {
        is Int, is Long -> v.toString()
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d)) d.toLong().toString() else d.toString()
        }
        is String -> v.takeIf { it.isNotEmpty() }
        else -> null
    }
}

/** Walks a path of keys; returns null at the first missing step. */
fun JSONObject.dig(vararg keys: String): Any? {
    var cur: Any? = this
    for (k in keys) {
        cur = (cur as? JSONObject)?.let { if (it.isNull(k)) null else it.opt(k) } ?: return null
    }
    return cur
}

fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

fun JSONArray.strings(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        if (!isNull(i)) out.add(optString(i))
    }
    return out
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
    return o
}

fun jsonArrayOf(values: Iterable<Any?>): JSONArray {
    val a = JSONArray()
    for (v in values) a.put(v ?: JSONObject.NULL)
    return a
}
