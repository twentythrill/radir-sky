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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class RadarViewModel(application: Application) : AndroidViewModel(application) {

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
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
                val isBackoffNeeded = performFetch()

                if (isBackoffNeeded) {
                    // HTTP 429 / Rate Limit: Pause loop for 30 seconds backoff
                    delay(30000L)
                } else {
                    // Continuous 5-second polling for 24/7 desk-toy operation
                    delay(5000L)
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun performFetch(): Boolean {
        val loc = _userLocation.value ?: return false
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

        return (result is Lce.Error && result.isRateLimit)
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
