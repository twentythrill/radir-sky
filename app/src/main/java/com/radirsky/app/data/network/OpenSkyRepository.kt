package com.radirsky.app.data.network

import com.radirsky.app.data.model.Aircraft
import com.radirsky.app.data.model.Lce
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

class OpenSkyRepository(private val httpClient: HttpClient) {

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun fetchAircraftInBoundingBox(
        lat: Double,
        lon: Double,
        radiusDegrees: Double = 0.3,
        username: String? = null,
        password: String? = null
    ): Lce<List<Aircraft>> = withContext(Dispatchers.IO) {
        val safeRadius = radiusDegrees.coerceIn(0.04, 0.8)

        val lamin = lat - safeRadius
        val lamax = lat + safeRadius
        val lomin = lon - safeRadius
        val lomax = lon + safeRadius

        val url = "https://opensky-network.org/api/states/all?lamin=$lamin&lamax=$lamax&lomin=$lomin&lomax=$lomax"

        return@withContext try {
            val response: HttpResponse = httpClient.get(url) {
                if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
                    val authString = "$username:$password"
                    val encodedAuth = java.util.Base64.getEncoder().encodeToString(authString.toByteArray())
                    header("Authorization", "Basic $encodedAuth")
                }
            }

            if (response.status == HttpStatusCode.TooManyRequests) {
                return@withContext Lce.Error("OpenSky API rate limit reached (429).", isRateLimit = true)
            }

            if (response.status != HttpStatusCode.OK) {
                return@withContext Lce.Error("OpenSky API error: HTTP ${response.status.value}")
            }

            val bodyText = response.bodyAsText()
            val jsonObj = jsonParser.parseToJsonElement(bodyText).jsonObject
            val statesArray = jsonObj["states"] as? JsonArray ?: return@withContext Lce.Content(emptyList())

            val aircraftList = mutableListOf<Aircraft>()

            for (element in statesArray) {
                if (aircraftList.size >= 80) break

                try {
                    val state = element as? JsonArray ?: continue
                    if (state.size < 12) continue

                    val icao24 = state[0].asString() ?: continue
                    val callsign = state[1].asString()?.trim() ?: icao24
                    val originCountry = state[2].asString() ?: "Unknown"
                    val lastContact = state[4].asLong()
                    val longitude = state[5].asDouble()
                    val latitude = state[6].asDouble()
                    val baroAlt = state[7].asDouble()
                    val velocity = state[9].asDouble()
                    val trueTrack = state[10].asDouble()
                    val verticalRate = state[11].asDouble()

                    if (longitude != null && latitude != null && longitude.isFinite() && latitude.isFinite()) {
                        aircraftList.add(
                            Aircraft(
                                icao24 = icao24,
                                callsign = if (callsign.isEmpty()) icao24.uppercase() else callsign,
                                originCountry = originCountry,
                                longitude = longitude,
                                latitude = latitude,
                                barometricAltitude = baroAlt,
                                velocity = velocity,
                                trueTrack = trueTrack,
                                verticalRate = verticalRate,
                                lastContact = lastContact
                            )
                        )
                    }
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
            Lce.Error("Connection Error: ${e.localizedMessage ?: "Failed to reach OpenSky"}")
        }
    }

    private fun JsonElement?.asString(): String? {
        if (this == null) return null
        val prim = this as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content else prim.content
    }

    private fun JsonElement?.asDouble(): Double? {
        if (this == null) return null
        val prim = this as? JsonPrimitive ?: return null
        return prim.doubleOrNull
    }

    private fun JsonElement?.asLong(): Long? {
        if (this == null) return null
        val prim = this as? JsonPrimitive ?: return null
        return prim.longOrNull
    }
}
