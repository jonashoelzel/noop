package com.noop.garmin

/**
 * Pure skip rule for one Garmin Connect activity against the workouts already stored. Twin of the Swift
 * `GarminImportRule`; both platforms pin the same test table.
 *
 *  - [Decision.ALREADY_IMPORTED]: a stored `garmin-connect` row starts within 2 s of the activity.
 *  - [Decision.SENT_FROM_NOOP]: a stored `manual` row (a workout NOOP recorded) overlaps the activity by
 *    more than half of the shorter window, regardless of sport. A run NOOP recorded and the user uploaded
 *    to Garmin comes back through the activity list; it must not be imported a second time.
 *  - otherwise [Decision.IMPORT].
 */
object GarminImportRule {

    enum class Decision { IMPORT, ALREADY_IMPORTED, SENT_FROM_NOOP }

    /** A stored workout's window and source, all the rule looks at. */
    data class Existing(val startTs: Long, val endTs: Long, val source: String)

    /** Start-time tolerance for "this exact activity is already stored". */
    const val SAME_START_TOLERANCE_S = 2L

    fun decide(activityStart: Long, activityEnd: Long, existing: List<Existing>): Decision {
        if (existing.any {
                it.source.lowercase() == GarminConnect.SOURCE_ID &&
                    kotlin.math.abs(it.startTs - activityStart) <= SAME_START_TOLERANCE_S
            }
        ) return Decision.ALREADY_IMPORTED
        if (existing.any {
                it.source.lowercase() == "manual" &&
                    overlapsMajority(activityStart, activityEnd, it.startTs, it.endTs)
            }
        ) return Decision.SENT_FROM_NOOP
        return Decision.IMPORT
    }

    /**
     * The windows overlap by more than half of the shorter one: the same arithmetic as the time half of
     * [com.noop.ui.WorkoutEditing.sameActivity].
     */
    internal fun overlapsMajority(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean {
        val overlap = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
        if (overlap <= 0) return false
        val shorter = maxOf(1L, minOf(aEnd - aStart, bEnd - bStart))
        return overlap.toDouble() > 0.5 * shorter.toDouble()
    }
}
