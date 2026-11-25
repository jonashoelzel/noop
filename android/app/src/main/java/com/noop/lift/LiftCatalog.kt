package com.noop.lift

import android.content.Context
import com.noop.data.LiftMuscle
import org.json.JSONObject

/**
 * The bundled exercise library: about 680 resistance exercises with their muscles and equipment, so a new
 * user picks "Barbell Bench Press" instead of typing it and classifying it by hand.
 *
 * Built by `Tools/lift_catalog/build_lift_catalog.py` from free-exercise-db (public domain). Names stay in
 * English: they are the source's own names and most gyms use them untranslated. Picking an entry copies its
 * name and muscles into the user's own exercise vocabulary, exactly as typing them would, so the library
 * adds nothing to the database and nothing to what a session stores.
 */
object LiftCatalog {
    enum class Equipment(val raw: String) {
        BARBELL("barbell"),
        DUMBBELL("dumbbell"),
        CABLE("cable"),
        MACHINE("machine"),
        BODYWEIGHT("bodyweight"),
        KETTLEBELL("kettlebell"),
        BAND("band"),
        EZ_BAR("ezBar"),
        OTHER("other"),
        ;

        companion object {
            fun fromRaw(raw: String?): Equipment = entries.firstOrNull { it.raw == raw } ?: OTHER
        }
    }

    data class Entry(
        val name: String,
        val primary: LiftMuscle,
        val secondaries: List<LiftMuscle>,
        val equipment: Equipment,
    )

    const val ASSET = "lift/exercise_catalog.json"

    @Volatile private var cached: List<Entry>? = null

    /** The library, read from the APK once per process. Empty if the asset is missing or unreadable. */
    fun load(context: Context): List<Entry> {
        cached?.let { return it }
        val parsed = runCatching {
            context.applicationContext.assets.open(ASSET).use { parse(it.readBytes().decodeToString()) }
        }.getOrDefault(emptyList())
        cached = parsed
        return parsed
    }

    /** Parse the asset's JSON. An entry whose primary muscle is not a known [LiftMuscle] is skipped. */
    fun parse(json: String): List<Entry> {
        val rows = JSONObject(json).getJSONArray("exercises")
        val out = ArrayList<Entry>(rows.length())
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val primary = LiftMuscle.fromRaw(o.optString("p")) ?: continue
            val secondaries = o.optJSONArray("s")?.let { arr ->
                (0 until arr.length()).mapNotNull { LiftMuscle.fromRaw(arr.optString(it)) }
            }.orEmpty().filter { it != primary }.distinct()
            out += Entry(o.getString("n"), primary, secondaries, Equipment.fromRaw(o.optString("e")))
        }
        return out
    }

    /**
     * Entries matching every typed word (in any order, anywhere in the name), an optional muscle [region]
     * (matched on the PRIMARY muscle, which is what the exercise trains) and optional [equipment].
     * Names that START with the query rank first, then the rest alphabetically as stored.
     */
    fun search(
        entries: List<Entry>,
        query: String,
        region: LiftMuscle.Region? = null,
        muscle: LiftMuscle? = null,
        equipment: Equipment? = null,
    ): List<Entry> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val q = query.trim().lowercase()
        val hits = entries.filter { e ->
            (region == null || e.primary.region == region) &&
                (muscle == null || e.primary == muscle) &&
                (equipment == null || e.equipment == equipment) &&
                words.all { e.name.lowercase().contains(it) }
        }
        if (q.isEmpty()) return hits
        val (prefix, rest) = hits.partition { it.name.lowercase().startsWith(q) }
        return prefix + rest
    }
}
