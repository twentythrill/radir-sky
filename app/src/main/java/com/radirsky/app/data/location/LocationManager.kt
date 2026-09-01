package com.radirsky.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager as AndroidLocationManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LocationCoordinates(val latitude: Double, val longitude: Double, val isGps: Boolean)

class LocationManager(private val context: Context, private val httpClient: HttpClient) {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    @SuppressLint("MissingPermission")
    suspend fun getBestLocation(): LocationCoordinates = withContext(Dispatchers.IO) {
        val gpsLocation = tryGetGpsLocation()
        if (gpsLocation != null) {
            return@withContext LocationCoordinates(gpsLocation.latitude, gpsLocation.longitude, isGps = true)
        }

        val ipLocation = tryGetIpLocation()
        if (ipLocation != null) {
            return@withContext ipLocation
        }

        // Fallback default coordinates (Zurich, CH) if GPS and IP are both unavailable
        return@withContext LocationCoordinates(47.3769, 8.5417, isGps = false)
    }

    @SuppressLint("MissingPermission")
    private fun tryGetGpsLocation(): Location? {
        return try {
            val systemLocationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as? AndroidLocationManager
                    ?: return null
            systemLocationManager.getLastKnownLocation(AndroidLocationManager.GPS_PROVIDER)
                ?: systemLocationManager.getLastKnownLocation(AndroidLocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryGetIpLocation(): LocationCoordinates? {
        return try {
            val responseText = httpClient.get("https://freeipapi.com/api/json").bodyAsText()
            val jsonObj = jsonParser.parseToJsonElement(responseText).jsonObject
            val lat = jsonObj["latitude"]?.jsonPrimitive?.doubleOrNull
            val lon = jsonObj["longitude"]?.jsonPrimitive?.doubleOrNull
            if (lat != null && lon != null) {
                LocationCoordinates(lat, lon, isGps = false)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}

