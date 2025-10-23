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
    const val API_KEY = "BURAYA_KENDİ_OLUSTURDUGUNUZ_APİ_KEY_İ_GİRİN"

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
