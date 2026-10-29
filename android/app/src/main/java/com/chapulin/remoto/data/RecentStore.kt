package com.chapulin.remoto.data

import android.content.Context
import com.chapulin.remoto.net.HostTarget
import org.json.JSONArray
import org.json.JSONObject

class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("chapulin_recent", Context.MODE_PRIVATE)

    fun load(): List<HostTarget> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(
                        HostTarget(
                            address = obj.getString("address"),
                            port = obj.getInt("port"),
                            pin = obj.optString("pin", "")
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(target: HostTarget) {
        val current = load().toMutableList()
        current.removeAll { it.address == target.address && it.port == target.port }
        current.add(0, target)
        val trimmed = current.take(10)

        val arr = JSONArray()
        for (t in trimmed) {
            arr.put(
                JSONObject().apply {
                    put("address", t.address)
                    put("port", t.port)
                    put("pin", t.pin)
                }
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("items").apply()
    }
}
