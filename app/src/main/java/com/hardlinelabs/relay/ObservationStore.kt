package com.hardlinelabs.relay

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.hardlinelabs.relay.core.RadioEvent
import org.json.JSONObject

/** Bounded, private metadata history. Only explicitly selected fields can reach disk/export. */
class ObservationStore(context: Context, name: String = "radio-observations.db") :
    SQLiteOpenHelper(context, name, null, 3) {
    var radio: Int = 0
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, metadata TEXT NOT NULL, radio INTEGER NOT NULL DEFAULT 0)")
        createRadios(db)
        createSurveys(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE events ADD COLUMN radio INTEGER NOT NULL DEFAULT 0")
            createRadios(db)
        }
        if (oldVersion < 3) {
            createSurveys(db)
            db.rawQuery("SELECT radio, snapshot FROM radios", null).use { c ->
                while (c.moveToNext()) {
                    val value = JSONObject(c.getString(1))
                    writeSurveys(db, c.getInt(0), value.optJSONArray("surveys"))
                    value.remove("surveys")
                    db.update("radios", ContentValues().apply { put("snapshot", value.toString()) }, "radio = ?", arrayOf(c.getInt(0).toString()))
                }
            }
        }
    }
    private fun createSurveys(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE surveys (radio INTEGER NOT NULL, id TEXT NOT NULL, started INTEGER NOT NULL, record TEXT NOT NULL, PRIMARY KEY (radio, id))")
    }
    private fun writeSurveys(db: SQLiteDatabase, node: Int, records: org.json.JSONArray?) {
        if (records == null) return
        for (i in 0 until minOf(records.length(), 20)) {
            val record = records.getJSONObject(i)
            db.insertWithOnConflict("surveys", null, ContentValues().apply {
                put("radio", node); put("id", record.getString("id")); put("started", record.getLong("started")); put("record", record.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        db.execSQL("DELETE FROM surveys WHERE radio = ? AND id NOT IN (SELECT id FROM surveys WHERE radio = ? ORDER BY started DESC LIMIT 20)", arrayOf(node, node))
    }
    private fun createRadios(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE radios (radio INTEGER PRIMARY KEY, touched INTEGER NOT NULL, snapshot TEXT NOT NULL)")
    }
    @Synchronized fun saveSnapshot(value: JSONObject) {
        if (radio == 0) return
        val db = writableDatabase
        val details = JSONObject(value.toString()).apply { remove("surveys") }
        db.beginTransaction()
        try {
            db.insertWithOnConflict("radios", null, ContentValues().apply {
                put("radio", radio); put("touched", System.currentTimeMillis()); put("snapshot", details.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            writeSurveys(db, radio, value.optJSONArray("surveys"))
            db.execSQL("DELETE FROM radios WHERE radio NOT IN (SELECT radio FROM radios ORDER BY touched DESC LIMIT 8)")
            db.execSQL("DELETE FROM events WHERE radio NOT IN (SELECT radio FROM radios)")
            db.execSQL("DELETE FROM surveys WHERE radio NOT IN (SELECT radio FROM radios)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun snapshot(): JSONObject? = readableDatabase.rawQuery(
        "SELECT snapshot FROM radios WHERE radio = ?", arrayOf(radio.toString())).use { c ->
        if (!c.moveToFirst()) null else JSONObject(c.getString(0)).apply {
            put("surveys", org.json.JSONArray().apply {
                readableDatabase.rawQuery("SELECT record FROM surveys WHERE radio = ? ORDER BY started DESC LIMIT 20", arrayOf(radio.toString())).use { rows ->
                    while (rows.moveToNext()) put(JSONObject(rows.getString(0)))
                }
            })
        }
    }
    @Synchronized fun latestRadio(): Int = readableDatabase.rawQuery("SELECT radio FROM radios ORDER BY touched DESC LIMIT 1", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    @Synchronized fun append(event: RadioEvent) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply {
            put("radio", radio); put("time", event.time); put("metadata", encode(event).toString())
        })
        trim()
    }

    @Synchronized fun trim() {
        writableDatabase.delete("events", "time < ?", arrayOf((System.currentTimeMillis() - 7 * 86_400_000L).toString()))
        writableDatabase.execSQL("DELETE FROM events WHERE radio = $radio AND sequence NOT IN (SELECT sequence FROM events WHERE radio = $radio ORDER BY sequence DESC LIMIT 5000)")
    }

    @Synchronized fun read(): List<RadioEvent> {
        trim()
        return readableDatabase.rawQuery("SELECT metadata FROM events WHERE radio = ? ORDER BY sequence DESC LIMIT 5000", arrayOf(radio.toString())).use { c ->
            buildList { while (c.moveToNext()) runCatching { decode(JSONObject(c.getString(0))) }.getOrNull()?.let(::add) }
        }.reversed()
    }

    @Synchronized fun clear() { writableDatabase.delete("events", "radio = ?", arrayOf(radio.toString())) }

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
