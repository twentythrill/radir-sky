package com.radirsky.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.radirsky.app.data.location.LocationCoordinates
import com.radirsky.app.data.location.LocationManager
import com.radirsky.app.data.model.Aircraft
import com.radirsky.app.data.model.Lce
import com.radirsky.app.data.network.FlightRadarRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RadarViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Balanced 25-second polling interval (144 requests/hour total)
        // With 50/50 dual-provider rotation: ~72 req/hour per provider (~1 req / 50s)
        private const val POLLING_INTERVAL_MS = 25000L
        // Initial 60-second backoff when throttled (prevents repeated hits during penalty window)
        private const val BASE_BACKOFF_MS = 60000L
        private const val MAX_BACKOFF_MS = 600000L // 10 minutes maximum backoff
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        defaultRequest {
            header(
                HttpHeaders.UserAgent,
                "RadirSky-DeskToy/1.0 (Rabbit R1; Android; +https://github.com/twentythrill/radir-sky)"
            )
        }
    }

    private val repository = FlightRadarRepository(httpClient)
    private val locationManager = LocationManager(application, httpClient)

    private val _uiState = MutableStateFlow<Lce<List<Aircraft>>>(Lce.Loading)
    val uiState: StateFlow<Lce<List<Aircraft>>> = _uiState.asStateFlow()

    private val _userLocation = MutableStateFlow<LocationCoordinates?>(null)
    val userLocation: StateFlow<LocationCoordinates?> = _userLocation.asStateFlow()

    // Zoom level in radius kilometers (clamped between 5km and 150km)
    private val _zoomRadiusKm = MutableStateFlow(30f)
    val zoomRadiusKm: StateFlow<Float> = _zoomRadiusKm.asStateFlow()

    // Manual Pan Offset in Latitude / Longitude degrees from user location
    private val _panOffsetLatLon = MutableStateFlow(Pair(0.0, 0.0))
    val panOffsetLatLon: StateFlow<Pair<Double, Double>> = _panOffsetLatLon.asStateFlow()

    private val _selectedAircraft = MutableStateFlow<Aircraft?>(null)
    val selectedAircraft: StateFlow<Aircraft?> = _selectedAircraft.asStateFlow()

    private val _lastUpdateTime = MutableStateFlow(0L)
    val lastUpdateTime: StateFlow<Long> = _lastUpdateTime.asStateFlow()

    private var pollingJob: Job? = null
    private val fetchMutex = Mutex()
    private var consecutiveRateLimits = 0

    init {
        fetchLocation()
    }

    fun fetchLocation() {
        viewModelScope.launch(Dispatchers.IO) {
            val loc = locationManager.getBestLocation()
            withContext(Dispatchers.Main) {
                _userLocation.value = loc
            }
            performFetch()
        }
    }

    fun onResume() {
        startPolling()
    }

    fun onPause() {
        stopPolling()
    }

    private fun startPolling() {
        stopPolling()

        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val result = performFetch()

                if (result is Lce.Error && result.isRateLimit) {
                    consecutiveRateLimits++
                    val retryAfterMs = result.retryAfterSeconds?.times(1000L)
                    val exponentialBackoffMs = (BASE_BACKOFF_MS * (1 shl (consecutiveRateLimits - 1).coerceAtMost(3)))
                        .coerceAtMost(MAX_BACKOFF_MS)
                    val backoffDelay = (retryAfterMs ?: exponentialBackoffMs).coerceAtMost(MAX_BACKOFF_MS)
                    delay(backoffDelay)
                } else {
                    if (result is Lce.Content) {
                        consecutiveRateLimits = 0
                    }
                    delay(POLLING_INTERVAL_MS)
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun performFetch(): Lce<List<Aircraft>>? {
        // Deduplicate overlapping fetches across lifecycle and location triggers
        if (!fetchMutex.tryLock()) {
            return null
        }
        return try {
            val loc = _userLocation.value ?: return null
            val centerLat = loc.latitude + _panOffsetLatLon.value.first
            val centerLon = loc.longitude + _panOffsetLatLon.value.second
            val zoomRadius = _zoomRadiusKm.value.toDouble()

            val result = repository.fetchAircraft(
                lat = centerLat,
                lon = centerLon,
                radiusKm = zoomRadius
            )

            withContext(Dispatchers.Main) {
                _uiState.value = result
                if (result is Lce.Content) {
                    _lastUpdateTime.value = System.currentTimeMillis()
                }
            }
            result
        } finally {
            fetchMutex.unlock()
        }
    }

    fun zoomIn() {
        _zoomRadiusKm.value = (_zoomRadiusKm.value * 0.75f).coerceAtLeast(5f)
    }

    fun zoomOut() {
        _zoomRadiusKm.value = (_zoomRadiusKm.value * 1.35f).coerceAtMost(150f)
    }

    fun onPan(deltaLat: Double, deltaLon: Double) {
        val current = _panOffsetLatLon.value
        _panOffsetLatLon.value = Pair(current.first + deltaLat, current.second + deltaLon)
    }

    fun recenterMap() {
        _panOffsetLatLon.value = Pair(0.0, 0.0)
        fetchLocation()
    }

    fun selectAircraft(aircraft: Aircraft?) {
        _selectedAircraft.value = aircraft
    }

    override fun onCleared() {
        super.onCleared()
        httpClient.close()
    }
}
