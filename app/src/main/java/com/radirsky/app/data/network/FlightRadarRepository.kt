package com.radirsky.app.data.network

import com.radirsky.app.data.model.Aircraft
import com.radirsky.app.data.model.Lce
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FlightRadarRepository(httpClient: HttpClient) {

    private val adsbLolRepo = AdsbLolRepository(httpClient)
    private val adsbFiRepo = AdsbFiRepository(httpClient)

    // Cooldown timers in epoch milliseconds
    @Volatile
    private var adsbLolCooldownUntil: Long = 0L
    @Volatile
    private var adsbFiCooldownUntil: Long = 0L

    private var adsbLolFailures = 0
    private var adsbFiFailures = 0
    private var rotationCounter = 0

    suspend fun fetchAircraft(
        lat: Double,
        lon: Double,
        radiusKm: Double = 35.0
    ): Lce<List<Aircraft>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val distNauticalMiles = ((radiusKm * 0.539957).toInt()).coerceIn(10, 50)

        val lolAvailable = now >= adsbLolCooldownUntil
        val fiAvailable = now >= adsbFiCooldownUntil

        // Round-robin selection: Alternate between available providers
        rotationCounter++
        val preferLol = (rotationCounter % 2 == 0)

        val isPrimaryLol = when {
            lolAvailable && fiAvailable -> preferLol
            lolAvailable -> true
            fiAvailable -> false
            else -> adsbLolCooldownUntil <= adsbFiCooldownUntil
        }

        // 1. Fetch from Primary Provider
        val primaryResult = if (isPrimaryLol) {
            adsbLolRepo.fetchAircraftNearLocation(lat, lon, distNauticalMiles)
        } else {
            adsbFiRepo.fetchAircraftNearLocation(lat, lon, distNauticalMiles)
        }

        if (primaryResult is Lce.Content) {
            if (isPrimaryLol) adsbLolFailures = 0 else adsbFiFailures = 0
            return@withContext primaryResult
        }

        // Handle Primary Error & Backoff
        if (primaryResult is Lce.Error && primaryResult.isRateLimit) {
            if (isPrimaryLol) {
                adsbLolFailures++
                val backoffSec = primaryResult.retryAfterSeconds ?: (60L * (1 shl (adsbLolFailures - 1).coerceAtMost(3)))
                adsbLolCooldownUntil = now + (backoffSec * 1000L)
            } else {
                adsbFiFailures++
                val backoffSec = primaryResult.retryAfterSeconds ?: (60L * (1 shl (adsbFiFailures - 1).coerceAtMost(3)))
                adsbFiCooldownUntil = now + (backoffSec * 1000L)
            }
        }

        // 2. Seamless Failover to Secondary Provider
        val secondaryResult = if (isPrimaryLol) {
            adsbFiRepo.fetchAircraftNearLocation(lat, lon, distNauticalMiles)
        } else {
            adsbLolRepo.fetchAircraftNearLocation(lat, lon, distNauticalMiles)
        }

        if (secondaryResult is Lce.Content) {
            if (!isPrimaryLol) adsbLolFailures = 0 else adsbFiFailures = 0
            return@withContext secondaryResult
        }

        if (secondaryResult is Lce.Error && secondaryResult.isRateLimit) {
            if (!isPrimaryLol) {
                adsbLolFailures++
                val backoffSec = secondaryResult.retryAfterSeconds ?: (60L * (1 shl (adsbLolFailures - 1).coerceAtMost(3)))
                adsbLolCooldownUntil = now + (backoffSec * 1000L)
            } else {
                adsbFiFailures++
                val backoffSec = secondaryResult.retryAfterSeconds ?: (60L * (1 shl (adsbFiFailures - 1).coerceAtMost(3)))
                adsbFiCooldownUntil = now + (backoffSec * 1000L)
            }
        }

        // Both failed, return the primary error
        return@withContext primaryResult
    }
}
