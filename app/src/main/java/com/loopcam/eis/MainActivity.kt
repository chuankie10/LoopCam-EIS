package com.loopcam.eis

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        requestCameraPermissions()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
        }
        val title = TextView(this).apply {
            text = "LOOPCAM EIS"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Tahap awal: kerangka aplikasi siap"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }
        val start = Button(this).apply {
            text = "START"
            isEnabled = false
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Mesin kamera akan ditambahkan pada tahap berikutnya", Toast.LENGTH_LONG).show()
            }
        }
        val stop = Button(this).apply {
            text = "STOP"
            isEnabled = false
        }
        root.addView(title)
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        setContentView(root)
    }

    private fun requestCameraPermissions() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        } else {
            status.text = "Izin kamera dan mikrofon tersedia. Mesin kamera belum diaktifkan."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            status.text = if (granted) {
                "Izin kamera dan mikrofon tersedia. Mesin kamera belum diaktifkan."
            } else {
                "Izin kamera dan mikrofon diperlukan untuk merekam."
            }
        }
    }
}
