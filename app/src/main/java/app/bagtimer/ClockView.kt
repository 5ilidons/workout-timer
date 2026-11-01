package app.bagtimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import app.bagtimer.engine.Kind
import app.bagtimer.engine.formatClock
import kotlin.math.min

object Palette {
    const val GROUND = 0xFF0F1113.toInt()
    const val PANEL = 0xFF181B1F.toInt()
    const val LINE = 0xFF2A2F35.toInt()
    const val FG = 0xFFEEF0F2.toInt()
    const val MUTED = 0xFF8B939C.toInt()
    const val INK = 0xFF0B0C0D.toInt()
    const val IDLE = 0xFF3B4148.toInt()
    const val WORK = 0xFFE5484D.toInt()
    const val REST = 0xFF2FB38F.toInt()
    const val BAG = 0xFFF2A531.toInt()

    fun of(k: Kind) = when (k) {
        Kind.WORK -> WORK
        Kind.REST -> REST
        Kind.BAG -> BAG
        Kind.PREP -> IDLE
    }
}

/**
 * The big clock. Draws itself to fit any size, from a full TV screen down to a PiP corner window.
 * Below about 260 dp tall it switches to a compact layout: phase and time only.
 */
class ClockView(context: Context) : View(context) {
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = condensed }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    init {
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = Session.timeline
        val p = Session.position()

        val kind = p?.segment?.kind
        val finished = p?.finished == true
        val color = if (kind == null || finished) Palette.IDLE else Palette.of(kind)
        val ink = if (kind == null || finished || kind == Kind.PREP) Palette.FG else Palette.INK
        bg.color = color
        canvas.drawRect(0f, 0f, w, h, bg)

        val phase = when {
            p == null -> "Ready"
            finished -> "Done"
            Session.state == Session.State.PAUSED -> "${kind!!.label} · paused"
            else -> kind!!.label
        }
        val clock = if (p == null) "0:00" else formatClock(p.remainingMs)
        text.color = ink

        val compact = h / density < 260
        val pad = min(w, h) * 0.06f

        if (compact) {
            // Phase on top, time fills the rest.
            text.textAlign = Paint.Align.CENTER
            text.textSize = fitSize(phase.uppercase(), w - 2 * pad, h * 0.24f)
            canvas.drawText(phase.uppercase(), w / 2, pad + text.textSize * 0.85f, text)
            val phaseBottom = pad + text.textSize
            text.textSize = fitSize(clock, w - 2 * pad, (h - phaseBottom - pad) * 1.0f)
            canvas.drawText(clock, w / 2, h - pad - text.descent() * 0.4f, text)
            drawProgress(canvas, w, h, p?.totalRemainingMs, t?.totalMs, ink, compact = true)
            return
        }

        // Full layout.
        text.textAlign = Paint.Align.LEFT
        val small = min(h * 0.045f, w * 0.05f)
        text.textSize = small
        text.letterSpacing = 0.08f
        val round = when {
            t == null -> ""
            p == null || p.segment.round == 0 -> t.workout.name.uppercase()
            else -> "ROUND ${p.segment.round} OF ${t.workout.rounds}"
        }
        canvas.drawText(round, pad, pad + small, text)
        text.textAlign = Paint.Align.RIGHT
        val left = if (p == null) "" else "${formatClock(p.totalRemainingMs)} LEFT"
        canvas.drawText(left, w - pad, pad + small, text)
        text.letterSpacing = 0f

        text.textAlign = Paint.Align.LEFT
        text.textSize = fitSize(phase.uppercase(), w - 2 * pad, h * 0.14f)
        val phaseY = pad + small * 1.6f + text.textSize * 0.9f
        canvas.drawText(phase.uppercase(), pad, phaseY, text)

        text.textSize = fitSize(clock, w - 2 * pad, h * 0.42f)
        val clockY = phaseY + h * 0.04f + text.textSize * 0.74f
        canvas.drawText(clock, pad - text.textSize * 0.03f, clockY, text)

        val next = t?.let { tl -> p?.let { pos -> if (pos.finished) null else tl.next(pos.segment) } }
        val nextText = when {
            finished -> "Session complete · ${formatClock(t!!.totalMs)}"
            next != null -> "Next: ${next.kind.label} ${formatClock(next.durationMs)}"
            p != null -> "Last interval"
            else -> ""
        }
        text.textSize = small * 1.2f
        canvas.drawText(nextText, pad, h - pad - small * 1.2f, text)

        drawProgress(canvas, w, h, p?.totalRemainingMs, t?.totalMs, ink, compact = false)
    }

    private fun drawProgress(c: Canvas, w: Float, h: Float, remaining: Long?, total: Long?, ink: Int, compact: Boolean) {
        if (remaining == null || total == null || total == 0L) return
        val barH = if (compact) h * 0.03f else h * 0.012f
        val pad = if (compact) 0f else min(w, h) * 0.06f
        val y = h - pad - barH
        rect.set(pad, y, w - pad, y + barH)
        bar.color = Color.argb(60, 0, 0, 0)
        c.drawRect(rect, bar)
        val frac = 1f - remaining.toFloat() / total
        rect.right = pad + (w - 2 * pad) * frac
        bar.color = ink
        bar.alpha = 200
        c.drawRect(rect, bar)
    }

    /** Largest text size where [s] fits inside maxW by maxH. */
    private fun fitSize(s: String, maxW: Float, maxH: Float): Float {
        text.textSize = 100f
        val wAt100 = text.measureText(s).coerceAtLeast(1f)
        val byWidth = 100f * maxW / wAt100
        val byHeight = maxH / 0.78f // cap height of digits is about 0.72 of text size
        return min(byWidth, byHeight).coerceAtLeast(8f)
    }
}
