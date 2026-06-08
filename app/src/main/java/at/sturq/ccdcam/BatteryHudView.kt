package at.sturq.ccdcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.os.BatteryManager
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Camcorder-HUD-style battery indicator: rectangle + nub, filled with up to 4 cyan bars
 * proportional to the current battery level. Bars go red below 20%.
 *
 * Subscribes to the sticky ACTION_BATTERY_CHANGED broadcast while attached so updates
 * are cheap (no polling).
 */
class BatteryHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val strokeColor = ContextCompat.getColor(context, R.color.hud_text)
    private val fillColor = ContextCompat.getColor(context, R.color.hud_accent)
    private val lowColor = ContextCompat.getColor(context, R.color.hud_rec)

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        strokeWidth = dp(1f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    @Volatile private var levelPct: Int = -1  // -1 == unknown
    private val barCount = 4

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val raw = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (raw >= 0 && scale > 0) {
                val pct = (raw * 100 / scale).coerceIn(0, 100)
                if (pct != levelPct) { levelPct = pct; invalidate() }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val sticky = context.registerReceiver(
            receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        sticky?.let { receiver.onReceive(context, it) }
    }

    override fun onDetachedFromWindow() {
        try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // body + nub geometry
        val nubW = w * 0.1f
        val bodyRight = w - nubW
        val stroke = outlinePaint.strokeWidth
        val left = stroke / 2f
        val top = h * 0.18f
        val right = bodyRight - stroke / 2f
        val bottom = h * 0.82f
        canvas.drawRect(left, top, right, bottom, outlinePaint)

        // nub (filled in stroke color)
        fillPaint.color = strokeColor
        canvas.drawRect(bodyRight, h * 0.34f, w, h * 0.66f, fillPaint)

        // unknown level -> just the outline
        val pct = levelPct
        if (pct < 0) return

        val filled = (pct * barCount + 50) / 100  // round
        if (filled <= 0) return

        fillPaint.color = if (pct < 20) lowColor else fillColor
        val padding = dp(2f)
        val innerLeft = left + padding
        val innerTop = top + padding
        val innerRight = right - padding
        val innerBottom = bottom - padding
        val innerW = innerRight - innerLeft
        val gap = dp(1f)
        val barW = (innerW - gap * (barCount - 1)) / barCount
        for (i in 0 until filled) {
            val bl = innerLeft + i * (barW + gap)
            canvas.drawRect(bl, innerTop, bl + barW, innerBottom, fillPaint)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
