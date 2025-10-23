package com.example.otobustest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.*
import com.mapbox.maps.plugin.locationcomponent.location
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.*

data class BusStop(
    val lat: Double,
    val lng: Double,
    val label: String,
    var schedule: List<String>
)

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var mapView: com.mapbox.maps.MapView
    private lateinit var locationCallback: LocationCallback
    private lateinit var debugButton: android.widget.Button
    private lateinit var locateButton: android.widget.Button
    private lateinit var sendLocationButton: android.widget.Button

    private val client = OkHttpClient()
    private val TAG = "MainActivity"

    private val popupUpdateHandler = Handler(Looper.getMainLooper())
    private val fetchUsersHandler = Handler(Looper.getMainLooper())
    private val locationTrackingHandler = Handler(Looper.getMainLooper())
    private val autoSendHandler = Handler(Looper.getMainLooper())

    private var currentLocation: android.location.Location? = null
    private var pointAnnotationManager: PointAnnotationManager? = null
    private var busStopAnnotations = mutableMapOf<Int, PointAnnotation>()
    private var userLocationAnnotations = mutableMapOf<String, PointAnnotation>()
    private var isFirstLocationReceived = false
    private var userId = UUID.randomUUID().toString().take(8)
    private var isLocationTrackingActive = false
    private var sendLocationRunnable: Runnable? = null

    // Adım sayacı değişkenleri
    private var stepCounterSensor: Sensor? = null
    private var sensorManager: SensorManager? = null
    private var initialStepCount = -1
    private var stepsSinceLeave = 0
    private val MAX_STEPS = 30
    private var wasAtStop = false
    private var stopStartTime: Long = 0L
    private var currentNearbyBusStop: BusStop? = null
    private val STOP_DISTANCE_METERS = 10.0
    private var isAutoSending = false
    private var autoSendRunnable: Runnable? = null
    private val AUTO_SEND_INTERVAL = 5000L

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 10000
    ).apply {
        setMinUpdateIntervalMillis(5000)
        setMaxUpdateDelayMillis(15000)
    }.build()

    private val schedule2 = listOf(
        "07:30", "07:35", "07:40", "07:45", "07:50", "08:00", "08:10", "08:20", "08:30", "08:50",
        "09:00", "09:20", "09:30", "10:00", "10:30", "10:50", "11:00", "11:15", "11:30", "11:45",
        "12:00", "12:15", "12:30", "12:45", "13:00", "13:15", "13:30", "13:45", "14:00", "14:15",
        "14:30", "14:45", "15:00", "15:15", "15:30", "15:45", "16:00", "16:15", "16:30", "16:45",
        "17:00", "17:15", "17:30", "17:45", "18:00", "18:15", "18:30", "19:30", "20:00", "21:00",
        "21:30", "22:00", "22:30"
    )

    private val busStops = mutableListOf(
        BusStop(40.60334291781015, 35.81898005259835, "Sultan Beyazıt KYK", emptyList()),
        BusStop(40.60673605201167, 35.8120564911628, "Sağlık Fakültesi Geliş", emptyList()),
        BusStop(40.64748813553502, 35.813670862671856, "Mezarlık", emptyList()),
        BusStop(40.65542855188074, 35.805376980033, "Eğitim", emptyList())
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val token = getString(R.string.mapbox_access_token)
            com.mapbox.common.MapboxOptions.accessToken = token
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        sendLocationButton = findViewById(R.id.sendLocationButton)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Adım sayacı sensor kurulumu
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        setupLocationCallback()
        initializeBusStops()

        // Her zaman butonları oluştur
        setupDynamicButtons()

        // Harita her zaman yüklensin
        mapView.getMapboxMap().loadStyleUri(com.mapbox.maps.Style.MAPBOX_STREETS) {
            addBusStopMarkers()
            startFetchingUserLocations()
            startPeriodicPopupUpdate()
        }

        sendLocationButton.setOnClickListener {
            if (!isLocationTrackingActive) {
                startContinuousLocationTracking()
            } else {
                stopContinuousLocationTracking()
            }
        }

        // İzinleri kontrol et ve iste
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        // Konum izni kontrolü
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
        } else {
            // Konum izni var, haritayı etkinleştir
            enableLocationOnMap()
            // Diğer izinleri kontrol et
            checkOtherPermissions()
        }
    }

    private fun checkOtherPermissions() {
        // Adım sayacı izni (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                registerStepCounter()
            }
        } else {
            registerStepCounter()
        }

        // Bildirim izni (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Sessizce kaydet, kullanıcıyı rahatsız etme
            }
        }
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            // Konum izni var, diğer izinleri iste
            Handler(Looper.getMainLooper()).postDelayed({
                requestOtherPermissions()
            }, 500)
        }
    }

    private fun requestOtherPermissions() {
        // Adım sayacı izni (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACTIVITY_RECOGNITION),
                    ACTIVITY_RECOGNITION_REQUEST_CODE
                )
                return
            } else {
                registerStepCounter()
            }
        } else {
            registerStepCounter()
        }

        // Bildirim izni (Android 13+)
        Handler(Looper.getMainLooper()).postDelayed({
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        NOTIFICATION_PERMISSION_REQUEST_CODE
                    )
                }
            }
        }, 500)
    }

    private fun registerStepCounter() {
        stepCounterSensor?.let {
            sensorManager?.registerListener(stepListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                val totalSteps = it.values[0].toInt()
                if (initialStepCount == -1) {
                    initialStepCount = totalSteps
                }
                stepsSinceLeave = totalSteps - initialStepCount
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun setupDynamicButtons() {
        // Debug butonu
        debugButton = android.widget.Button(this).apply {
            text = "DEBUG"
            setBackgroundColor(Color.parseColor("#AAFF0000"))
            setTextColor(Color.WHITE)
        }

        val debugParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = 20
            bottomMargin = 20
            gravity = Gravity.BOTTOM or Gravity.LEFT
        }

        (mapView.parent as? ViewGroup)?.addView(debugButton, debugParams)

        debugButton.setOnClickListener {
            val secondsAtStop = if (wasAtStop) (System.currentTimeMillis() - stopStartTime) / 1000 else 0
            val nearestStopName = currentNearbyBusStop?.label ?: "Yok"
            val debugText = """
                User ID: $userId
                
                SERVER BİLGİLERİ:
                Backend: ${Constants.SEND_LOCATION_URL}
                
                DURUM:
                Adımlar: $stepsSinceLeave
                Durağın altındayım: $wasAtStop
                Durakta bekleme süresi: ${secondsAtStop}s
                En yakın durak: $nearestStopName
                Otomatik gönderim: $isAutoSending
                Manuel izleme: $isLocationTrackingActive
                Mevcut Konum: ${currentLocation?.latitude}, ${currentLocation?.longitude}
                
                DİĞER KULLANICILAR:
                Haritada görünen: ${userLocationAnnotations.size}
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("Debug Bilgisi")
                .setMessage(debugText)
                .setPositiveButton("Tamam", null)
                .setNeutralButton("Test Gönder") { _, _ ->
                    sendCurrentLocation()
                    Toast.makeText(this, "Test konumu gönderildi", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // Neredeyim butonu
        locateButton = android.widget.Button(this).apply {
            text = "Neredeyim"
            setBackgroundColor(Color.WHITE)
        }

        val locateParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            rightMargin = 20
            bottomMargin = 20
            gravity = Gravity.BOTTOM or Gravity.RIGHT
        }

        (mapView.parent as? ViewGroup)?.addView(locateButton, locateParams)

        locateButton.setOnClickListener {
            if (currentLocation != null) {
                mapView.getMapboxMap().setCamera(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(currentLocation!!.longitude, currentLocation!!.latitude))
                        .zoom(15.0)
                        .build()
                )
            } else {
                Toast.makeText(this, "Konum alınmadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initializeBusStops() {
        busStops[0].schedule = schedule2
        busStops[1].schedule = adjustSchedule(schedule2, 7)
        busStops[2].schedule = adjustSchedule(schedule2, 19)
        busStops[3].schedule = adjustSchedule(schedule2, 25)
    }

    private fun adjustSchedule(schedule: List<String>, subtract: Int): List<String> {
        return schedule.map {
            val (h, m) = it.split(":").map { s -> s.toInt() }
            var hour = h
            var minute = m - subtract
            if (minute < 0) {
                minute += 60
                hour -= 1
            }
            "%02d:%02d".format(hour, minute)
        }
    }

    private fun calculateTimeRemaining(schedule: List<String>): String {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val nextBuses = schedule.mapNotNull {
            val (h, m) = it.split(":").map { s -> s.toInt() }
            val total = h * 60 + m
            if (total > currentMinutes) total - currentMinutes else null
        }

        return when {
            nextBuses.isEmpty() -> "Bugün başka otobüs yok."
            nextBuses.size == 1 -> "Sonraki otobüs ${nextBuses[0]} dakika içinde."
            else -> "Sonraki otobüs ${nextBuses[0]} dakika içinde.\nSıradaki otobüs ${nextBuses[1]} dakika içinde."
        }
    }

    private fun checkBusStopAndTriggerAutoSend(location: android.location.Location) {
        var nearestStop: BusStop? = null
        var nearestDistance = Double.MAX_VALUE

        busStops.forEach { stop ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(
                location.latitude, location.longitude,
                stop.lat, stop.lng, results
            )
            val distance = results[0].toDouble()
            if (distance < nearestDistance) {
                nearestDistance = distance
                nearestStop = stop
            }
        }

        if (nearestDistance <= STOP_DISTANCE_METERS) {
            // Durağın içindeyiz
            if (!wasAtStop) {
                // Yeni durağa girdik
                stopStartTime = System.currentTimeMillis()
                initialStepCount = -1 // Adım sayacını sıfırla
                stepsSinceLeave = 0
                Toast.makeText(this, "Durağa girdiniz: ${nearestStop?.label}", Toast.LENGTH_SHORT).show()
            }
            wasAtStop = true
            currentNearbyBusStop = nearestStop
        } else {
            // Duraktan uzaktayız
            if (wasAtStop) {
                // Durağı yeni terk ettik
                Toast.makeText(this, "Durağı terk ettiniz", Toast.LENGTH_SHORT).show()
                startAutoLocationSending()
                wasAtStop = false
            }
            currentNearbyBusStop = null
        }
    }

    private fun startAutoLocationSending() {
        if (isAutoSending) return

        isAutoSending = true
        Toast.makeText(this, "Otobuste olduğunuz algılandı, konumunuz diğer kullanicilarla paylaşılıyor. (Arka planda çalışır)", Toast.LENGTH_SHORT).show()

        // Foreground Service'i başlat (otomatik mod)
        val serviceIntent = Intent(this, LocationForegroundService::class.java)
        serviceIntent.putExtra("userId", userId)
        serviceIntent.putExtra("isManual", false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        autoSendRunnable = object : Runnable {
            override fun run() {
                // Adım sayısını her çalıştırmada kontrol et
                if (stepsSinceLeave <= MAX_STEPS) {
                    autoSendHandler.postDelayed(this, AUTO_SEND_INTERVAL)
                } else {
                    stopAutoLocationSending()
                    Toast.makeText(this@MainActivity, "30 adımdan fazla yürüdünüz, otomatik gönderim durdu", Toast.LENGTH_SHORT).show()
                }
            }
        }
        autoSendRunnable?.let { autoSendHandler.post(it) }
    }

    private fun stopAutoLocationSending() {
        if (!isAutoSending) return
        isAutoSending = false
        autoSendRunnable?.let { autoSendHandler.removeCallbacks(it) }

        // Sadece manuel mod kapalıysa servisi durdur
        if (!isLocationTrackingActive) {
            val serviceIntent = Intent(this, LocationForegroundService::class.java)
            stopService(serviceIntent)
        }
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentLocation = location

                    if (!isFirstLocationReceived) {
                        isFirstLocationReceived = true
                        mapView.getMapboxMap().setCamera(
                            CameraOptions.Builder()
                                .center(Point.fromLngLat(location.longitude, location.latitude))
                                .zoom(15.0)
                                .build()
                        )
                    }

                    // Durak kontrolü ve otomatik gönderim
                    checkBusStopAndTriggerAutoSend(location)
                }
            }
        }
    }

    private fun enableLocationOnMap() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        // Android 10+ için arka plan konum izni
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                AlertDialog.Builder(this)
                    .setTitle("Arka Plan Konum İzni")
                    .setMessage("Otobüslerin konumunun canlı takip edilebilmesi için arka plan konum iznine ihtiyaç var. Lütfen 'Her zaman izin ver' seçeneğini seçin.")
                    .setPositiveButton("İzin Ver") { _, _ ->
                        ActivityCompat.requestPermissions(
                            this,
                            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                            BACKGROUND_LOCATION_REQUEST_CODE
                        )
                    }
                    .setNegativeButton("Sonra", null)
                    .show()
            }
        }

        mapView.location.updateSettings {
            enabled = true
            pulsingEnabled = true
        }

        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            mainLooper
        )

        Toast.makeText(this, "Konum izleme başladı", Toast.LENGTH_SHORT).show()
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun startContinuousLocationTracking() {
        isLocationTrackingActive = true
        sendLocationButton.text = "Aramayı Durdur"
        sendLocationButton.setBackgroundColor(Color.RED)

        Toast.makeText(this, "Taksi arkadaşı arama talebiniz alındı. (Arka planda çalışır)", Toast.LENGTH_SHORT).show()

        // Foreground Service'i başlat (manuel mod)
        val serviceIntent = Intent(this, LocationForegroundService::class.java)
        serviceIntent.putExtra("userId", userId)
        serviceIntent.putExtra("isManual", true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        sendLocationRunnable = object : Runnable {
            override fun run() {
                locationTrackingHandler.postDelayed(this, 5000)
            }
        }
        sendLocationRunnable?.let { locationTrackingHandler.post(it) }
    }

    private fun stopContinuousLocationTracking() {
        isLocationTrackingActive = false
        sendLocationButton.text = "Taksı arkadaşı ara"
        sendLocationButton.setBackgroundColor(Color.parseColor("#4285F4"))

        sendLocationRunnable?.let { locationTrackingHandler.removeCallbacks(it) }

        // Sadece otomatik mod kapalıysa servisi durdur
        if (!isAutoSending) {
            val serviceIntent = Intent(this, LocationForegroundService::class.java)
            stopService(serviceIntent)
        }

        Toast.makeText(this, "taksi arkadaşı arama durduruldu", Toast.LENGTH_SHORT).show()
    }

    private fun sendCurrentLocation() {
        val location = currentLocation ?: return

        val json = JSONObject().apply {
            put("userId", userId)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(Constants.SEND_LOCATION_URL)
            .post(body)
            .addHeader("x-api-key", Constants.API_KEY)
            .addHeader("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Test konum gönderiliyor: $json")

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Test gönderim hatası: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Test başarısız: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d(TAG, "Test yanıt: ${response.code} - $responseBody")
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Test başarılı!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Test hatası: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
                response.close()
            }
        })
    }

    // DOĞRU ENDPOİNT İLE KULLANICILARI ÇEK
    private fun startFetchingUserLocations() {
        val fetchRunnable = object : Runnable {
            override fun run() {
                fetchUserLocationsFromDB()
                fetchUsersHandler.postDelayed(this, 5000)
            }
        }
        fetchUsersHandler.post(fetchRunnable)
    }

    private fun fetchUserLocationsFromDB() {
        val request = Request.Builder()
            .url(Constants.GET_LOCATIONS_URL)
            .get()
            .addHeader("x-api-key", Constants.API_KEY)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Kullanıcı çekme hatası: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val jsonObject = JSONObject(body)

                            if (jsonObject.getBoolean("success")) {
                                val dataArray = jsonObject.getJSONArray("data")
                                val userLocations = mutableListOf<Triple<String, Double, Double>>()

                                for (i in 0 until dataArray.length()) {
                                    val obj = dataArray.getJSONObject(i)
                                    val fetchedUserId = obj.getString("userId")
                                    val latitude = obj.getDouble("latitude")
                                    val longitude = obj.getDouble("longitude")
                                    userLocations.add(Triple(fetchedUserId, latitude, longitude))
                                }

                                Log.d(TAG, "Çekilen kullanıcı sayısı: ${userLocations.size}")

                                runOnUiThread {
                                    updateUserMarkersOnMap(userLocations)
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "API hatası: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse hatası: ${e.message}")
                    e.printStackTrace()
                }
                response.close()
            }
        })
    }

    private fun updateUserMarkersOnMap(userLocations: List<Triple<String, Double, Double>>) {
        // Kendi kullanıcı ID'mizi filtrele
        val otherUsers = userLocations.filter { it.first != userId }

        Log.d(TAG, "Haritada gösterilecek kullanıcılar: ${otherUsers.size}")

        // Eski marker'ları çıkar
        val toRemove = mutableListOf<String>()
        userLocationAnnotations.forEach { (userId, annotation) ->
            if (otherUsers.none { it.first == userId }) {
                pointAnnotationManager?.delete(annotation)
                toRemove.add(userId)
            }
        }
        toRemove.forEach { userLocationAnnotations.remove(it) }

        // Yeni marker'ları ekle veya güncelle
        otherUsers.forEach { (userId, latitude, longitude) ->
            val point = Point.fromLngLat(longitude, latitude)

            if (userLocationAnnotations.containsKey(userId)) {
                val annotation = userLocationAnnotations[userId]
                if (annotation != null) {
                    // Marker var, güncelle
                    annotation.point = point
                    pointAnnotationManager?.update(annotation)
                    Log.d(TAG, "Marker güncellendi: $userId")
                }
            } else {
                // Yeni marker ekle
                val annotationOptions = PointAnnotationOptions()
                    .withPoint(point)
                    .withIconImage("user-location-icon")
                    .withIconSize(1.2)

                val annotation = pointAnnotationManager?.create(annotationOptions)
                if (annotation != null) {
                    userLocationAnnotations[userId] = annotation
                    Log.d(TAG, "Yeni marker eklendi: $userId")

                    pointAnnotationManager?.addClickListener { clicked ->
                        if (clicked == annotation) {
                            Toast.makeText(
                                this,
                                "Kullanıcı ID: ${userId.take(6)}",
                                Toast.LENGTH_SHORT
                            ).show()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Toplam marker sayısı: ${userLocationAnnotations.size}")
    }

    private fun createUserLocationMarker(): android.graphics.Bitmap {
        val size = 80
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val whitePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2 - 3).toFloat(), whitePaint)

        val redPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FF5252")
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2 - 3).toFloat(), redPaint)

        val centerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FF5252")
            isAntiAlias = true
        }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), 8f, centerPaint)

        return bitmap
    }

    private fun addBusStopMarkers() {
        val annotationApi = mapView.annotations
        pointAnnotationManager = annotationApi.createPointAnnotationManager()

        val busStopMarker = createBusStopMarker()
        val userLocationMarker = createUserLocationMarker()

        try {
            mapView.getMapboxMap().getStyle { style ->
                style.addImage("bus-stop-icon", busStopMarker, false)
                style.addImage("user-location-icon", userLocationMarker, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        busStops.forEachIndexed { index, stop ->
            val point = Point.fromLngLat(stop.lng, stop.lat)

            val annotationOptions = PointAnnotationOptions()
                .withPoint(point)
                .withIconImage("bus-stop-icon")
                .withIconSize(1.5)
                .withDraggable(false)

            val annotation = pointAnnotationManager?.create(annotationOptions)

            if (annotation != null) {
                busStopAnnotations[index] = annotation
                pointAnnotationManager?.addClickListener { clicked ->
                    if (clicked == annotation) {
                        showBusStopDialog(stop, index)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    private fun createBusStopMarker(): android.graphics.Bitmap {
        val size = 96
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val whitePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            isAntiAlias = true
        }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2 - 4).toFloat(), whitePaint)

        val bluePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#4285F4")
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2 - 4).toFloat(), bluePaint)

        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#4285F4")
            textSize = 50f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        val xPos = (size / 2).toFloat()
        val yPos = (size / 2 - (textPaint.descent() + textPaint.ascent()) / 2).toFloat()
        canvas.drawText("\uD83D\uDE8F", xPos, yPos, textPaint)

        return bitmap
    }

    private fun showBusStopDialog(stop: BusStop, index: Int) {
        val timeInfo = calculateTimeRemaining(stop.schedule)

        AlertDialog.Builder(this)
            .setTitle(stop.label)
            .setMessage(timeInfo)
            .setPositiveButton("Yol Tarifi Al") { _, _ ->
                openGoogleMaps(stop.lat, stop.lng)
            }
            .setNegativeButton("Kapat", null)
            .setCancelable(true)
            .show()
    }

    private fun openGoogleMaps(lat: Double, lng: Double) {
        if (currentLocation == null) {
            Toast.makeText(this, "Konum henüz alınmadı", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val gmmIntentUri = Uri.parse(
                "google.navigation:q=$lat,$lng&origin=${currentLocation!!.latitude},${currentLocation!!.longitude}"
            )
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            startActivity(mapIntent)
        } catch (e: Exception) {
            try {
                val webUrl = "https://www.google.com/maps/dir/${currentLocation!!.latitude},${currentLocation!!.longitude}/$lat,$lng"
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                startActivity(webIntent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Yol tarifi açılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startPeriodicPopupUpdate() {
        val updateRunnable = object : Runnable {
            override fun run() {
                busStops.forEachIndexed { index, stop ->
                    val annotation = busStopAnnotations[index]
                    if (annotation != null) {
                        pointAnnotationManager?.update(annotation)
                    }
                }
                popupUpdateHandler.postDelayed(this, 60000)
            }
        }
        popupUpdateHandler.post(updateRunnable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Konum izni verildi", Toast.LENGTH_SHORT).show()
                    enableLocationOnMap()
                    // 500ms sonra diğer izinleri iste
                    Handler(Looper.getMainLooper()).postDelayed({
                        requestOtherPermissions()
                    }, 500)
                } else {
                    Toast.makeText(this, "Konum izni gerekli! Bazı özellikler çalışmayacak.", Toast.LENGTH_LONG).show()
                    // İzin olmasa bile diğer izinleri dene
                    Handler(Looper.getMainLooper()).postDelayed({
                        requestOtherPermissions()
                    }, 500)
                }
            }
            ACTIVITY_RECOGNITION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Adım sayacı izni verildi", Toast.LENGTH_SHORT).show()
                    registerStepCounter()
                } else {
                    Toast.makeText(this, "Adım sayacı izni verilmedi, bu özellik çalışmayacak", Toast.LENGTH_LONG).show()
                }
                // 500ms sonra bildirim iznini iste
                Handler(Looper.getMainLooper()).postDelayed({
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                NOTIFICATION_PERMISSION_REQUEST_CODE
                            )
                        }
                    }
                }, 500)
            }
            BACKGROUND_LOCATION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Arka plan konum izni verildi", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Arka plan konum izni olmadan otomatik gönderim çalışmayabilir", Toast.LENGTH_LONG).show()
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bildirim izni verildi", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Bildirim izni olmadan arka plan servisi görüntülenemez", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        stopContinuousLocationTracking()
        stopAutoLocationSending()
        popupUpdateHandler.removeCallbacksAndMessages(null)
        fetchUsersHandler.removeCallbacksAndMessages(null)
        locationTrackingHandler.removeCallbacksAndMessages(null)
        autoSendHandler.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(stepListener)
        mapView.onDestroy()
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val ACTIVITY_RECOGNITION_REQUEST_CODE = 2
        private const val BACKGROUND_LOCATION_REQUEST_CODE = 3
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4
    }
}