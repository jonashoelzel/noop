package com.noop.lift

import com.noop.analytics.LiftMetrics

/**
 * Personal records for one exercise: the heaviest working set, the best estimated one-rep max and the
 * biggest single set (weight x reps).
 *
 * Only working sets with a weight and at least one rep count. A warm-up is excluded for the same reason it
 * is excluded from volume: it was never an attempt. The 1RM estimate is [LiftMetrics.estimatedOneRepMaxKg],
 * the same Epley formula the session detail shows, so a record and the detail never disagree about what a
 * set was worth.
 *
 * A set is only a RECORD when there is an earlier best to beat. The first time an exercise is logged sets
 * the baseline instead: calling every first set a record would make the badge meaningless.
 */
object LiftRecords {
    enum class Kind { HEAVIEST_WEIGHT, BEST_ONE_REP_MAX, BEST_SET_VOLUME }

    /** The bests so far. A field stays null until a qualifying set supplies it. */
    data class Best(
        val weightKg: Double? = null,
        val oneRepMaxKg: Double? = null,
        val setVolumeKg: Double? = null,
    ) {
        fun value(kind: Kind): Double? = when (kind) {
            Kind.HEAVIEST_WEIGHT -> weightKg
            Kind.BEST_ONE_REP_MAX -> oneRepMaxKg
            Kind.BEST_SET_VOLUME -> setVolumeKg
        }

        /** These bests with one more set folded in. */
        fun adding(weightKg: Double?, reps: Int?, isWarmup: Boolean): Best {
            if (!counts(weightKg, reps, isWarmup)) return this
            val w = weightKg!!
            val r = reps!!
            return Best(
                weightKg = maxOrSelf(this.weightKg, w),
                oneRepMaxKg = LiftMetrics.estimatedOneRepMaxKg(w, r)?.let { maxOrSelf(oneRepMaxKg, it) } ?: oneRepMaxKg,
                setVolumeKg = maxOrSelf(setVolumeKg, w * r),
            )
        }

        private fun maxOrSelf(current: Double?, candidate: Double) = if (current == null || candidate > current) candidate else current
    }

    /** A set that can hold a record: a working set with a weight and at least one rep. */
    fun counts(weightKg: Double?, reps: Int?, isWarmup: Boolean): Boolean =
        !isWarmup && weightKg != null && weightKg > 0 && reps != null && reps > 0

    /** What one set is worth under [kind], or null when it cannot hold that record. */
    fun value(kind: Kind, weightKg: Double?, reps: Int?, isWarmup: Boolean): Double? {
        if (!counts(weightKg, reps, isWarmup)) return null
        return when (kind) {
            Kind.HEAVIEST_WEIGHT -> weightKg
            Kind.BEST_ONE_REP_MAX -> LiftMetrics.estimatedOneRepMaxKg(weightKg, reps)
            Kind.BEST_SET_VOLUME -> weightKg!! * reps!!
        }
    }

    /** One set as the record rules see it; [key] identifies it to the caller. */
    data class Candidate<K>(val key: K, val weightKg: Double?, val reps: Int?, val isWarmup: Boolean)

    /**
     * Which of [sets] (one exercise, one session) set a record against [prior], the bests from before the
     * session. Each kind goes to at most ONE set, the session's best for it, and only when that beats the
     * prior best outright: a tie is not a record. Ties within the session go to the first set handed in.
     * Order-independent otherwise, so typing numbers into an earlier row never moves a badge sideways.
     */
    fun <K> recordsIn(sets: List<Candidate<K>>, prior: Best?): Map<K, Set<Kind>> {
        if (prior == null) return emptyMap()
        val out = LinkedHashMap<K, MutableSet<Kind>>()
        for (kind in Kind.entries) {
            val before = prior.value(kind) ?: continue
            var bestKey: K? = null
            var bestValue = before
            for (s in sets) {
                val v = value(kind, s.weightKg, s.reps, s.isWarmup) ?: continue
                if (v > bestValue) {
                    bestValue = v
                    bestKey = s.key
                }
            }
            bestKey?.let { out.getOrPut(it) { LinkedHashSet() }.add(kind) }
        }
        return out
    }
}
