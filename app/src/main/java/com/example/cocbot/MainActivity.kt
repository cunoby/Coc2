package com.example.cocbot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private val REQ_CAPTURE = 1
    private val REQ_TEMPLATES = 2

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 60, 40, 40) }
        fun add(text: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; setOnClickListener { onClick() } })
        }
        root.addView(TextView(this).apply {
            text = "CoC Bot\nIkuti urutan 1-4. Setelah mulai, pakai tombol melayang: ketuk = mulai/berhenti, tahan = tutup bot."
        })
        add("1. Izinkan tampil di atas aplikasi lain") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        add("2. Aktifkan Accessibility (CoC Bot)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        add("3. Impor template (PNG)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }, REQ_TEMPLATES)
        }
        add("4. Mulai bot") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        }
        setContentView(root)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        if (req == REQ_CAPTURE) {
            startForegroundService(Intent(this, BotService::class.java)
                .putExtra("code", res).putExtra("data", data))
            moveTaskToBack(true)
        } else if (req == REQ_TEMPLATES) {
            val dir = getExternalFilesDir("templates")!!.apply { mkdirs() }
            val uris = mutableListOf<Uri>()
            data.data?.let { uris.add(it) }
            data.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
            for (u in uris) {
                val name = contentResolver.query(u, null, null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                } ?: continue
                contentResolver.openInputStream(u)?.use { i -> File(dir, name).outputStream().use { o -> i.copyTo(o) } }
            }
            Toast.makeText(this, "${uris.size} template diimpor", Toast.LENGTH_SHORT).show()
        }
    }
}
