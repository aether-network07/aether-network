package com.aether.network

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class AetherService : Service(), SensorEventListener {

    companion object {
        var isRunning = false
        const val CHANNEL_ID = "aether_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "AetherService"
        const val SUPABASE_URL = "https://svjzmkvvhqzonkfewxeu.supabase.co"
        const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InN2anpta3Z2aHF6b25rZmV3eGV1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY4MDk2NjAsImV4cCI6MjA5MjM4NTY2MH0.LCcTDtPOSQz1ACpMk76PnlikVJBYaQWixMUCaAM1lI4"
        const val SEND_INTERVAL_MS = 15000L
    }

    private lateinit var sensorManager: SensorManager
    private var accelX = 0f; private var accelY = 0f; private var accelZ = 0f
    private var light = 0f
    private var gyroX = 0f; private var gyroY = 0f; private var gyroZ = 0f
    private var latitude = 0.0; private var longitude = 0.0; private var altitude = 0.0

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var deviceId = ""
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sendJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        val prefs = getSharedPreferences("aether", Context.MODE_PRIVATE)
        deviceId = prefs.getString("device_id", "aether-unknown") ?: "aether-unknown"
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        registerSensors()
        startGPS()
        startForeground(NOTIFICATION_ID, buildNotification("🌍 Aether actif — collecte en cours"))
        startSending()
    }

    private fun registerSensors() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun startGPS() {
        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    latitude = location.latitude
                    longitude = location.longitude
                    altitude = location.altitude
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000L, 10f, listener)
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                latitude = it.latitude; longitude = it.longitude; altitude = it.altitude
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "GPS permission denied: ${e.message}")
        }
    }

    private fun startSending() {
        sendJob = serviceScope.launch {
            while (isActive) {
                try { sendData() } catch (e: Exception) { Log.e(TAG, "Send error: ${e.message}") }
                delay(SEND_INTERVAL_MS)
            }
        }
    }

    private fun sendData() {
        val battery = getBatteryLevel()
        val decibels = measureDecibels()
        val json = JSONObject().apply {
            put("device_id", deviceId)
            put("accel_x", accelX); put("accel_y", accelY); put("accel_z", accelZ)
            put("lumiere", light)
            put("decibels", decibels)
            put("battery_level", battery)
            put("lat", latitude); put("lon", longitude); put("altitude", altitude)
            put("gyro_x", gyroX); put("gyro_y", gyroY); put("gyro_z", gyroZ)
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$SUPABASE_URL/rest/v1/sensor_data")
            .addHeader("apikey", SUPABASE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_KEY")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=minimal")
            .post(body).build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Données envoyées")
                updateNotification("🌍 Actif — ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}")
            } else {
                Log.e(TAG, "❌ Erreur: ${response.code}")
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun measureDecibels(): Double {
        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            val tmpFile = File(cacheDir, "aether_tmp.3gp")
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(tmpFile.absolutePath)
                prepare(); start()
            }
            Thread.sleep(500)
            val amplitude = recorder.maxAmplitude
            recorder.stop(); recorder.release(); tmpFile.delete()
            if (amplitude > 0) 20 * Math.log10(amplitude.toDouble()) else -60.0
        } catch (e: Exception) { -60.0 }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { accelX = event.values[0]; accelY = event.values[1]; accelZ = event.values[2] }
            Sensor.TYPE_LIGHT -> light = event.values[0]
            Sensor.TYPE_GYROSCOPE -> { gyroX = event.values[0]; gyroY = event.values[1]; gyroZ = event.values[2] }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Aether Network", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aether Network")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sensorManager.unregisterListener(this)
        sendJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?) = null
}
