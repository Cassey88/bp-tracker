package nz.cassey.bptracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "bp.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE readings (
                id     INTEGER PRIMARY KEY AUTOINCREMENT,
                date   TEXT    NOT NULL,
                time   TEXT    NOT NULL,
                state  TEXT    NOT NULL,
                sys    INTEGER NOT NULL,
                dia    INTEGER NOT NULL,
                pulse  INTEGER NOT NULL,
                rating TEXT    NOT NULL,
                photo  TEXT,
                UNIQUE (date, time)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        // Single schema version so far; nothing to migrate.
    }

    fun all(): List<Reading> {
        val out = ArrayList<Reading>()
        readableDatabase.rawQuery(
            "SELECT id,date,time,state,sys,dia,pulse,rating,photo FROM readings " +
                "ORDER BY date DESC, time DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Reading(
                        id = c.getLong(0),
                        date = c.getString(1),
                        time = c.getString(2),
                        state = c.getString(3),
                        sys = c.getInt(4),
                        dia = c.getInt(5),
                        pulse = c.getInt(6),
                        rating = c.getString(7),
                        photo = if (c.isNull(8)) null else c.getString(8)
                    )
                )
            }
        }
        return out
    }

    /**
     * Insert, or update the row that already holds this date+time.
     *
     * Keying on date+time is what makes re-importing your own export a no-op
     * rather than a pile of duplicates. An existing photo is kept when the
     * incoming row does not carry one, so importing a CSV never loses images.
     */
    fun upsert(r: Reading): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("date", r.date)
            put("time", r.time)
            put("state", r.state)
            put("sys", r.sys)
            put("dia", r.dia)
            put("pulse", r.pulse)
            put("rating", r.rating)
            if (r.photo != null) put("photo", r.photo)
        }
        val updated = db.update("readings", cv, "date=? AND time=?", arrayOf(r.date, r.time))
        return if (updated > 0) updated.toLong() else db.insert("readings", null, cv)
    }

    fun delete(id: Long) {
        writableDatabase.delete("readings", "id=?", arrayOf(id.toString()))
    }

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM readings", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
