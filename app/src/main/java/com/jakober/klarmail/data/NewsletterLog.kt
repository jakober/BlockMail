package com.jakober.klarmail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Protokoll der Newsletter-Aufräumläufe (JSON-Datei im App-Speicher). */
object NewsletterLog {

    data class Item(
        val from: String,
        val address: String,
        val subject: String,
        val unsubscribe: String?,
        val uid: Long = 0L,
        val date: Long = 0L
    )

    data class Run(val time: Long, val items: List<Item>)

    private fun file(context: Context) = File(context.filesDir, "newsletter_log.json")

    fun load(context: Context): List<Run> = try {
        val text = file(context).takeIf { it.exists() }?.readText() ?: "[]"
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            Run(
                time = o.optLong("time"),
                items = (0 until itemsArr.length()).map { j ->
                    val io = itemsArr.getJSONObject(j)
                    Item(
                        from = io.optString("from"),
                        address = io.optString("address"),
                        subject = io.optString("subject"),
                        unsubscribe = io.optString("unsubscribe").takeIf { it.isNotBlank() },
                        uid = io.optLong("uid"),
                        date = io.optLong("date")
                    )
                }
            )
        }.sortedByDescending { it.time }
    } catch (e: Exception) {
        emptyList()
    }

    /** Entfernt alle Protokoll-Einträge eines Absenders (nach Abmeldung). */
    fun removeByAddress(context: Context, address: String) {
        try {
            val runs = load(context).map { r ->
                r.copy(items = r.items.filterNot { it.address.equals(address, ignoreCase = true) })
            }.filter { it.items.isNotEmpty() }
            val arr = JSONArray()
            runs.forEach { r ->
                arr.put(JSONObject().apply {
                    put("time", r.time)
                    put("items", JSONArray().apply {
                        r.items.forEach { item ->
                            put(JSONObject().apply {
                                put("from", item.from)
                                put("address", item.address)
                                put("subject", item.subject)
                                put("unsubscribe", item.unsubscribe ?: "")
                                put("uid", item.uid)
                                put("date", item.date)
                            })
                        }
                    })
                })
            }
            file(context).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    fun append(context: Context, run: Run) {
        try {
            val existing = load(context).take(29)
            val arr = JSONArray()
            (listOf(run) + existing).forEach { r ->
                arr.put(JSONObject().apply {
                    put("time", r.time)
                    put("items", JSONArray().apply {
                        r.items.forEach { item ->
                            put(JSONObject().apply {
                                put("from", item.from)
                                put("address", item.address)
                                put("subject", item.subject)
                                put("unsubscribe", item.unsubscribe ?: "")
                                put("uid", item.uid)
                                put("date", item.date)
                            })
                        }
                    })
                })
            }
            file(context).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
