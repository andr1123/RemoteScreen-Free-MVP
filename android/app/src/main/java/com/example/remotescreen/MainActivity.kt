package com.example.remotescreen

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val serverUrl = "ws://10.188.45.140:8000/ws/text"
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private lateinit var input: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.input)
        status = findViewById(R.id.status)

        connect()

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                socket?.send(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun connect() {
        status.text = "Status: menghubungkan..."
        val request = Request.Builder().url(serverUrl).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                runOnUiThread { status.text = "Status: TERHUBUNG" }
                webSocket.send(input.text.toString())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                runOnUiThread { status.text = "Status: GAGAL — ${t.message ?: "koneksi gagal"}" }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread { status.text = "Status: terputus" }
            }
        })
    }

    override fun onDestroy() {
        socket?.close(1000, "app closed")
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}
