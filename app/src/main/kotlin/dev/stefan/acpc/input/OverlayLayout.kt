package dev.stefan.acpc.input

import org.json.JSONArray
import org.json.JSONObject

/** Position and size of the touch controls, in fractions of the view size. */
data class OverlayLayout(
    var joystickX: Float = 0.17f,
    var joystickY: Float = 0.70f,
    var joystickSize: Float = 0.30f,
    var fire1X: Float = 0.88f,
    var fire1Y: Float = 0.72f,
    var fire2X: Float = 0.76f,
    var fire2Y: Float = 0.82f,
    var buttonSize: Float = 0.12f,
    var extraKeys: MutableList<ExtraKey> = mutableListOf(),
) {
    /** An additional on-screen button mapped to a CPC key. */
    data class ExtraKey(var key: String, var label: String, var x: Float, var y: Float)

    fun toJson(): JSONObject = JSONObject().apply {
        put("joystickX", joystickX.toDouble()); put("joystickY", joystickY.toDouble()); put("joystickSize", joystickSize.toDouble())
        put("fire1X", fire1X.toDouble()); put("fire1Y", fire1Y.toDouble())
        put("fire2X", fire2X.toDouble()); put("fire2Y", fire2Y.toDouble())
        put("buttonSize", buttonSize.toDouble())
        put("extraKeys", JSONArray().apply { extraKeys.forEach { put(JSONObject().apply { put("key", it.key); put("label", it.label); put("x", it.x.toDouble()); put("y", it.y.toDouble()) }) } })
    }

    companion object {
        fun fromJson(o: JSONObject): OverlayLayout = OverlayLayout(
            joystickX = o.optDouble("joystickX", 0.17).toFloat(), joystickY = o.optDouble("joystickY", 0.70).toFloat(),
            joystickSize = o.optDouble("joystickSize", 0.30).toFloat(),
            fire1X = o.optDouble("fire1X", 0.88).toFloat(), fire1Y = o.optDouble("fire1Y", 0.72).toFloat(),
            fire2X = o.optDouble("fire2X", 0.76).toFloat(), fire2Y = o.optDouble("fire2Y", 0.82).toFloat(),
            buttonSize = o.optDouble("buttonSize", 0.12).toFloat(),
            extraKeys = mutableListOf<ExtraKey>().apply {
                val arr = o.optJSONArray("extraKeys") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    add(ExtraKey(e.getString("key"), e.getString("label"), e.getDouble("x").toFloat(), e.getDouble("y").toFloat()))
                }
            },
        )

        /** Built-in profiles. */
        val PROFILES: Map<String, OverlayLayout> = mapOf(
            "platform" to OverlayLayout(
                extraKeys = mutableListOf(ExtraKey("SPACE", "SPC", 0.62f, 0.86f)),
            ),
            "adventure" to OverlayLayout(
                joystickSize = 0.24f, buttonSize = 0.10f,
                extraKeys = mutableListOf(ExtraKey("RETURN", "RET", 0.62f, 0.86f), ExtraKey("SPACE", "SPC", 0.52f, 0.86f), ExtraKey("ESC", "ESC", 0.42f, 0.86f)),
            ),
            "shootemup" to OverlayLayout(
                joystickSize = 0.34f, buttonSize = 0.14f, fire1X = 0.86f, fire1Y = 0.66f, fire2X = 0.72f, fire2Y = 0.80f,
            ),
            "custom" to OverlayLayout(),
        )

        fun profileNames(): List<String> = PROFILES.keys.toList()

        /** Loads all profiles, applying user customisations stored as JSON {name: layout}. */
        fun loadAll(json: String?): MutableMap<String, OverlayLayout> {
            val result = LinkedHashMap<String, OverlayLayout>()
            for ((k, v) in PROFILES) result[k] = v.copy(extraKeys = v.extraKeys.map { it.copy() }.toMutableList())
            if (json != null) {
                runCatching {
                    val o = JSONObject(json)
                    for (k in o.keys()) result[k] = fromJson(o.getJSONObject(k))
                }
            }
            return result
        }

        fun saveAll(layouts: Map<String, OverlayLayout>): String {
            val o = JSONObject()
            for ((k, v) in layouts) o.put(k, v.toJson())
            return o.toString()
        }
    }
}
