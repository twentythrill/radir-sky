package com.radirsky.app.data.network

import com.radirsky.app.data.model.Aircraft
import com.radirsky.app.data.model.Lce
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AdsbFiRepository(private val httpClient: HttpClient) {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun fetchAircraftNearLocation(
        lat: Double,
        lon: Double,
        distanceNauticalMiles: Int = 25
    ): Lce<List<Aircraft>> = withContext(Dispatchers.IO) {
        val roundedLat = String.format(java.util.Locale.US, "%.3f", lat)
        val roundedLon = String.format(java.util.Locale.US, "%.3f", lon)
        val url = "https://opendata.adsb.fi/api/v2/lat/$roundedLat/lon/$roundedLon/dist/$distanceNauticalMiles"

        return@withContext try {
            val response: HttpResponse = httpClient.get(url)

            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryHeader = response.headers["Retry-After"]?.toLongOrNull()
                    ?: response.headers["x-rate-limit-retry-after-seconds"]?.toLongOrNull()
                return@withContext Lce.Error(
                    message = "adsb.fi rate limit (429)",
                    isRateLimit = true,
                    retryAfterSeconds = retryHeader
                )
            }

            if (response.status != HttpStatusCode.OK) {
                return@withContext Lce.Error("adsb.fi HTTP ${response.status.value}", isRateLimit = false)
            }

            val bodyText = response.bodyAsText()
            val jsonObj = jsonParser.parseToJsonElement(bodyText).jsonObject
            val acArray = jsonObj["aircraft"] as? JsonArray ?: return@withContext Lce.Content(emptyList())

            val aircraftList = mutableListOf<Aircraft>()

            for (element in acArray) {
                if (aircraftList.size >= 80) break

                try {
                    val acObj = element as? JsonObject ?: continue
                    val hex = acObj["hex"]?.asString() ?: continue
                    val flight = acObj["flight"]?.asString()?.trim()
                    val aLat = acObj["lat"]?.asDouble()
                    val aLon = acObj["lon"]?.asDouble()

                    if (aLat == null || aLon == null || !aLat.isFinite() || !aLon.isFinite()) continue

                    val altBaroFeet = parseAltBaro(acObj["alt_baro"])
                    val altMeters = altBaroFeet?.times(0.3048)
                    val gsKnots = acObj["gs"]?.asDouble()
                    val velocityMs = gsKnots?.times(0.514444)
                    val trackHeading = acObj["track"]?.asDouble()
                    val baroRateFtMin = acObj["baro_rate"]?.asDouble()
                    val verticalRateMs = baroRateFtMin?.times(0.00508)
                    val callsign = if (flight.isNullOrEmpty()) hex.uppercase() else flight

                    aircraftList.add(
                        Aircraft(
                            icao24 = hex,
                            callsign = callsign,
                            originCountry = "adsb.fi",
                            longitude = aLon,
                            latitude = aLat,
                            barometricAltitude = altMeters,
                            velocity = velocityMs,
                            trueTrack = trackHeading,
                            verticalRate = verticalRateMs,
                            lastContact = System.currentTimeMillis() / 1000L,
                            dataSource = "ADSB.FI"
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
            }

            val closest25 = aircraftList
                .sortedBy { ac ->
                    val aLat = ac.latitude ?: lat
                    val aLon = ac.longitude ?: lon
                    val dLat = aLat - lat
                    val dLon = aLon - lon
                    (dLat * dLat + dLon * dLon)
                }
                .take(25)

            Lce.Content(closest25)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Lce.Error("adsb.fi error: ${e.localizedMessage ?: "Failed to reach adsb.fi"}")
        }
    }

    private fun parseAltBaro(element: JsonElement?): Double? {
        if (element == null) return null
        val prim = element as? JsonPrimitive ?: return null
        if (prim.isString) {
            val str = prim.content.trim().lowercase()
            if (str == "ground") return 0.0
            return str.toDoubleOrNull()
        }
        return prim.doubleOrNull
    }

    private fun JsonElement?.asString(): String? {
        if (this == null) return null
        val prim = this as? JsonPrimitive ?: return null
        return prim.content
    }

    private fun JsonElement?.asDouble(): Double? {
        if (this == null) return null
        val prim = this as? JsonPrimitive ?: return null
        return prim.doubleOrNull
    }
}
