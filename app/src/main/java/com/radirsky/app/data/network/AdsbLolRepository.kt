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

class AdsbLolRepository(private val httpClient: HttpClient) {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun fetchAircraftNearLocation(
        lat: Double,
        lon: Double,
        distanceNauticalMiles: Int = 25
    ): Lce<List<Aircraft>> = withContext(Dispatchers.IO) {
        val url = "https://api.adsb.lol/v2/lat/$lat/lon/$lon/dist/$distanceNauticalMiles"

        return@withContext try {
            val response: HttpResponse = httpClient.get(url)

            if (response.status == HttpStatusCode.TooManyRequests) {
                return@withContext Lce.Error("ADSB.lol API rate limit (HTTP 429). Backing off 30s.", isRateLimit = true)
            }

            if (response.status != HttpStatusCode.OK) {
                return@withContext Lce.Error("ADSB.lol API error: HTTP ${response.status.value}", isRateLimit = true)
            }

            val bodyText = response.bodyAsText()
            val jsonObj = jsonParser.parseToJsonElement(bodyText).jsonObject
            val acArray = jsonObj["ac"] as? JsonArray ?: return@withContext Lce.Content(emptyList())

            val aircraftList = mutableListOf<Aircraft>()

            for (element in acArray) {
                if (aircraftList.size >= 80) break

                try {
                    val acObj = element as? JsonObject ?: continue

                    val hex = acObj["hex"]?.asString() ?: continue
                    val flight = acObj["flight"]?.asString()?.trim()
                    val aircraftLat = acObj["lat"]?.asDouble()
                    val aircraftLon = acObj["lon"]?.asDouble()

                    if (aircraftLat == null || aircraftLon == null || !aircraftLat.isFinite() || !aircraftLon.isFinite()) {
                        continue
                    }

                    val altBaroFeet = parseAltBaro(acObj["alt_baro"])
                    val altMeters = altBaroFeet?.times(0.3048) // Convert feet to meters

                    val gsKnots = acObj["gs"]?.asDouble()
                    val velocityMs = gsKnots?.times(0.514444) // Convert knots to m/s

                    val trackHeading = acObj["track"]?.asDouble()

                    val baroRateFtMin = acObj["baro_rate"]?.asDouble()
                    val verticalRateMs = baroRateFtMin?.times(0.00508) // Convert ft/min to m/s

                    val callsign = if (flight.isNullOrEmpty()) hex.uppercase() else flight

                    aircraftList.add(
                        Aircraft(
                            icao24 = hex,
                            callsign = callsign,
                            originCountry = "ADSB.lol",
                            longitude = aircraftLon,
                            latitude = aircraftLat,
                            barometricAltitude = altMeters,
                            velocity = velocityMs,
                            trueTrack = trackHeading,
                            verticalRate = verticalRateMs,
                            lastContact = System.currentTimeMillis() / 1000L
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
            }

            // Hard limit: Sort by distance from center location and take at most 25 closest aircraft
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
            Lce.Error("ADSB.lol Connection Error: ${e.localizedMessage ?: "Failed to reach ADSB.lol"}")
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
