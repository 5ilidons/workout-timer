package app.bagtimer

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Rational
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.bagtimer.engine.Kind
import app.bagtimer.engine.Step
import app.bagtimer.engine.Workout
import app.bagtimer.engine.formatClock
import java.util.UUID

/**
 * One activity, three screens built in code: workout list, editor, and the running clock.
 * Every control is a focusable button, so the Fire TV remote's D-pad works everywhere.
 */
class MainActivity : Activity(), Session.Listener {

    private enum class Screen { HOME, EDIT, RUN }

    private lateinit var store: PresetStore
    private var screen = Screen.HOME
    private var inPip = false

    // Run screen views
    private var clock: ClockView? = null
    private var controls: View? = null
    private var toggleBtn: Button? = null
    private var stopBtn: Button? = null
    private var stopArmedAt = 0L

    private val hasPip by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }

    // ---------- lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PresetStore(this)
        window.decorView.setBackgroundColor(Palette.GROUND)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        if (Session.state != Session.State.IDLE) showRun() else showHome()
    }

    override fun onStart() {
        super.onStart()
        Session.addListener(this)
        if (screen == Screen.RUN && Session.state == Session.State.IDLE) showHome()
        refreshRun()
    }

    override fun onStop() {
        Session.removeListener(this)
        super.onStop()
    }

    override fun onTick() {
        if (screen == Screen.RUN) {
            if (Session.state == Session.State.IDLE) showHome() else refreshRun()
        }
    }

    @Deprecated("Activity back handling; fine for this framework-only app")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            Screen.HOME -> super.onBackPressed()
            Screen.EDIT -> showHome()
            Screen.RUN -> showHome() // the workout keeps running; "Return to workout" appears on the list
        }
    }

    // Fire TV remote media keys and phone headset buttons.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (screen == Screen.RUN) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> { Session.toggle(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> { Session.skip(); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { Session.back(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- picture-in-picture ----------

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 10))
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(screen == Screen.RUN && Session.state == Session.State.RUNNING)
            b.setSeamlessResizeEnabled(false)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            b.setTitle("Bag Timer")
        }
        return b.build()
    }

    private fun pipActions(): List<RemoteAction> {
        fun pi(action: String, req: Int) = PendingIntent.getService(
            this, 100 + req, Intent(this, TimerService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val running = Session.state == Session.State.RUNNING
        return listOf(
            RemoteAction(
                Icon.createWithResource(this, if (running) R.drawable.ic_pause else R.drawable.ic_play),
                if (running) "Pause" else "Resume", if (running) "Pause" else "Resume",
                pi(TimerService.ACTION_TOGGLE, 1),
            ),
            RemoteAction(Icon.createWithResource(this, R.drawable.ic_skip), "Skip", "Skip interval", pi(TimerService.ACTION_SKIP, 2)),
        )
    }

    private var lastPipRunning: Boolean? = null
    private fun updatePipParams() {
        if (!hasPip) return
        val running = Session.state == Session.State.RUNNING
        if (lastPipRunning == running) return
        lastPipRunning = running
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
    }

    private fun enterPip() {
        if (!hasPip) return
        try {
            enterPictureInPictureMode(pipParams())
        } catch (e: Exception) {
            toast("Picture-in-picture is not available on this device")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home button during a workout: float the clock instead of hiding it.
        if (Build.VERSION.SDK_INT < 31 && hasPip && screen == Screen.RUN && Session.state == Session.State.RUNNING) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        controls?.visibility = if (inPip) View.GONE else View.VISIBLE
        clock?.invalidate()
    }

    // ---------- screens ----------

    private fun showHome() {
        screen = Screen.HOME
        clearRunRefs()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (hasPip) {
            lastPipRunning = null
            try { setPictureInPictureParams(pipParams()) } catch (_: Exception) { }
        }
        val col = column()
        col.addView(title("Bag Timer"))
        col.addView(caption("Pick a workout to start. Edit changes rounds and intervals."))

        if (Session.state != Session.State.IDLE) {
            col.addView(bigButton("Return to running workout", Palette.FG, Palette.INK) { showRun() })
        }

        val presets = store.load()
        var first: View? = null
        for (w in presets) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val start = button("${w.name}\n${summary(w)}", Palette.PANEL, Palette.FG, alignStart = true) { startWorkout(w) }
            start.textSize = 18f
            row.addView(start, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val edit = button("Edit", Palette.PANEL, Palette.MUTED) { showEdit(w, isNew = false) }
            row.addView(edit, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = dp(8) })
            col.addView(row, spaced())
            if (first == null && w.id == store.lastUsedId) first = start
            if (first == null && w === presets.first()) first = start
        }

        col.addView(button("+ New workout", Palette.PANEL, Palette.FG) {
            showEdit(Workout(UUID.randomUUID().toString(), "New workout", 4, listOf(Step(Kind.WORK, 60), Step(Kind.REST, 30))), isNew = true)
        }, spaced(top = 20))
        col.addView(button("Restore built-in workouts", Palette.GROUND, Palette.MUTED) {
            store.restoreDefaults(); showHome(); toast("Built-in workouts restored")
        }, spaced())
        col.addView(caption("Remote: Play/Pause pauses, Fast-forward skips, Rewind goes back. Home floats the clock over other apps when the device supports it."))

        setContentView(scroll(col))
        first?.requestFocus()
    }

    private fun showRun() {
        screen = Screen.RUN
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.GROUND)
        }
        val c = ClockView(this)
        root.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val ctl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = button("Back", Palette.PANEL, Palette.FG) { Session.back() }
        val toggle = button("Pause", Palette.FG, Palette.INK) { Session.toggle() }
        toggle.textSize = 22f
        val skip = button("Skip", Palette.PANEL, Palette.FG) { Session.skip() }
        row1.addView(back, LinearLayout.LayoutParams(0, dp(64), 1f))
        row1.addView(toggle, LinearLayout.LayoutParams(0, dp(64), 2f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        row1.addView(skip, LinearLayout.LayoutParams(0, dp(64), 1f))
        ctl.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val list = button("Workouts", Palette.GROUND, Palette.MUTED) { showHome() }
        val stop = button("Stop", Palette.GROUND, Palette.MUTED) { onStopPressed() }
        row2.addView(list, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (hasPip) {
            val pip = button("Float", Palette.GROUND, Palette.MUTED) { enterPip() }
            row2.addView(pip, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        }
        row2.addView(stop, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        ctl.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        root.addView(ctl)
        setContentView(root)

        clock = c; controls = ctl; toggleBtn = toggle; stopBtn = stop
        ctl.visibility = if (inPip) View.GONE else View.VISIBLE
        lastPipRunning = null
        toggle.requestFocus()
        refreshRun()
    }

    private fun onStopPressed() {
        val now = System.currentTimeMillis()
        if (now - stopArmedAt < 3000) {
            Session.stop()
            showHome()
        } else {
            stopArmedAt = now
            stopBtn?.text = "Press again to stop"
            stopBtn?.postDelayed({ stopBtn?.text = "Stop" }, 3000)
        }
    }

    private fun refreshRun() {
        if (screen != Screen.RUN) return
        clock?.invalidate()
        toggleBtn?.text = when (Session.state) {
            Session.State.RUNNING -> "Pause"
            Session.State.FINISHED -> "Again"
            else -> "Resume"
        }
        updatePipParams()
    }

    private fun clearRunRefs() {
        clock = null; controls = null; toggleBtn = null; stopBtn = null
    }

    private fun startWorkout(w: Workout) {
        store.lastUsedId = w.id
        Session.start(this, w)
        showRun()
    }

    // ---------- editor ----------

    private fun showEdit(original: Workout, isNew: Boolean) {
        screen = Screen.EDIT
        clearRunRefs()
        var name = original.name
        var rounds = original.rounds
        var prep = original.prepSeconds
        val steps = original.steps.toMutableList()

        val col = column()
        col.addView(title(if (isNew) "New workout" else "Edit workout"))

        val nameField = EditText(this).apply {
            setText(name)
            setTextColor(Palette.FG)
            setHintTextColor(Palette.MUTED)
            hint = "Workout name"
            textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            background = boxBg(Palette.PANEL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        col.addView(nameField, spaced())

        val summaryView = caption("")
        val stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun current() = Workout(original.id, nameField.text.toString().ifBlank { "Workout" }, rounds, steps.toList(), prep)
        fun updateSummary() {
            val w = current()
            summaryView.text = "Round ${formatClock(w.roundSeconds * 1000L)} · Total ${formatClock(w.totalSeconds * 1000L)} including ${prep} s prep"
        }

        lateinit var rebuildSteps: () -> Unit
        rebuildSteps = {
            stepsBox.removeAllViews()
            steps.forEachIndexed { i, s ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val kindBtn = button(s.kind.label, Palette.of(s.kind), Palette.INK) {
                    steps[i] = s.copy(kind = s.kind.next()); rebuildSteps(); updateSummary()
                }
                row.addView(kindBtn, LinearLayout.LayoutParams(0, dp(56), 1.3f))
                row.addView(button("−", Palette.PANEL, Palette.FG) {
                    steps[i] = s.copy(seconds = stepDown(s.seconds)); rebuildSteps(); updateSummary()
                }, LinearLayout.LayoutParams(0, dp(56), 0.7f).apply { leftMargin = dp(6) })
                row.addView(TextView(this).apply {
                    text = formatClock(s.seconds * 1000L)
                    setTextColor(Palette.FG); textSize = 22f; gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(56), 1f))
                row.addView(button("+", Palette.PANEL, Palette.FG) {
                    steps[i] = s.copy(seconds = stepUp(s.seconds)); rebuildSteps(); updateSummary()
                }, LinearLayout.LayoutParams(0, dp(56), 0.7f))
                row.addView(button("✕", Palette.GROUND, Palette.MUTED) {
                    if (steps.size > 1) { steps.removeAt(i); rebuildSteps(); updateSummary() } else toast("A workout needs at least one interval")
                }, LinearLayout.LayoutParams(0, dp(56), 0.7f).apply { leftMargin = dp(6) })
                stepsBox.addView(row, spaced())
            }
        }

        col.addView(stepper("Rounds", { rounds.toString() }, { rounds = (rounds - 1).coerceAtLeast(1); updateSummary() }, { rounds = (rounds + 1).coerceAtMost(99); updateSummary() }), spaced())
        col.addView(stepper("Get ready", { "$prep s" }, { prep = (prep - 5).coerceAtLeast(0); updateSummary() }, { prep = (prep + 5).coerceAtMost(60); updateSummary() }), spaced())
        col.addView(caption("Intervals in each round. Tap the coloured button to change Work, Rest or Bag."), spaced(top = 16))
        col.addView(stepsBox)
        col.addView(button("+ Add interval", Palette.PANEL, Palette.FG) {
            val last = steps.lastOrNull()
            steps += Step(last?.kind?.next() ?: Kind.WORK, 30); rebuildSteps(); updateSummary()
        }, spaced())
        col.addView(summaryView, spaced(top = 12))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Save", Palette.FG, Palette.INK) {
            store.upsert(current()); toast("Saved"); showHome()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        actions.addView(button("Save and start", Palette.WORK, Palette.INK) {
            val w = current(); store.upsert(w); startWorkout(w)
        }, LinearLayout.LayoutParams(0, dp(56), 1.3f).apply { leftMargin = dp(8) })
        col.addView(actions, spaced(top = 16))

        val actions2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions2.addView(button("Cancel", Palette.GROUND, Palette.MUTED) { showHome() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (!isNew) {
            var armed = 0L
            lateinit var del: Button
            del = button("Delete", Palette.GROUND, Palette.WORK) {
                val now = System.currentTimeMillis()
                if (now - armed < 3000) { store.delete(original.id); toast("Deleted"); showHome() }
                else { armed = now; del.text = "Press again to delete" }
            }
            actions2.addView(del, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        }
        col.addView(actions2, spaced())

        rebuildSteps()
        updateSummary()
        setContentView(scroll(col))
        if (isTv) stepsBox.getChildAt(0)?.requestFocus()
    }

    private fun stepUp(s: Int) = when { s < 60 -> s + 5; s < 300 -> s + 15; else -> s + 30 }.coerceAtMost(3600)
    private fun stepDown(s: Int) = when { s <= 60 -> s - 5; s <= 300 -> s - 15; else -> s - 30 }.coerceAtLeast(5)

    private fun stepper(label: String, value: () -> String, minus: () -> Unit, plus: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply { text = label; setTextColor(Palette.MUTED); textSize = 16f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        val v = TextView(this).apply {
            text = value(); setTextColor(Palette.FG); textSize = 22f; gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        row.addView(button("−", Palette.PANEL, Palette.FG) { minus(); v.text = value() }, LinearLayout.LayoutParams(0, dp(56), 0.7f))
        row.addView(v, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(button("+", Palette.PANEL, Palette.FG) { plus(); v.text = value() }, LinearLayout.LayoutParams(0, dp(56), 0.7f))
        return row
    }

    // ---------- view helpers ----------

    private fun summary(w: Workout): String {
        val r = if (w.rounds == 1) "1 round" else "${w.rounds} rounds"
        return "$r · ${formatClock(w.totalSeconds * 1000L)}"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val side = if (isTv) dp(48) else dp(16)
        setPadding(side, dp(24), side, dp(32))
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        setBackgroundColor(Palette.GROUND)
        isFillViewport = true
        addView(content)
    }

    private fun spaced(top: Int = 8) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun title(s: String) = TextView(this).apply {
        text = s.uppercase()
        setTextColor(Palette.FG)
        textSize = 34f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private fun caption(s: String) = TextView(this).apply {
        text = s
        setTextColor(Palette.MUTED)
        textSize = 14f
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun boxBg(fill: Int, stroke: Int = 0, strokeW: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    private fun buttonBg(fill: Int): StateListDrawable = StateListDrawable().apply {
        // Thick light outline on focus so the D-pad position is obvious from across the garage.
        addState(intArrayOf(android.R.attr.state_focused), boxBg(fill, Palette.FG, dp(4)))
        addState(intArrayOf(android.R.attr.state_pressed), boxBg(fill, Palette.MUTED, dp(2)))
        addState(intArrayOf(), boxBg(fill, Palette.LINE, if (fill == Palette.GROUND) dp(1) else 0))
    }

    private fun button(label: String, fill: Int, fg: Int, alignStart: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(fg)
        textSize = 17f
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        background = buttonBg(fill)
        stateListAnimator = null
        gravity = if (alignStart) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun bigButton(label: String, fill: Int, fg: Int, onClick: () -> Unit) =
        button(label, fill, fg, onClick = onClick).apply { textSize = 20f; minHeight = dp(64) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
