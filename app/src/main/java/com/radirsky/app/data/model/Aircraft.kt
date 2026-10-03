package com.radirsky.app.data.model

data class Aircraft(
    val icao24: String,
    val callsign: String,
    val originCountry: String,
    val longitude: Double?,
    val latitude: Double?,
    val barometricAltitude: Double?, // in meters
    val velocity: Double?,           // in m/s
    val trueTrack: Double?,          // in degrees heading
    val verticalRate: Double?,       // in m/s
    val lastContact: Long? = null,   // epoch seconds
    val dataSource: String = "ADSB.LOL"
)
