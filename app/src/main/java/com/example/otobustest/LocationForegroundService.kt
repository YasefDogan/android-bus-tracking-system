package com.example.otobustest

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LocationForegroundService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private val client = OkHttpClient.Builder()
        .connectTimeout(Constants.CONNECTION_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(Constants.READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(Constants.WRITE_TIMEOUT, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "LocationForegroundService"
        const val CHANNEL_ID = "LocationServiceChannel"
        const val NOTIFICATION_ID = 1

        var isServiceRunning = false
        var userId: String = ""
        var isManualMode: Boolean = false

        // MainActivity'nin eriştiği sayaçlar
        var totalSent: Int = 0
        var successCount: Int = 0
        var failureCount: Int = 0
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        setupLocationCallback()
        Log.d(TAG, "Servis oluşturuldu")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userId = intent?.getStringExtra("userId") ?: "bus_driver_001"
        isManualMode = intent?.getBooleanExtra("isManual", false) ?: false

        val modeText = if (isManualMode) "Manuel" else "Otomatik"
        val notification = createNotification("$modeText konum gönderimi aktif...")
        startForeground(NOTIFICATION_ID, notification)

        startLocationUpdates()
        isServiceRunning = true

        Log.d(TAG, "Servis başlatıldı - Mod: $modeText, UserId: $userId")

        return START_STICKY
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    Log.d(TAG, "Konum alındı: ${location.latitude}, ${location.longitude}")
                    sendLocationToServer(location.latitude, location.longitude)
                }
            }
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            Constants.LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(Constants.LOCATION_FASTEST_INTERVAL)
            setMaxUpdateDelayMillis(Constants.LOCATION_MAX_WAIT_TIME)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
            Log.d(TAG, "Konum güncellemeleri başlatıldı")
        } catch (e: SecurityException) {
            Log.e(TAG, "Konum izni yok: ${e.message}")
            stopSelf()
        }
    }

    private fun sendLocationToServer(latitude: Double, longitude: Double) {
        totalSent++

        val json = JSONObject().apply {
            put("userId", userId)
            put("latitude", latitude)
            put("longitude", longitude)
            put("timestamp", System.currentTimeMillis())
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(Constants.SEND_LOCATION_URL)
            .post(body)
            .addHeader("x-api-key", Constants.API_KEY)
            .addHeader("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Gönderiliyor: ${Constants.SEND_LOCATION_URL}")
        Log.d(TAG, "Veri: $json")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failureCount++
                Log.e(TAG, "Gönderim hatası: ${e.message}")
                updateNotification("❌ Bağlantı hatası: ${e.message?.take(30)}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()

                if (response.isSuccessful) {
                    successCount++
                    val modeText = if (isManualMode) "Manuel" else "Otomatik"
                    updateNotification("✅ $modeText - Başarılı (${successCount}/${totalSent})")
                    Log.d(TAG, "Gönderim başarılı: $responseBody")
                } else {
                    failureCount++
                    updateNotification("❌ Sunucu hatası: ${response.code}")
                    Log.e(TAG, "Sunucu hatası ${response.code}: $responseBody")
                }

                response.close()
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Konum Servisi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Otobüs konumu takibi için arka plan servisi"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel oluşturuldu")
        }
    }

    private fun createNotification(contentText: String): Notification {
        val packageName = applicationContext.packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent, pendingIntentFlags
            )
        } else {
            null
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🚌 Otobüs Takip Aktif")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isServiceRunning = false

        // Sayaçları sıfırla
        totalSent = 0
        successCount = 0
        failureCount = 0

        Log.d(TAG, "Servis durduruldu ve temizlendi")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}