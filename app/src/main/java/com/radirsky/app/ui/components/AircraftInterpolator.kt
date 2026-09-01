package com.radirsky.app.ui.components

import com.radirsky.app.data.model.Aircraft
import kotlin.math.cos
import kotlin.math.sin

data class InterpolatedAircraft(
    val aircraft: Aircraft,
    val renderLat: Double,
    val renderLon: Double
)

class AircraftInterpolator {

    private class TrackedState(
        var currentLat: Double,
        var currentLon: Double,
        var startLat: Double,
        var startLon: Double,
        var targetLat: Double,
        var targetLon: Double,
        var animProgress: Float,
        var aircraft: Aircraft
    )

    private val trackedMap = mutableMapOf<String, TrackedState>()
    private var lastUpdateNanos: Long = 0L

    fun updateTargets(newList: List<Aircraft>) {
        val currentIds = newList.map { it.icao24 }.toSet()
        // Remove aircraft that are no longer reported
        trackedMap.keys.retainAll(currentIds)

        for (newAircraft in newList) {
            val newLat = newAircraft.latitude ?: continue
            val newLon = newAircraft.longitude ?: continue
            val existing = trackedMap[newAircraft.icao24]

            if (existing == null) {
                // New aircraft blip - initialize directly at coordinates
                trackedMap[newAircraft.icao24] = TrackedState(
                    currentLat = newLat,
                    currentLon = newLon,
                    startLat = newLat,
                    startLon = newLon,
                    targetLat = newLat,
                    targetLon = newLon,
                    animProgress = 1.0f,
                    aircraft = newAircraft
                )
            } else {
                // Smooth transition from current position to new position over 1 second
                existing.startLat = existing.currentLat
                existing.startLon = existing.currentLon
                existing.targetLat = newLat
                existing.targetLon = newLon
                existing.animProgress = 0.0f
                existing.aircraft = newAircraft
            }
        }
    }

    fun tick(frameTimeNanos: Long): List<InterpolatedAircraft> {
        if (lastUpdateNanos == 0L) {
            lastUpdateNanos = frameTimeNanos
            return trackedMap.values.map {
                InterpolatedAircraft(it.aircraft, it.currentLat, it.currentLon)
            }
        }

        val dtSeconds = ((frameTimeNanos - lastUpdateNanos) / 1_000_000_000.0).coerceIn(0.001, 0.2)
        lastUpdateNanos = frameTimeNanos

        val result = mutableListOf<InterpolatedAircraft>()

        for (state in trackedMap.values) {
            val velocity = state.aircraft.velocity ?: 0.0
            val heading = state.aircraft.trueTrack

            // Dead Reckoning: Continuously project target location along heading vector at reported velocity
            if (velocity > 0.0 && heading != null && heading.isFinite()) {
                val headingRad = Math.toRadians(heading)
                val distMeters = velocity * dtSeconds
                val dLat = (distMeters * cos(headingRad)) / 111000.0
                val cosLat = cos(Math.toRadians(state.targetLat)).coerceAtLeast(0.1)
                val dLon = (distMeters * sin(headingRad)) / (111000.0 * cosLat)

                if (dLat.isFinite() && dLon.isFinite()) {
                    state.targetLat += dLat
                    state.targetLon += dLon
                }
            }

            // Smooth Interpolation from start to target over 1.0 second
            if (state.animProgress < 1.0f) {
                state.animProgress = (state.animProgress + (dtSeconds / 1.0f).toFloat()).coerceAtMost(1.0f)
                val p = state.animProgress.toDouble()
                state.currentLat = state.startLat + (state.targetLat - state.startLat) * p
                state.currentLon = state.startLon + (state.targetLon - state.startLon) * p
            } else {
                state.currentLat = state.targetLat
                state.currentLon = state.targetLon
            }

            if (state.currentLat.isFinite() && state.currentLon.isFinite()) {
                result.add(InterpolatedAircraft(state.aircraft, state.currentLat, state.currentLon))
            }
        }

        return result
    }
}
