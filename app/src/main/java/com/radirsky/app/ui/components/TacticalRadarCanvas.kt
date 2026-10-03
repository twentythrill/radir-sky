package com.radirsky.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radirsky.app.data.location.LocationCoordinates
import com.radirsky.app.data.model.Aircraft
import com.radirsky.app.data.model.Lce
import com.radirsky.app.ui.theme.DarkBackground
import com.radirsky.app.ui.theme.DarkSurface
import com.radirsky.app.ui.theme.IaWriterQuattro
import com.radirsky.app.ui.theme.RadarGreen
import com.radirsky.app.ui.theme.TacticalGrid
import com.radirsky.app.ui.theme.TacticalOrange
import com.radirsky.app.ui.theme.TextPrimary
import com.radirsky.app.ui.theme.TextSecondary
import kotlin.math.cos

@Composable
fun TacticalRadarCanvas(
    userLocation: LocationCoordinates?,
    uiState: Lce<List<Aircraft>>,
    zoomRadiusKm: Float,
    panOffsetLatLon: Pair<Double, Double>,
    selectedAircraft: Aircraft?,
    onPan: (Double, Double) -> Unit,
    onRecenter: () -> Unit,
    onSelectAircraft: (Aircraft?) -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SweepAnimation")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SweepAngle"
    )

    val textMeasurer = rememberTextMeasurer()

    // Dead Reckoning & Interpolation Engine
    val interpolator = remember { AircraftInterpolator() }
    var interpolatedList by remember { mutableStateOf<List<InterpolatedAircraft>>(emptyList()) }

    var lastKnownSource by remember { mutableStateOf("ADSB.LOL") }

    // Update targets when new API content arrives
    LaunchedEffect(uiState) {
        if (uiState is Lce.Content) {
            interpolator.updateTargets(uiState.data)
            uiState.data.firstOrNull()?.dataSource?.let { lastKnownSource = it }
        }
    }

    // Continuous 60 FPS Dead Reckoning animation loop
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameTimeNanos ->
                interpolatedList = interpolator.tick(frameTimeNanos)
            }
        }
    }

    val safeZoomKm = zoomRadiusKm.coerceIn(2.0f, 250.0f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // RADAR CANVAS VIEWPORT (480x640 target)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val widthPx = size.width.toFloat()
                        val heightPx = size.height.toFloat()
                        val kmPerPx = (safeZoomKm * 2f) / minOf(widthPx, heightPx)
                        val deltaKmY = dragAmount.y * kmPerPx
                        val deltaKmX = -dragAmount.x * kmPerPx

                        val centerLat = (userLocation?.latitude ?: 0.0) + panOffsetLatLon.first
                        val deltaLat = deltaKmY / 111.0
                        val deltaLon = deltaKmX / (111.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.1))

                        if (deltaLat.isFinite() && deltaLon.isFinite()) {
                            onPan(deltaLat, deltaLon)
                        }
                    }
                }
                .clickable {
                    onSelectAircraft(null)
                }
        ) {
            val maxRadius = (minOf(size.width, size.height) / 2f) - 5.dp.toPx()
            val centerY = (size.height / 2f) + 24.dp.toPx()

            if (!maxRadius.isFinite() || maxRadius <= 0f || !centerY.isFinite()) return@Canvas

            val center = Offset(size.width / 2f, centerY)

            val rawLat = (userLocation?.latitude ?: 47.3769) + panOffsetLatLon.first
            val rawLon = (userLocation?.longitude ?: 8.5417) + panOffsetLatLon.second

            val centerLat = if (rawLat.isFinite()) rawLat else 47.3769
            val centerLon = if (rawLon.isFinite()) rawLon else 8.5417

            // Draw Tactical Grid
            drawTacticalGrid(center, size)

            // Draw Concentric Range Rings (470px diameter)
            drawRangeRings(center, maxRadius, safeZoomKm, textMeasurer)

            // Draw Animated Radar Sweep Line
            drawRadarSweep(center, maxRadius, sweepAngle)

            // Draw User Location Reticle
            if (userLocation != null) {
                drawUserLocationReticle(center, userLocation.isGps, panOffsetLatLon)
            }

            // Draw Interpolated Aircraft Blips (Dead Reckoning)
            val pxPerKm = maxRadius / safeZoomKm
            if (!pxPerKm.isFinite() || pxPerKm <= 0f) return@Canvas

            interpolatedList.forEach { item ->
                val aircraft = item.aircraft
                val latDiff = item.renderLat - centerLat
                val lonDiff = item.renderLon - centerLon

                val kmY = latDiff * 111.0
                val kmX = lonDiff * 111.0 * cos(Math.toRadians(centerLat))

                val aircraftX = center.x + (kmX * pxPerKm).toFloat()
                val aircraftY = center.y - (kmY * pxPerKm).toFloat()

                // Viewport culling: only draw aircraft blips that fall within screen bounds
                if (aircraftX in -20f..(size.width + 20f) && aircraftY in -20f..(size.height + 20f)) {
                    val isSelected = aircraft.icao24 == selectedAircraft?.icao24

                    drawAircraftBlip(
                        centerPx = Offset(aircraftX, aircraftY),
                        aircraft = aircraft,
                        isSelected = isSelected,
                        textMeasurer = textMeasurer
                    )
                }
            }
        }

        // TOP TACTICAL HUD BAR
        Surface(
            color = DarkSurface.copy(alpha = 0.92f),
            shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .border(1.dp, TacticalGrid, RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (userLocation?.isGps == true) Icons.Default.GpsFixed else Icons.Default.MyLocation,
                        contentDescription = "Location Mode",
                        tint = if (userLocation?.isGps == true) RadarGreen else TacticalOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = if (userLocation != null)
                                String.format("%.3f°N, %.3f°E", userLocation.latitude, userLocation.longitude)
                            else "ACQUIRING POSITION...",
                            style = TextStyle(
                                fontFamily = IaWriterQuattro,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = if (userLocation?.isGps == true) "SRC::$lastKnownSource [GPS]" else "SRC::$lastKnownSource [IP]",
                            style = TextStyle(
                                fontFamily = IaWriterQuattro,
                                fontSize = 9.sp,
                                color = if (userLocation?.isGps == true) RadarGreen else TacticalOrange
                            )
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "RNG: ${safeZoomKm.toInt()}KM",
                        style = TextStyle(
                            fontFamily = IaWriterQuattro,
                            fontSize = 11.sp,
                            color = TacticalOrange
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "TGT: ${interpolatedList.size}",
                        style = TextStyle(
                            fontFamily = IaWriterQuattro,
                            fontSize = 11.sp,
                            color = RadarGreen
                        )
                    )
                }
            }
        }

        // MINIMAL ICON-ONLY RECENTER / GPS BUTTON AT VERY BOTTOM RIGHT
        IconButton(
            onClick = onRecenter,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 14.dp)
                .size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = "Recenter Map to GPS",
                tint = if (panOffsetLatLon.first != 0.0 || panOffsetLatLon.second != 0.0) TacticalOrange else TextPrimary.copy(alpha = 0.85f),
                modifier = Modifier.size(20.dp)
            )
        }

        // SELECTED AIRCRAFT HUD DETAIL CARD (Bottom Left)
        if (selectedAircraft != null) {
            Surface(
                color = DarkSurface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .border(1.dp, TacticalOrange, RoundedCornerShape(6.dp))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "LOCK :: ${selectedAircraft.callsign} [${selectedAircraft.icao24.uppercase()}]",
                        style = TextStyle(
                            fontFamily = IaWriterQuattro,
                            fontSize = 12.sp,
                            color = TacticalOrange
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        Text(
                            text = "ALT: ${selectedAircraft.barometricAltitude?.toInt() ?: "---"}m | SPD: ${selectedAircraft.velocity?.toInt() ?: "---"}m/s",
                            style = TextStyle(fontFamily = IaWriterQuattro, fontSize = 10.sp, color = TextPrimary)
                        )
                    }
                    Row {
                        Text(
                            text = "HDG: ${selectedAircraft.trueTrack?.toInt() ?: "---"}° | SRC: ${selectedAircraft.dataSource}",
                            style = TextStyle(fontFamily = IaWriterQuattro, fontSize = 10.sp, color = TextSecondary)
                        )
                    }
                }
            }
        }

        // ERROR / BACKOFF STATUS BANNER
        if (uiState is Lce.Error) {
            Surface(
                color = Color(0xCC900C0C),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
            ) {
                Text(
                    text = uiState.message,
                    style = TextStyle(
                        fontFamily = IaWriterQuattro,
                        fontSize = 11.sp,
                        color = Color.White
                    ),
                    modifier = Modifier.padding(6.dp),
                    maxLines = 1
                )
            }
        }
    }
}

private fun DrawScope.drawTacticalGrid(center: Offset, size: Size) {
    val gridColor = TacticalGrid.copy(alpha = 0.35f)
    val gridStep = 50.dp.toPx()

    var x = center.x % gridStep
    while (x < size.width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += gridStep
    }

    var y = center.y % gridStep
    while (y < size.height) {
        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += gridStep
    }
}

private fun DrawScope.drawRangeRings(
    center: Offset,
    maxRadius: Float,
    zoomRadiusKm: Float,
    textMeasurer: TextMeasurer
) {
    val ringColor = TacticalOrange.copy(alpha = 0.5f)
    val ringCount = 4

    for (i in 1..ringCount) {
        val r = (maxRadius / ringCount) * i
        if (!r.isFinite() || r <= 0f) continue

        drawCircle(
            color = ringColor,
            radius = r,
            center = center,
            style = Stroke(width = 1.5f)
        )

        val ringKm = (zoomRadiusKm / ringCount * i).toInt()
        val textY = center.y - r + 3f
        val textX = center.x + 6f

        if (textX in 0f..(size.width - 40f) && textY in 10f..(size.height - 20f)) {
            drawText(
                textMeasurer = textMeasurer,
                text = "${ringKm}KM",
                style = TextStyle(
                    fontFamily = IaWriterQuattro,
                    fontSize = 9.sp,
                    color = TacticalOrange.copy(alpha = 0.85f)
                ),
                topLeft = Offset(textX, textY)
            )
        }
    }
}

private fun DrawScope.drawRadarSweep(center: Offset, radius: Float, angle: Float) {
    if (!radius.isFinite() || radius <= 0f) return

    val trailArcAngle = 100f // 100-degree wide trailing arc sector
    val steps = 45

    rotate(angle, center) {
        // Draw intense fading trail arc sectors behind the leading sweep line
        for (i in 0 until steps) {
            val stepAngle = (trailArcAngle / steps)
            val startAngle = -(i + 1) * stepAngle
            val factor = 1f - (i.toFloat() / steps)
            val alpha = 0.65f * (factor * factor) // Powerful quadratic decay curve

            drawArc(
                color = TacticalOrange.copy(alpha = alpha),
                startAngle = startAngle,
                sweepAngle = stepAngle + 0.6f, // prevent gap artifacts between slices
                useCenter = true,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f)
            )
        }

        // Leading intense tactical radar beam line
        drawLine(
            color = TacticalOrange,
            start = center,
            end = Offset(center.x + radius, center.y),
            strokeWidth = 3.5f
        )
    }
}

private fun DrawScope.drawUserLocationReticle(
    center: Offset,
    isGps: Boolean,
    panOffset: Pair<Double, Double>
) {
    val reticleColor = if (isGps) RadarGreen else TacticalOrange
    val isPanned = panOffset.first != 0.0 || panOffset.second != 0.0

    if (!isPanned) {
        drawCircle(
            color = reticleColor,
            radius = 6.dp.toPx(),
            center = center,
            style = Stroke(width = 2f)
        )
        drawCircle(
            color = reticleColor,
            radius = 2.dp.toPx(),
            center = center
        )
    }
}

private fun DrawScope.drawAircraftBlip(
    centerPx: Offset,
    aircraft: Aircraft,
    isSelected: Boolean,
    textMeasurer: TextMeasurer
) {
    val blipColor = if (isSelected) TacticalOrange else RadarGreen
    val heading = aircraft.trueTrack?.toFloat() ?: 0f

    withTransform({
        rotate(heading, centerPx)
    }) {
        val path = Path().apply {
            moveTo(centerPx.x, centerPx.y - 8.dp.toPx())
            lineTo(centerPx.x + 6.dp.toPx(), centerPx.y + 6.dp.toPx())
            lineTo(centerPx.x, centerPx.y + 2.dp.toPx())
            lineTo(centerPx.x - 6.dp.toPx(), centerPx.y + 6.dp.toPx())
            close()
        }
        drawPath(path, blipColor)
    }

    if (isSelected) {
        val boxSize = 22.dp.toPx()
        drawRect(
            color = TacticalOrange,
            topLeft = Offset(centerPx.x - boxSize / 2, centerPx.y - boxSize / 2),
            size = Size(boxSize, boxSize),
            style = Stroke(width = 1.5f)
        )
    }

    val textX = centerPx.x + 10.dp.toPx()
    val textY = centerPx.y - 10.dp.toPx()
    if (textX in 0f..(size.width - 50f) && textY in 10f..(size.height - 20f)) {
        drawText(
            textMeasurer = textMeasurer,
            text = aircraft.callsign,
            style = TextStyle(
                fontFamily = IaWriterQuattro,
                fontSize = 9.sp,
                color = if (isSelected) TacticalOrange else TextPrimary
            ),
            topLeft = Offset(textX, textY)
        )
    }
}
