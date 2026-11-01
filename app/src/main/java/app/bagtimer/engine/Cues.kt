package app.bagtimer.engine

/** Sounds the session asks the audio layer to play. */
sealed class Cue {
    data class Start(val kind: Kind) : Cue()
    object Pip : Cue()
    object Finish : Cue()
}

/**
 * Decides which cues to fire as time moves forward.
 * Feed it every tick; it fires each cue once.
 */
class CueTracker {
    private var lastSegment = -1
    private var lastPipSecond = -1
    private var finished = false

    /** Call after a jump (skip, back, reset) so the new interval announces itself once. */
    fun reset() {
        lastSegment = -1
        lastPipSecond = -1
        finished = false
    }

    fun update(p: Position): List<Cue> {
        if (p.finished) {
            if (finished) return emptyList()
            finished = true
            return listOf(Cue.Finish)
        }
        finished = false
        val out = ArrayList<Cue>(2)
        if (p.segment.index != lastSegment) {
            lastSegment = p.segment.index
            lastPipSecond = -1
            out += Cue.Start(p.segment.kind)
        }
        val secLeft = ((p.remainingMs + 999) / 1000).toInt()
        if (secLeft in 1..3 && p.segment.durationMs > 3000 && secLeft != lastPipSecond &&
            p.remainingMs < p.segment.durationMs - 500
        ) {
            lastPipSecond = secLeft
            out += Cue.Pip
        }
        return out
    }
}
