package com.aether.network

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_CODE = 100
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val statusText = findViewById<TextView>(R.id.statusText)
        val deviceIdText = findViewById<TextView>(R.id.deviceIdText)

        val prefs = getSharedPreferences("aether", MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = "aether-" + java.util.UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("device_id", deviceId).apply()
        }
        deviceIdText.text = "ID : $deviceId"

        val isRunning = AetherService.isRunning
        updateUI(isRunning, btnConnect, btnStop, statusText)

        btnConnect.setOnClickListener {
            if (hasPermissions()) {
                startAetherService()
                updateUI(true, btnConnect, btnStop, statusText)
            } else {
                ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, AetherService::class.java))
            updateUI(false, btnConnect, btnStop, statusText)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startAetherService() {
        val intent = Intent(this, AetherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateUI(running: Boolean, btnConnect: Button, btnStop: Button, statusText: TextView) {
        if (running) {
            btnConnect.isEnabled = false
            btnStop.isEnabled = true
            statusText.text = "🟢 Connecté — envoi des données en cours"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            btnConnect.isEnabled = true
            btnStop.isEnabled = false
            statusText.text = "⚪ Déconnecté"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startAetherService()
                val btnConnect = findViewById<Button>(R.id.btnConnect)
                val btnStop = findViewById<Button>(R.id.btnStop)
                val statusText = findViewById<TextView>(R.id.statusText)
                updateUI(true, btnConnect, btnStop, statusText)
            } else {
                Toast.makeText(this, "Permissions requises pour collecter les données", Toast.LENGTH_LONG).show()
            }
        }
    }
}
