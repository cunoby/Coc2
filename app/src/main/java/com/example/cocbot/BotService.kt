package com.example.cocbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import kotlinx.coroutines.*
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.random.Random

/** Menangkap layar lewat MediaProjection, hasilnya Mat grayscale. */
class Screen(proj: MediaProjection, val w: Int, val h: Int, dpi: Int) {
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    private val vd = proj.createVirtualDisplay(
        "cap", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)

    fun shot(): Mat? {
        val img = reader.acquireLatestImage() ?: return null
        val pl = img.planes[0]
        val pad = (pl.rowStride - pl.pixelStride * w) / pl.pixelStride
        val full = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(pl.buffer)
        img.close()
        val bmp = Bitmap.createBitmap(full, 0, 0, w, h)
        val m = Mat()
        Utils.bitmapToMat(bmp, m)
        Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
        full.recycle(); bmp.recycle()
        return m
    }

    fun release() { vd.release(); reader.close() }
}

class BotService : Service() {
    // ---- Pengaturan: sesuaikan dengan layarmu (posisi relatif 0-1) ----
    private val threshold = 0.85
    private val upgradePerCycle = 3
    private val attackSeconds = 60L
    private val firstBuilderItem = 0.50 to 0.30
    private val troops = listOf((0.15 to 0.92) to 12, (0.25 to 0.92) to 8)
    private val heroes = listOf(0.05 to 0.92, 0.09 to 0.92)
    private val deployPoints = listOf(0.20 to 0.45, 0.25 to 0.50, 0.30 to 0.55)
    private val collectTemplates = listOf("collect_gold", "collect_elixir", "collect_dark")
    private val game = "com.supercell.clashofclans"
    // ---------------------------------------------------------------------

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null
    private var screen: Screen? = null
    private var proj: MediaProjection? = null
    private var overlay: Button? = null
    private val cache = HashMap<String, Mat?>()
    private val dir by lazy { getExternalFilesDir("templates")!! }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bot", "Bot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "bot").setContentTitle("CoC Bot aktif")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        OpenCVLoader.initLocal()
        @Suppress("DEPRECATION")
        val data = i?.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(i.getIntExtra("code", 0), data)
        p.registerCallback(object : MediaProjection.Callback() {}, null)
        proj = p
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val w = maxOf(b.width(), b.height()); val h = minOf(b.width(), b.height())
        screen = Screen(p, w, h, resources.displayMetrics.densityDpi)
        showOverlay()
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val btn = Button(this).apply {
            text = "▶"
            setOnClickListener {
                if (job?.isActive == true) { job?.cancel(); text = "▶" } else { startLoop(); text = "■" }
            }
            setOnLongClickListener { stopSelf(); true }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 200 }
        wm.addView(btn, lp)
        overlay = btn
    }

    override fun onDestroy() {
        job?.cancel(); scope.cancel()
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        screen?.release(); proj?.stop()
        super.onDestroy()
    }

    // ---- helper ----
    private fun find(name: String, s: Mat): Pair<Double, Double>? {
        val t = cache.getOrPut(name) {
            File(dir, "$name.png").takeIf { it.exists() }?.let { Imgcodecs.imread(it.path, Imgcodecs.IMREAD_GRAYSCALE) }
        } ?: return null
        if (t.cols() > s.cols() || t.rows() > s.rows()) return null
        val res = Mat()
        Imgproc.matchTemplate(s, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res); res.release()
        return if (mm.maxVal >= threshold) (mm.maxLoc.x + t.cols() / 2) to (mm.maxLoc.y + t.rows() / 2) else null
    }

    private suspend fun wait(name: String, ms: Long = 10_000): Pair<Double, Double>? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val s = screen?.shot()
            if (s != null) { val p = find(name, s); s.release(); if (p != null) return p }
            delay(1000)
        }
        return null
    }

    private suspend fun tap(x: Double, y: Double) {
        BotAccessibility.inst?.tap((x + Random.nextInt(-4, 5)).toFloat(), (y + Random.nextInt(-4, 5)).toFloat())
        delay(Random.nextLong(250, 600))
    }

    private suspend fun rel(p: Pair<Double, Double>) = tap(p.first * screen!!.w, p.second * screen!!.h)

    private suspend fun click(name: String, ms: Long = 5000): Boolean {
        val p = wait(name, ms) ?: return false
        tap(p.first, p.second); return true
    }

    // ---- tugas ----
    private suspend fun collect() {
        val s = screen?.shot() ?: return
        val hits = collectTemplates.mapNotNull { find(it, s) }
        s.release()
        for (p in hits) tap(p.first, p.second)
    }

    private suspend fun upgrade() {
        repeat(upgradePerCycle) {
            if (!click("builder_icon", 3000)) return
            rel(firstBuilderItem)
            if (click("upgrade_btn", 4000)) click("confirm_btn", 3000)
            else { click("close_btn", 2000); return }
        }
    }

    private suspend fun attack() {
        if (!click("attack_btn")) return
        if (!click("find_match_btn")) return
        if (wait("end_battle_btn", 45_000) == null) return
        for ((slot, n) in troops) {
            rel(slot)
            repeat(n) { rel(deployPoints[it % deployPoints.size]) }
        }
        for (h in heroes) { rel(h); rel(deployPoints[0]) }
        delay(attackSeconds * 1000)
        for (h in heroes) rel(h)
        if (click("end_battle_btn", 3000)) click("surrender_ok", 3000)
        click("return_home_btn", 20_000)
    }

    private fun launchGame() {
        packageManager.getLaunchIntentForPackage(game)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(it)
        }
    }

    private fun startLoop() {
        job = scope.launch {
            launchGame(); delay(6000)
            var cycle = 0; var nextBreak = Random.nextInt(6, 13)
            while (isActive) {
                cycle++
                try { collect(); upgrade(); attack() } catch (e: Exception) { Log.e("CocBot", "error", e) }
                if (cycle >= nextBreak) {
                    delay(Random.nextLong(5, 21) * 60_000)
                    cycle = 0; nextBreak = Random.nextInt(6, 13)
                }
                delay(Random.nextLong(30_000, 90_000))
            }
        }
    }
}
