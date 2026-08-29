package com.example.remotescreen

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var url: EditText
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val requestCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        url = findViewById(R.id.url)
        status = findViewById(R.id.status)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)

        if (url.text.isNullOrBlank()) {
            url.setText("ws://10.188.45.140:8000/ws/phone")
        }

        start.setOnClickListener {
            val wsUrl = url.text.toString().trim()
            if (!wsUrl.startsWith("ws://") && !wsUrl.startsWith("wss://")) {
                status.text = "Status: URL harus ws:// atau wss://"
                return@setOnClickListener
            }

            val mgr = getSystemService(MediaProjectionManager::class.java)
            status.text = "Status: meminta izin screen capture..."
            startActivityForResult(mgr.createScreenCaptureIntent(), requestCode)
        }

        stop.setOnClickListener {
            stopService(Intent(this, ScreenService::class.java))
            status.text = "Status: berhenti"
            start.isEnabled = true
            stop.isEnabled = false
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != this.requestCode || resultCode != Activity.RESULT_OK || data == null) {
            status.text = "Status: izin screen capture ditolak"
            start.isEnabled = true
            stop.isEnabled = false
            return
        }

        val intent = Intent(this, ScreenService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("resultData", data)
            putExtra("wsUrl", url.text.toString().trim())
        }
        startForegroundService(intent)
        status.text = "Status: menghubungkan ke server..."
        start.isEnabled = false
        stop.isEnabled = true
    }
}
