package com.github.arapy.groundstation.identity

import com.parodison.orbit.core.satellite.ObserverCoordinates
import java.io.File
import java.util.Properties

object GroundStationLocationStore {
    private val file = File(System.getProperty("user.home"), ".config/groundstation/location.properties")

    fun load(): ObserverCoordinates? {
        if (!file.exists()) return null
        val props = Properties().apply { load(file.inputStream()) }
        return ObserverCoordinates(
            latitudeDeg = props.getProperty("latitudeDeg").toDouble(),
            longitudeDeg = props.getProperty("longitudeDeg").toDouble(),
            altitudeKm = props.getProperty("altitudeKm").toDouble(),
        )
    }

    fun save(coordinates: ObserverCoordinates) {
        file.parentFile.mkdirs()
        Properties().apply {
            setProperty("latitudeDeg", coordinates.latitudeDeg.toString())
            setProperty("longitudeDeg", coordinates.longitudeDeg.toString())
            setProperty("altitudeKm", coordinates.altitudeKm.toString())
        }.store(file.outputStream(), "Ground Station Location")
    }
}
