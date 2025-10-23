package com.example.otobustest

object Constants {
    // Backend URL - localhost yerine bilgisayarınızın IP adresini yazın
    // Örnek: private const val BASE_URL = "http://192.168.1.100:3000"
    // Android emulator için: 10.0.2.2
    // Fiziksel cihaz için: bilgisayarınızın yerel IP'si
    private const val BASE_URL = "http://10.90.3.14:3000"

    // API Endpoints
    const val SEND_LOCATION_URL = "$BASE_URL/konum"
    const val GET_LOCATIONS_URL = "$BASE_URL/konum/users"
    const val GET_STATS_URL = "$BASE_URL/stats"

    // API Key - config.txt'deki API_KEY ile aynı olmalı!
    const val API_KEY = "b7e3f4a9c2d1e8f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2"

    // Location Settings
    const val LOCATION_UPDATE_INTERVAL = 5000L // 5 saniye
    const val LOCATION_FASTEST_INTERVAL = 5000L // 5 saniye
    const val LOCATION_MAX_WAIT_TIME = 10000L // 10 saniye

    // WebSocket URL (opsiyonel)
    const val WEBSOCKET_URL = BASE_URL

    // Timeout ayarları
    const val CONNECTION_TIMEOUT = 30L // saniye
    const val READ_TIMEOUT = 30L // saniye
    const val WRITE_TIMEOUT = 30L // saniye
}