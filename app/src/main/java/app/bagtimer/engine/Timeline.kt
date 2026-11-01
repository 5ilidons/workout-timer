package app.bagtimer.engine

/** Kind of interval. Each kind has its own colour and start sound. */
enum class Kind(val label: String) {
    PREP("Get ready"),
    WORK("Work"),
    REST("Rest"),
    BAG("Bag");

    fun next(): Kind = when (this) {
        WORK -> REST
        REST -> BAG
        BAG -> WORK
        PREP -> WORK
    }
}

/** One step inside a round. */
data class Step(val kind: Kind, val seconds: Int)

/** A saved workout: a list of steps repeated for a number of rounds. */
data class Workout(
    val id: String,
    val name: String,
    val rounds: Int,
    val steps: List<Step>,
    val prepSeconds: Int = 10,
) {
    val roundSeconds: Int get() = steps.sumOf { it.seconds }
    val totalSeconds: Int get() = prepSeconds + roundSeconds * rounds
}

/** One interval on the flattened timeline. */
data class Segment(
    val index: Int,
    val kind: Kind,
    val round: Int,      // 1-based, 0 for the prep segment
    val stepIndex: Int,  // index inside the round, -1 for prep
    val startMs: Long,
    val durationMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/** Where the session is at a given elapsed time. */
data class Position(
    val segment: Segment,
    val remainingMs: Long,
    val totalRemainingMs: Long,
    val finished: Boolean,
)

/** The whole workout flattened into consecutive segments. Pure, no Android code. */
class Timeline(val workout: Workout) {
    val segments: List<Segment>
    val totalMs: Long

    init {
        val list = ArrayList<Segment>()
        var t = 0L
        if (workout.prepSeconds > 0) {
            list += Segment(0, Kind.PREP, 0, -1, 0, workout.prepSeconds * 1000L)
            t += workout.prepSeconds * 1000L
        }
        for (r in 1..workout.rounds) {
            workout.steps.forEachIndexed { i, s ->
                if (s.seconds > 0) {
                    list += Segment(list.size, s.kind, r, i, t, s.seconds * 1000L)
                    t += s.seconds * 1000L
                }
            }
        }
        require(list.isNotEmpty()) { "Workout has no intervals" }
        segments = list
        totalMs = t
    }

    fun positionAt(elapsedMs: Long): Position {
        val e = elapsedMs.coerceAtLeast(0)
        if (e >= totalMs) {
            return Position(segments.last(), 0, 0, finished = true)
        }
        // Binary search for the segment that contains e.
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segments[mid].startMs <= e) lo = mid else hi = mid - 1
        }
        val seg = segments[lo]
        return Position(seg, seg.endMs - e, totalMs - e, finished = false)
    }

    /** Elapsed time to jump to for "Skip". */
    fun skipTarget(elapsedMs: Long): Long {
        val p = positionAt(elapsedMs)
        return if (p.finished) totalMs else p.segment.endMs
    }

    /** Elapsed time to jump to for "Back": restart this interval, or go to the previous one in the first 2 s. */
    fun backTarget(elapsedMs: Long): Long {
        val p = positionAt(elapsedMs)
        val seg = p.segment
        val into = elapsedMs.coerceAtMost(totalMs) - seg.startMs
        return if (into > 2000 || seg.index == 0) seg.startMs else segments[seg.index - 1].startMs
    }

    fun next(segment: Segment): Segment? = segments.getOrNull(segment.index + 1)
}

/** Formats seconds as m:ss, rounding up so the display shows 0:01 until the end. */
fun formatClock(ms: Long): String {
    val s = ((ms + 999) / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Built-in presets created on first launch. */
object Presets {
    fun defaults(): List<Workout> = listOf(
        Workout(
            "bag-ladder", "Bag Ladder", 6,
            listOf(
                Step(Kind.WORK, 120), Step(Kind.REST, 30), Step(Kind.BAG, 30),
                Step(Kind.WORK, 60), Step(Kind.REST, 30), Step(Kind.BAG, 30),
                Step(Kind.WORK, 30), Step(Kind.REST, 30), Step(Kind.BAG, 30),
            ),
        ),
        Workout(
            "ladder-432", "4-3-2 Ladder", 4,
            listOf(
                Step(Kind.WORK, 240), Step(Kind.REST, 90), Step(Kind.BAG, 30),
                Step(Kind.WORK, 180), Step(Kind.REST, 60), Step(Kind.BAG, 30),
                Step(Kind.WORK, 120), Step(Kind.REST, 30), Step(Kind.BAG, 30),
            ),
        ),
        Workout("tabata", "Tabata", 8, listOf(Step(Kind.WORK, 20), Step(Kind.REST, 10))),
        Workout("emom-10", "EMOM 10 min", 10, listOf(Step(Kind.WORK, 60))),
        Workout("amrap-12", "AMRAP 12 min", 1, listOf(Step(Kind.WORK, 720))),
    )
}
