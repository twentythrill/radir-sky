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

class FlightRadarRepository(private val httpClient: HttpClient) {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun fetchAircraft(
        lat: Double,
        lon: Double,
        radiusKm: Double = 35.0
    ): Lce<List<Aircraft>> = withContext(Dispatchers.IO) {
        // Attempt Primary API: ADSB.lol
        val adsbResult = fetchFromAdsbLol(lat, lon)
        if (adsbResult is Lce.Content && adsbResult.data.isNotEmpty()) {
            return@withContext adsbResult
        }

        // Automatic Resilient Fallback: OpenSky Network API
        val openSkyResult = fetchFromOpenSky(lat, lon, radiusKm)
        if (openSkyResult is Lce.Content && openSkyResult.data.isNotEmpty()) {
            return@withContext openSkyResult
        }

        // Return whichever result has content or error status
        return@withContext if (adsbResult is Lce.Content) openSkyResult else adsbResult
    }

    private suspend fun fetchFromAdsbLol(lat: Double, lon: Double): Lce<List<Aircraft>> {
        val url = "https://api.adsb.lol/v2/lat/$lat/lon/$lon/dist/25"

        return try {
            val response: HttpResponse = httpClient.get(url)

            if (response.status == HttpStatusCode.TooManyRequests) {
                return Lce.Error("ADSB.lol rate limit (429)", isRateLimit = true)
            }

            if (response.status != HttpStatusCode.OK) {
                return Lce.Error("ADSB.lol HTTP ${response.status.value}")
            }

            val bodyText = response.bodyAsText()
            val jsonObj = jsonParser.parseToJsonElement(bodyText).jsonObject
            val acArray = jsonObj["ac"] as? JsonArray ?: return Lce.Content(emptyList())

            val list = mutableListOf<Aircraft>()
            for (element in acArray) {
                if (list.size >= 120) break
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

                    list.add(
                        Aircraft(
                            icao24 = hex,
                            callsign = callsign,
                            originCountry = "ADSB.lol",
                            longitude = aLon,
                            latitude = aLat,
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
            val closest25 = list
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
            Lce.Error("ADSB.lol error: ${e.localizedMessage}")
        }
    }

    private suspend fun fetchFromOpenSky(
        lat: Double,
        lon: Double,
        radiusKm: Double
    ): Lce<List<Aircraft>> {
        val radiusDegrees = (radiusKm / 111.0).coerceIn(0.04, 0.8)
        val lamin = lat - radiusDegrees
        val lamax = lat + radiusDegrees
        val lomin = lon - radiusDegrees
        val lomax = lon + radiusDegrees

        val url = "https://opensky-network.org/api/states/all?lamin=$lamin&lamax=$lamax&lomin=$lomin&lomax=$lomax"

        return try {
            val response: HttpResponse = httpClient.get(url)

            if (response.status == HttpStatusCode.TooManyRequests) {
                return Lce.Error("OpenSky rate limit (429)", isRateLimit = true)
            }

            if (response.status != HttpStatusCode.OK) {
                return Lce.Error("OpenSky HTTP ${response.status.value}")
            }

            val bodyText = response.bodyAsText()
            val jsonObj = jsonParser.parseToJsonElement(bodyText).jsonObject
            val statesArray = jsonObj["states"] as? JsonArray ?: return Lce.Content(emptyList())

            val list = mutableListOf<Aircraft>()
            for (element in statesArray) {
                if (list.size >= 80) break
                try {
                    val state = element as? JsonArray ?: continue
                    if (state.size < 12) continue

                    val icao24 = state[0].asString() ?: continue
                    val callsign = state[1].asString()?.trim() ?: icao24
                    val originCountry = state[2].asString() ?: "Unknown"
                    val aLon = state[5].asDouble()
                    val aLat = state[6].asDouble()
                    val baroAlt = state[7].asDouble()
                    val velocity = state[9].asDouble()
                    val trueTrack = state[10].asDouble()
                    val verticalRate = state[11].asDouble()

                    if (aLat == null || aLon == null || !aLat.isFinite() || !aLon.isFinite()) continue

                    list.add(
                        Aircraft(
                            icao24 = icao24,
                            callsign = if (callsign.isEmpty()) icao24.uppercase() else callsign,
                            originCountry = originCountry,
                            longitude = aLon,
                            latitude = aLat,
                            barometricAltitude = baroAlt,
                            velocity = velocity,
                            trueTrack = trueTrack,
                            verticalRate = verticalRate,
                            lastContact = System.currentTimeMillis() / 1000L
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    continue
                }
            }
            val closest25 = list
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
            Lce.Error("OpenSky error: ${e.localizedMessage}")
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
