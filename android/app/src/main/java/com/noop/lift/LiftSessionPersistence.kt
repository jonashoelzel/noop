package com.noop.lift

import android.content.Context
import android.content.SharedPreferences
import com.noop.data.LiftMuscle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Crash-safety for an in-flight gym session. Kotlin twin of `Strand/Data/LiftSessionPersistence.swift`.
 *
 * The session is written on every change and rehydrated on launch, so a crash, a phone call, a flat
 * battery or Android reclaiming the process mid-workout costs nothing. The codec is pure (no
 * SharedPreferences of its own) so the round trip is testable on the JVM. Unix seconds throughout: an
 * absolute instant survives suspension, time-zone changes and a relaunch, and keeps a rest countdown honest.
 *
 * The snapshot lives in its own SharedPreferences file and is not part of `.noopbak`, so its JSON need not
 * match Apple's byte for byte. The field names follow the Swift `Snapshot` anyway, so the two read alike.
 */
object LiftSessionPersistence {

    data class PendingValue(val exerciseIndex: Int, val setIndex: Int, val weightKg: Double?, val reps: Int?, val rpe: Double?)

    /** What a user typed into a set that has not happened yet. All optional. */
    data class PendingSetValues(val weightKg: Double? = null, val reps: Int? = null, val rpe: Double? = null) {
        val isEmpty: Boolean get() = weightKg == null && reps == null && rpe == null
    }

    /** The durable shape of an in-flight session. */
    data class Snapshot(
        val startSec: Long,
        val programId: String?,
        val programName: String?,
        /** The plan, flattened at start, so editing the program mid-session changes nothing being run. */
        val plan: List<LiftPlanItem>,
        val stage: LiftStage,
        val sets: List<LiftRecordedSet>,
        val stageStartedAt: Long,
        /** Numbers typed into sets not yet performed, and warm-ups marked in advance. */
        val pendingValues: List<PendingValue> = emptyList(),
        val pendingWarmups: List<LiftSlot> = emptyList(),
    )

    private const val PREFS = "noop.liftSession"
    const val KEY = "noop.activeLiftSession"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // MARK: - Codec

    fun encode(s: Snapshot): String {
        val o = JSONObject()
        o.put("startSec", s.startSec)
        o.putOpt("programId", s.programId)
        o.putOpt("programName", s.programName)
        o.put(
            "plan",
            JSONArray(
                s.plan.map { p ->
                    JSONObject().apply {
                        put("exercise", p.exercise)
                        putOpt("primaryMuscle", p.primaryMuscle?.name)
                        put("secondaryMuscles", JSONArray(p.secondaryMuscles.map { it.name }))
                        put("targetSets", p.targetSets)
                        put("restSec", p.restSec)
                        putOpt("targetRepsLow", p.targetRepsLow)
                        putOpt("targetRepsHigh", p.targetRepsHigh)
                        putOpt("targetRpe", p.targetRpe)
                        putOpt("targetWeightKg", p.targetWeightKg)
                        putOpt("note", p.note)
                        putOpt("programItemId", p.programItemId)
                        if (p.addedInSession) put("addedInSession", true)
                    }
                },
            ),
        )
        o.put("stage", box(s.stage))
        o.put(
            "sets",
            JSONArray(
                s.sets.map { r ->
                    JSONObject().apply {
                        put("exerciseIndex", r.exerciseIndex)
                        put("setIndex", r.setIndex)
                        putOpt("weightKg", r.weightKg)
                        putOpt("reps", r.reps)
                        putOpt("rpe", r.rpe)
                        put("isWarmup", r.isWarmup)
                        put("startTs", r.startTs)
                        put("endTs", r.endTs)
                        putOpt("restSec", r.restSec)
                    }
                },
            ),
        )
        o.put("stageStartedAt", s.stageStartedAt)
        o.put(
            "pendingValues",
            JSONArray(
                s.pendingValues.map { p ->
                    JSONObject().apply {
                        put("exerciseIndex", p.exerciseIndex)
                        put("setIndex", p.setIndex)
                        putOpt("weightKg", p.weightKg)
                        putOpt("reps", p.reps)
                        putOpt("rpe", p.rpe)
                    }
                },
            ),
        )
        o.put(
            "pendingWarmups",
            JSONArray(s.pendingWarmups.map { JSONObject().put("exerciseIndex", it.exerciseIndex).put("setIndex", it.setIndex) }),
        )
        return o.toString()
    }

    /**
     * Decode, bound-checking the untrusted persisted values. Null for null/garbage/empty input, an
     * implausible start, or an empty plan, so a corrupt write reads as "no session in flight" rather than
     * reviving a screen the user cannot get out of.
     */
    fun decode(raw: String?, nowSec: Long = System.currentTimeMillis() / 1000): Snapshot? {
        if (raw.isNullOrEmpty()) return null
        return runCatching {
            val o = JSONObject(raw)
            val startSec = o.getLong("startSec")
            if (startSec <= 1_000_000_000L || startSec >= nowSec + 86_400) return null
            val plan = o.getJSONArray("plan").objects().map { p ->
                LiftPlanItem(
                    exercise = p.getString("exercise"),
                    // Unknown tokens are dropped, never fatal: a newer build's snapshot must not strand
                    // a session in an older one.
                    primaryMuscle = LiftMuscle.fromRaw(p.optStringOrNull("primaryMuscle")),
                    secondaryMuscles = p.optJSONArray("secondaryMuscles")?.strings()?.mapNotNull { LiftMuscle.fromRaw(it) }
                        ?: emptyList(),
                    targetSets = p.getInt("targetSets"),
                    restSec = p.getInt("restSec"),
                    targetRepsLow = p.optIntOrNull("targetRepsLow"),
                    targetRepsHigh = p.optIntOrNull("targetRepsHigh"),
                    targetRpe = p.optDoubleOrNull("targetRpe"),
                    targetWeightKg = p.optDoubleOrNull("targetWeightKg"),
                    note = p.optStringOrNull("note"),
                    programItemId = p.optStringOrNull("programItemId"),
                    addedInSession = p.optBoolean("addedInSession", false),
                )
            }
            if (plan.isEmpty()) return null
            Snapshot(
                startSec = startSec,
                programId = o.optStringOrNull("programId"),
                programName = o.optStringOrNull("programName"),
                plan = plan,
                stage = unbox(o.optJSONObject("stage")),
                sets = o.getJSONArray("sets").objects().map { r ->
                    LiftRecordedSet(
                        exerciseIndex = r.getInt("exerciseIndex"),
                        setIndex = r.getInt("setIndex"),
                        weightKg = r.optDoubleOrNull("weightKg"),
                        reps = r.optIntOrNull("reps"),
                        rpe = r.optDoubleOrNull("rpe"),
                        isWarmup = r.optBoolean("isWarmup", false),
                        startTs = r.getLong("startTs"),
                        endTs = r.getLong("endTs"),
                        restSec = r.optIntOrNull("restSec"),
                    )
                },
                stageStartedAt = o.getLong("stageStartedAt"),
                pendingValues = o.optJSONArray("pendingValues")?.objects()?.map { p ->
                    PendingValue(
                        p.getInt("exerciseIndex"), p.getInt("setIndex"),
                        p.optDoubleOrNull("weightKg"), p.optIntOrNull("reps"), p.optDoubleOrNull("rpe"),
                    )
                } ?: emptyList(),
                pendingWarmups = o.optJSONArray("pendingWarmups")?.objects()?.map {
                    LiftSlot(it.getInt("exerciseIndex"), it.getInt("setIndex"))
                } ?: emptyList(),
            )
        }.getOrNull()
    }

    // MARK: - SharedPreferences

    fun store(prefs: SharedPreferences, snapshot: Snapshot?) {
        if (snapshot == null) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, encode(snapshot)).apply()
    }

    fun load(prefs: SharedPreferences): Snapshot? = decode(prefs.getString(KEY, null))

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(KEY).apply()
    }

    // MARK: - Engine bridge

    fun snapshot(
        engine: LiftSessionEngine,
        programId: String?,
        programName: String?,
        pendingValues: Map<LiftSlot, PendingSetValues>,
        pendingWarmups: Set<LiftSlot>,
    ): Snapshot {
        // Sorted so the encoding is stable: a map and a set have no order.
        val order = compareBy<LiftSlot>({ it.exerciseIndex }, { it.setIndex })
        return Snapshot(
            startSec = engine.startTs,
            programId = programId,
            programName = programName,
            plan = engine.plan,
            stage = engine.stage,
            sets = engine.sets,
            stageStartedAt = engine.stageStartedAt,
            pendingValues = pendingValues.keys.sortedWith(order).map { slot ->
                val v = pendingValues.getValue(slot)
                PendingValue(slot.exerciseIndex, slot.setIndex, v.weightKg, v.reps, v.rpe)
            },
            pendingWarmups = pendingWarmups.sortedWith(order),
        )
    }

    fun pendingValues(s: Snapshot): Map<LiftSlot, PendingSetValues> =
        s.pendingValues.associate { LiftSlot(it.exerciseIndex, it.setIndex) to PendingSetValues(it.weightKg, it.reps, it.rpe) }

    fun pendingWarmups(s: Snapshot): Set<LiftSlot> = s.pendingWarmups.toSet()

    fun engine(s: Snapshot): LiftSessionEngine =
        LiftSessionEngine.restoring(s.plan, s.startSec, s.stage, s.sets, s.stageStartedAt)

    private fun box(stage: LiftStage): JSONObject = when (stage) {
        LiftStage.Warmup -> JSONObject().put("kind", "warmup")
        is LiftStage.Working -> JSONObject().put("kind", "working").put("item", stage.slot.exerciseIndex).put("set", stage.slot.setIndex)
        is LiftStage.Resting -> JSONObject().put("kind", "resting").put("item", stage.slot.exerciseIndex)
            .put("set", stage.slot.setIndex).put("endsAt", stage.endsAt)
        LiftStage.Finished -> JSONObject().put("kind", "finished")
    }

    /**
     * Anything unrecognised falls back to the warm-up. That is a real recovery: every completed set is in
     * `sets` regardless, so only the cursor is forgotten and the next tap resumes at the first set left.
     */
    private fun unbox(o: JSONObject?): LiftStage {
        if (o == null) return LiftStage.Warmup
        val item = o.optIntOrNull("item")
        val set = o.optIntOrNull("set")
        return when (o.optString("kind")) {
            "working" -> if (item != null && set != null) LiftStage.Working(LiftSlot(item, set)) else LiftStage.Warmup
            "resting" -> {
                val endsAt = if (o.has("endsAt") && !o.isNull("endsAt")) o.getLong("endsAt") else null
                if (item != null && set != null && endsAt != null) LiftStage.Resting(LiftSlot(item, set), endsAt) else LiftStage.Warmup
            }
            "finished" -> LiftStage.Finished
            else -> LiftStage.Warmup
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
    private fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
}
