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
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
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

data class UserLocationData(
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val userType: String, // "bus" veya "taxi"
    val phoneNumber: String? = null
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
    private var userPhoneNumber: String = ""
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
        Priority.PRIORITY_HIGH_ACCURACY, 5000
    ).apply {
        setMinUpdateIntervalMillis(3000)
        setMaxUpdateDelayMillis(10000)
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

        // SharedPreferences'tan userId ve telefon numarasını yükle
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        userId = prefs.getString("userId", UUID.randomUUID().toString().take(8)) ?: UUID.randomUUID().toString().take(8)
        userPhoneNumber = prefs.getString("phoneNumber", "") ?: ""

        // userId'yi kaydet
        prefs.edit().putString("userId", userId).apply()

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

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepCounterSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        setupLocationCallback()
        initializeBusStops()
        setupDynamicButtons()

        mapView.getMapboxMap().loadStyleUri(com.mapbox.maps.Style.MAPBOX_STREETS) {
            addBusStopMarkers()
            startFetchingUserLocations()
            startPeriodicPopupUpdate()
        }

        sendLocationButton.setOnClickListener {
            if (!isLocationTrackingActive) {
                // Manuel mod başlatmadan önce telefon numarası iste
                showPhoneNumberDialog()
            } else {
                stopContinuousLocationTracking()
            }
        }

        checkAndRequestPermissions()
    }

    private fun showPhoneNumberDialog() {


        if (userPhoneNumber.isNotEmpty()) {
            startContinuousLocationTracking()
            return
        }


        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "Telefon numaranızı girin (05XX XXX XX XX)"
            setText(userPhoneNumber)
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("📱 Telefon Numarası")
            .setMessage("Taksi arkadaşı arayanlar sizinle iletişime geçebilsin mi?")
            .setView(input)
            .setPositiveButton("Başlat") { _, _ ->
                val phone = input.text.toString().trim()
                if (phone.isNotEmpty()) {
                    userPhoneNumber = phone
                    // Telefon numarasını kaydet
                    getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("phoneNumber", phone)
                        .apply()

                    startContinuousLocationTracking()
                } else {
                    Toast.makeText(this, "Telefon numarası girilmedi!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun checkAndRequestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
        } else {
            enableLocationOnMap()
            checkOtherPermissions()
        }
    }

    private fun checkOtherPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                registerStepCounter()
            }
        } else {
            registerStepCounter()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Sessizce kaydet
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
            Handler(Looper.getMainLooper()).postDelayed({
                requestOtherPermissions()
            }, 500)
        }
    }

    private fun requestOtherPermissions() {
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
                Telefon: $userPhoneNumber
                
                SERVER BİLGİLERİ:
                Backend: ${Constants.SEND_LOCATION_URL}
                
                DURUM:
                Adımlar: $stepsSinceLeave
                Durağın altındayım: $wasAtStop
                Durakta bekleme süresi: ${secondsAtStop}s
                En yakın durak: $nearestStopName
                Otomatik gönderim (Otobüs): $isAutoSending
                Manuel gönderim (Taksi): $isLocationTrackingActive
                Mevcut Konum: ${currentLocation?.latitude}, ${currentLocation?.longitude}
                
                DİĞER KULLANICILAR:
                Haritada görünen: ${userLocationAnnotations.size}
            """.trimIndent()

            AlertDialog.Builder(this)
                .setTitle("Debug Bilgisi")
                .setMessage(debugText)
                .setPositiveButton("Tamam", null)
                .show()
        }

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
            if (!wasAtStop) {
                stopStartTime = System.currentTimeMillis()
                initialStepCount = -1
                stepsSinceLeave = 0
                Toast.makeText(this, "Durağa girdiniz: ${nearestStop?.label}", Toast.LENGTH_SHORT).show()
            }
            wasAtStop = true
            currentNearbyBusStop = nearestStop
        } else {
            if (wasAtStop) {
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
        Toast.makeText(this, "Otobüste olduğunuz algılandı, konumunuz paylaşılıyor (Arka planda çalışır)", Toast.LENGTH_LONG).show()

        val serviceIntent = Intent(this, LocationForegroundService::class.java)
        serviceIntent.putExtra("userId", userId)
        serviceIntent.putExtra("isManual", false)
        serviceIntent.putExtra("phoneNumber", "")
        serviceIntent.putExtra("userType", "bus")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        autoSendRunnable = object : Runnable {
            override fun run() {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                AlertDialog.Builder(this)
                    .setTitle("Arka Plan Konum İzni")
                    .setMessage("Otobüslerin konumunun canlı takip edilebilmesi için arka plan konum iznine ihtiyaç var.")
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

        Log.d(TAG, "Konum güncellemeleri başlatıldı")
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun startContinuousLocationTracking() {
        isLocationTrackingActive = true
        sendLocationButton.text = "Aramayı Durdur"
        sendLocationButton.setBackgroundColor(Color.RED)

        Toast.makeText(this, "Taksi arkadaşı arama talebiniz alındı (Arka planda çalışır)", Toast.LENGTH_LONG).show()

        val serviceIntent = Intent(this, LocationForegroundService::class.java)
        serviceIntent.putExtra("userId", userId)
        serviceIntent.putExtra("isManual", true)
        serviceIntent.putExtra("phoneNumber", userPhoneNumber)
        serviceIntent.putExtra("userType", "taxi")

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
        sendLocationButton.text = "Taksi arkadaşı ara"
        sendLocationButton.setBackgroundColor(Color.parseColor("#4285F4"))

        sendLocationRunnable?.let { locationTrackingHandler.removeCallbacks(it) }

        if (!isAutoSending) {
            val serviceIntent = Intent(this, LocationForegroundService::class.java)
            stopService(serviceIntent)
        }

        Toast.makeText(this, "Taksi arkadaşı arama durduruldu", Toast.LENGTH_SHORT).show()
    }

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
                                val userLocations = mutableListOf<UserLocationData>()

                                for (i in 0 until dataArray.length()) {
                                    val obj = dataArray.getJSONObject(i)
                                    val fetchedUserId = obj.getString("userId")
                                    val latitude = obj.getDouble("latitude")
                                    val longitude = obj.getDouble("longitude")
                                    val userType = obj.optString("userType", "bus")
                                    val phoneNumber = obj.optString("phoneNumber", null)

                                    userLocations.add(
                                        UserLocationData(
                                            fetchedUserId,
                                            latitude,
                                            longitude,
                                            userType,
                                            phoneNumber
                                        )
                                    )
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

    private fun updateUserMarkersOnMap(userLocations: List<UserLocationData>) {
        val otherUsers = userLocations.filter { it.userId != userId }

        Log.d(TAG, "Haritada gösterilecek kullanıcılar: ${otherUsers.size}")

        val toRemove = mutableListOf<String>()
        userLocationAnnotations.forEach { (userId, annotation) ->
            if (otherUsers.none { it.userId == userId }) {
                pointAnnotationManager?.delete(annotation)
                toRemove.add(userId)
            }
        }
        toRemove.forEach { userLocationAnnotations.remove(it) }

        otherUsers.forEach { userData ->
            val point = Point.fromLngLat(userData.longitude, userData.latitude)

            if (userLocationAnnotations.containsKey(userData.userId)) {
                val annotation = userLocationAnnotations[userData.userId]
                if (annotation != null) {
                    annotation.point = point
                    pointAnnotationManager?.update(annotation)
                    Log.d(TAG, "Marker güncellendi: ${userData.userId}")
                }
            } else {
                val iconName = if (userData.userType == "taxi") "taxi-icon" else "bus-icon"

                val annotationOptions = PointAnnotationOptions()
                    .withPoint(point)
                    .withIconImage(iconName)
                    .withIconSize(1.2)

                val annotation = pointAnnotationManager?.create(annotationOptions)
                if (annotation != null) {
                    userLocationAnnotations[userData.userId] = annotation
                    Log.d(TAG, "Yeni marker eklendi: ${userData.userId} (${userData.userType})")

                    pointAnnotationManager?.addClickListener { clicked ->
                        if (clicked == annotation) {
                            val message = if (userData.userType == "taxi" && !userData.phoneNumber.isNullOrEmpty()) {
                                "📱 Telefon: ${userData.phoneNumber}\n🚕 Taksi"
                            } else {
                                "🚌 Otobüs\nID: ${userData.userId.take(6)}"
                            }

                            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
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

    private fun createBusIcon(): android.graphics.Bitmap {
        val size = 80
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val textPaint = android.graphics.Paint().apply {
            textSize = 60f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        val xPos = (size / 2).toFloat()
        val yPos = (size / 2 - (textPaint.descent() + textPaint.ascent()) / 2).toFloat()
        canvas.drawText("🚌", xPos, yPos, textPaint)

        return bitmap
    }

    private fun createTaxiIcon(): android.graphics.Bitmap {
        val size = 80
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        val textPaint = android.graphics.Paint().apply {
            textSize = 60f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        val xPos = (size / 2).toFloat()
        val yPos = (size / 2 - (textPaint.descent() + textPaint.ascent()) / 2).toFloat()
        canvas.drawText("🚕", xPos, yPos, textPaint)

        return bitmap
    }

    private fun addBusStopMarkers() {
        val annotationApi = mapView.annotations
        pointAnnotationManager = annotationApi.createPointAnnotationManager()

        val busStopMarker = createBusStopMarker()
        val busIcon = createBusIcon()
        val taxiIcon = createTaxiIcon()

        try {
            mapView.getMapboxMap().getStyle { style ->
                style.addImage("bus-stop-icon", busStopMarker, false)
                style.addImage("bus-icon", busIcon, false)
                style.addImage("taxi-icon", taxiIcon, false)
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
        canvas.drawText("🚏", xPos, yPos, textPaint)

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
                    Handler(Looper.getMainLooper()).postDelayed({
                        requestOtherPermissions()
                    }, 500)
                } else {
                    Toast.makeText(this, "Konum izni gerekli!", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "Adım sayacı izni verilmedi", Toast.LENGTH_LONG).show()
                }
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
        // Konum güncellemelerini DURDURMUYORUZ - sürekli çalışsın
        // stopLocationUpdates()
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