package com.radirsky.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.radirsky.app.ui.RadarViewModel
import com.radirsky.app.ui.components.TacticalRadarCanvas
import com.radirsky.app.ui.theme.DarkBackground
import com.radirsky.app.ui.theme.RadirSkyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()
    private var lastVolumeKeyTime = 0L

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            viewModel.fetchLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force Android Screen to remain ON (prevents autolock / screen timeout for 24/7 desk toy)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Enable Immersive Fullscreen Mode (hide system status bar & navigation bar)
        enableImmersiveMode()

        checkLocationPermissions()

        setContent {
            RadirSkyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    val userLocation by viewModel.userLocation.collectAsState()
                    val uiState by viewModel.uiState.collectAsState()
                    val zoomRadiusKm by viewModel.zoomRadiusKm.collectAsState()
                    val panOffsetLatLon by viewModel.panOffsetLatLon.collectAsState()
                    val selectedAircraft by viewModel.selectedAircraft.collectAsState()

                    TacticalRadarCanvas(
                        userLocation = userLocation,
                        uiState = uiState,
                        zoomRadiusKm = zoomRadiusKm,
                        panOffsetLatLon = panOffsetLatLon,
                        selectedAircraft = selectedAircraft,
                        onPan = { deltaLat, deltaLon ->
                            viewModel.onPan(deltaLat, deltaLon)
                        },
                        onRecenter = { viewModel.recenterMap() },
                        onSelectAircraft = { aircraft -> viewModel.selectAircraft(aircraft) }
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun checkLocationPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        viewModel.onResume()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }

    // Intercept Hardware Volume Keys for Map Zoom In / Zoom Out with 180ms throttling
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val currentTime = System.currentTimeMillis()
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (currentTime - lastVolumeKeyTime < 180L) {
                return true
            }
            lastVolumeKeyTime = currentTime

            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                viewModel.zoomIn()
            } else {
                viewModel.zoomOut()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
