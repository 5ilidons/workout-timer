package app.bagtimer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.bagtimer.engine.CueTracker
import app.bagtimer.engine.Position
import app.bagtimer.engine.Timeline
import app.bagtimer.engine.Workout

/**
 * The one running workout, shared by the screen, the notification and PiP.
 * Time comes from elapsedRealtime, so the clock stays right even if a tick runs late.
 */
object Session {
    enum class State { IDLE, RUNNING, PAUSED, FINISHED }

    fun interface Listener { fun onTick() }

    var timeline: Timeline? = null
        private set
    var state = State.IDLE
        private set

    private var baseElapsed = 0L      // elapsed ms banked before the current run
    private var runStartedAt = 0L     // elapsedRealtime when the current run started
    private val cues = CueTracker()
    private var beeper: Beeper? = null
    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (state == State.RUNNING) main.postDelayed(this, 50)
        }
    }

    val elapsedMs: Long
        get() = if (state == State.RUNNING) baseElapsed + (SystemClock.elapsedRealtime() - runStartedAt) else baseElapsed

    fun position(): Position? = timeline?.positionAt(elapsedMs)

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    fun start(context: Context, workout: Workout) {
        appContext = context.applicationContext
        stopTicking()
        timeline = Timeline(workout)
        state = State.IDLE
        baseElapsed = 0
        cues.reset()
        if (beeper == null) beeper = Beeper(appContext)
        resume()
        appContext.startForegroundService(Intent(appContext, TimerService::class.java))
    }

    fun resume() {
        if (timeline == null || state == State.RUNNING) return
        if (state == State.FINISHED) { baseElapsed = 0; cues.reset() }
        runStartedAt = SystemClock.elapsedRealtime()
        state = State.RUNNING
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    fun pause() {
        if (state != State.RUNNING) return
        baseElapsed = elapsedMs
        state = State.PAUSED
        stopTicking()
        notifyListeners()
    }

    fun toggle() = if (state == State.RUNNING) pause() else resume()

    fun skip() = jump { it.skipTarget(elapsedMs) }
    fun back() = jump { it.backTarget(elapsedMs) }

    private fun jump(target: (Timeline) -> Long) {
        val t = timeline ?: return
        val to = target(t)
        if (state == State.RUNNING) runStartedAt = SystemClock.elapsedRealtime()
        if (state == State.FINISHED) state = State.PAUSED
        baseElapsed = to
        cues.reset()
        if (state == State.RUNNING) tick() else notifyListeners()
    }

    fun stop() {
        stopTicking()
        state = State.IDLE
        timeline = null
        baseElapsed = 0
        notifyListeners()
    }

    private fun stopTicking() = main.removeCallbacks(ticker)

    private fun tick() {
        val t = timeline ?: return
        val p = t.positionAt(elapsedMs)
        for (c in cues.update(p)) beeper?.play(c)
        if (p.finished) {
            baseElapsed = t.totalMs
            state = State.FINISHED
            stopTicking()
        }
        notifyListeners()
    }

    private fun notifyListeners() {
        for (l in listeners.toList()) l.onTick()
    }
}
