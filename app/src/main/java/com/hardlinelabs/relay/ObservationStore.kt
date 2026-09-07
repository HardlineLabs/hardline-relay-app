package com.hardlinelabs.relay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hardlinelabs.relay.core.RadioEvent
import org.json.JSONObject

/** Bounded, private metadata history. Only explicitly selected fields can reach disk/export. */
class ObservationStore(context: Context, name: String = "radio-observations.db") :
    SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, metadata TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun append(event: RadioEvent) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("time", event.time); put("metadata", encode(event).toString())
        })
        trim()
    }

    @Synchronized fun trim() {
        writableDatabase.delete("events", "time < ?", arrayOf((System.currentTimeMillis() - 7 * 86_400_000L).toString()))
        writableDatabase.execSQL("DELETE FROM events WHERE sequence NOT IN (SELECT sequence FROM events ORDER BY sequence DESC LIMIT 5000)")
    }

    @Synchronized fun read(): List<RadioEvent> {
        trim()
        return readableDatabase.rawQuery("SELECT metadata FROM events ORDER BY sequence DESC LIMIT 5000", null).use { c ->
            buildList { while (c.moveToNext()) runCatching { decode(JSONObject(c.getString(0))) }.getOrNull()?.let(::add) }
        }.reversed()
    }

    @Synchronized fun clear() { writableDatabase.delete("events", null, null) }

    companion object {
        fun encode(e: RadioEvent) = JSONObject().apply {
            put("time", e.time); put("direction", e.direction); put("kind", e.kind)
            put("source", e.source); put("destination", e.destination); put("packetId", e.packetId)
            put("channel", e.channel); put("size", e.size); put("rssi", e.rssi); put("snr", e.snr)
            put("hops", e.hops); put("hopLimit", e.hopLimit); put("relay", e.relay); put("mqtt", e.mqtt)
            put("distance", e.distance); put("positionTime", e.positionTime); put("context", e.context)
            put("survey", e.survey); put("outcome", e.outcome); put("note", e.note)
            put("elapsed", e.elapsed); put("transport", e.transport)
        }
        fun decode(j: JSONObject) = RadioEvent(
            j.getLong("time"), j.getString("direction"), j.getString("kind"), j.optString("source"),
            j.optString("destination"), j.optInt("packetId"), j.optInt("channel", -1), j.optInt("size"),
            if (j.has("rssi")) j.getInt("rssi") else null,
            if (j.has("snr")) j.getDouble("snr").toFloat() else null,
            if (j.has("hops")) j.getInt("hops") else null,
            if (j.has("hopLimit")) j.getInt("hopLimit") else null,
            if (j.has("relay")) j.getInt("relay") else null, j.optBoolean("mqtt"),
            if (j.has("distance")) j.getDouble("distance") else null,
            if (j.has("positionTime")) j.getLong("positionTime") else null,
            j.optString("context"), j.optString("survey"), j.optString("outcome"), j.optString("note"),
            if (j.has("elapsed")) j.getLong("elapsed") else null, j.optString("transport", "Unknown")
        )
    }
}
