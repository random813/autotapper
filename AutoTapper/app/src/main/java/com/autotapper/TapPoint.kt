package com.autotapper

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tap point. Position and size are stored *normalized*:
 *  - nx, ny: centre as a fraction of screen width/height (0..1)
 *  - nr: radius as a fraction of the smaller screen dimension
 * so a setup survives rotation and lands in the same relative place
 * across screen sizes.
 *
 * Radius semantics: below the crosshair threshold the marker is an exact
 * crosshair (taps land on the centre pixel, no randomness); above it, each
 * tap lands on a uniformly random point inside the circle, finger-like.
 */
data class TapPoint(
    var nx: Double = 0.5,
    var ny: Double = 0.5,
    var nr: Double = 0.08,
    var intervalMinMs: Long = 1000,
    var intervalMaxMs: Long = 3000,
    var enabled: Boolean = true,
    var locked: Boolean = false,
) {
    var clickCount: Long = 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("nx", nx); put("ny", ny); put("nr", nr)
        put("min", intervalMinMs); put("max", intervalMaxMs)
        put("enabled", enabled); put("locked", locked)
    }

    companion object {
        fun fromJson(o: JSONObject) = TapPoint(
            nx = o.optDouble("nx", 0.5),
            ny = o.optDouble("ny", 0.5),
            nr = o.optDouble("nr", 0.08),
            intervalMinMs = o.optLong("min", 1000),
            intervalMaxMs = o.optLong("max", 3000),
            enabled = o.optBoolean("enabled", true),
            locked = o.optBoolean("locked", false),
        )

        fun listToJson(points: List<TapPoint>): String =
            JSONArray().apply { points.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): MutableList<TapPoint> {
            if (s.isNullOrBlank()) return mutableListOf()
            return try {
                val arr = JSONArray(s)
                MutableList(arr.length()) { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                mutableListOf()
            }
        }
    }
}
