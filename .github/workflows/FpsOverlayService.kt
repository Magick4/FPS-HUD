package com.fps.hud

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.WindowManager.LayoutParams.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.math.abs

class FpsOverlayService : Service() {

    // ── Window manager + overlay view ────────────────────────────────────────
    private lateinit var wm: WindowManager
    private lateinit var hudRoot: LinearLayout
    private lateinit var tvFps: TextView
    private lateinit var tvLabel: TextView
    private lateinit var tvLow: TextView
    private lateinit var tvGraph: FpsGraphView
    private lateinit var params: WindowManager.LayoutParams

    // ── Polling ──────────────────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val fpsHistory = ArrayDeque<Float>(HISTORY_LEN)
    private var lastReadMs = 0L

    // Data source: module writes here (world-readable)
    private val fpsFile = File("/sdcard/.fps_hud")
    // Fallback path
    private val fpsFileFallback = File("/data/adb/fps_overlay/current_fps")

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Waiting for data…"))
        buildHud()
        handler.post(pollRunnable)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        runCatching { wm.removeView(hudRoot) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ── HUD construction ─────────────────────────────────────────────────────
    private fun buildHud() {
        // Root container
        hudRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = hudBackground()
            elevation = 8f
        }

        // "FPS" micro-label
        tvLabel = TextView(this).apply {
            text = "FPS"
            textSize = 8f
            setTextColor(Color.parseColor("#80FFFFFF"))
            typeface = Typeface.create("monospace", Typeface.BOLD)
            letterSpacing = 0.3f
        }

        // Main FPS number
        tvFps = TextView(this).apply {
            text = "---"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1% low
        tvLow = TextView(this).apply {
            text = "1% ---"
            textSize = 8f
            setTextColor(Color.parseColor("#80FFFFFF"))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }

        // Sparkline graph
        tvGraph = FpsGraphView(this)

        hudRoot.addView(tvLabel)
        hudRoot.addView(tvFps)
        hudRoot.addView(tvLow)
        hudRoot.addView(tvGraph, LinearLayout.LayoutParams(dp(72), dp(24)))

        // Window params — TYPE_APPLICATION_OVERLAY stays above games
        val winType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") TYPE_PHONE

        params = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            winType,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS or FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(48)
        }

        wm.addView(hudRoot, params)
        makeDraggable()
    }

    // ── Draggable touch handler ──────────────────────────────────────────────
    private fun makeDraggable() {
        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f

        hudRoot.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (touchX - ev.rawX).toInt()
                    params.y = initY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(hudRoot, params)
                    true
                }
                else -> false
            }
        }
    }

    // ── Polling runnable ─────────────────────────────────────────────────────
    private val pollRunnable = object : Runnable {
        override fun run() {
            val raw = readFpsLine()
            if (raw != null) {
                updateHud(raw)
                lastReadMs = SystemClock.elapsedRealtime()
            } else if (SystemClock.elapsedRealtime() - lastReadMs > STALE_MS) {
                tvFps.text = "---"
                tvLow.text = "No data"
                setFpsColor(tvFps, -1f)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun readFpsLine(): String? =
        runCatching {
            val f = if (fpsFile.canRead()) fpsFile else fpsFileFallback
            f.readText().trim().ifBlank { null }
        }.getOrNull()

    // ── HUD update ───────────────────────────────────────────────────────────
    private fun updateHud(raw: String) {
        // Expected format: "FPS: 118.4  |  1% Low: 97.2"  or  "FPS: 118.4"
        val fpsVal = Regex("""FPS:\s*([\d.]+)""").find(raw)?.groupValues?.get(1)?.toFloatOrNull()
        val lowVal = Regex("""1%\s*Low:\s*([\d.]+|---)""").find(raw)?.groupValues?.get(1)

        if (fpsVal != null) {
            tvFps.text = fpsVal.toInt().toString()
            setFpsColor(tvFps, fpsVal)
            fpsHistory.addLast(fpsVal)
            if (fpsHistory.size > HISTORY_LEN) fpsHistory.removeFirst()
            tvGraph.update(fpsHistory.toList())
            updateNotification("${fpsVal.toInt()} FPS${if (lowVal != null) "  |  1% Low: $lowVal" else ""}")
        }

        tvLow.text = if (lowVal != null && lowVal != "---") "1% $lowVal" else "1% ---"
    }

    /** Color-codes the FPS number: green ≥ target, yellow mid, red low */
    private fun setFpsColor(tv: TextView, fps: Float) {
        tv.setTextColor(when {
            fps < 0   -> Color.parseColor("#80FFFFFF")
            fps >= 90 -> Color.parseColor("#00FF9D")   // bright green
            fps >= 50 -> Color.parseColor("#FFD600")   // amber
            else      -> Color.parseColor("#FF3D3D")   // red
        })
    }

    // ── Notification ─────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(CHANNEL_ID, "FPS HUD", NotificationManager.IMPORTANCE_LOW)
                .also { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(text: String): Notification {
        val toggleIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { action = MainActivity.ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FPS HUD Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop HUD", toggleIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hudBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(10).toFloat()
        setColor(Color.parseColor("#CC0A0A0F"))      // near-black, 80% opaque
        setStroke(1, Color.parseColor("#33FFFFFF"))  // subtle white border
    }

    companion object {
        var isRunning = false
        private const val CHANNEL_ID  = "fps_hud_channel"
        private const val NOTIF_ID    = 9001
        private const val POLL_MS     = 200L
        private const val STALE_MS    = 2000L
        private const val HISTORY_LEN = 36
    }
}

// ── Mini sparkline graph view ────────────────────────────────────────────────
class FpsGraphView(context: Context) : View(context) {

    private var data: List<Float> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF9D")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val fillPath = Path()

    fun update(history: List<Float>) {
        data = history
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (data.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val minFps = (data.min() - 5f).coerceAtLeast(0f)
        val maxFps = (data.max() + 5f).coerceAtLeast(minFps + 1f)
        val range = maxFps - minFps

        fun xOf(i: Int) = i / (data.size - 1).toFloat() * w
        fun yOf(v: Float) = h - ((v - minFps) / range * h).coerceIn(0f, h)

        path.reset()
        fillPath.reset()
        fillPath.moveTo(xOf(0), h)
        data.forEachIndexed { i, v ->
            val x = xOf(i); val y = yOf(v)
            if (i == 0) { path.moveTo(x, y); fillPath.lineTo(x, y) }
            else        { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(xOf(data.size - 1), h)
        fillPath.close()

        // Gradient fill
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#4000FF9D"),
            Color.parseColor("#0000FF9D"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
