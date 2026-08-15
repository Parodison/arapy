package com.github.arapy.groundstation.controller

import com.github.arapy.groundstation.hardware.Rotator
import com.github.arapy.groundstation.identity.GroundStationLocationStore
import com.parodison.orbit.core.groundstation.dto.GroundStationStatus
import com.parodison.orbit.core.satellite.ObserverCoordinates
import com.parodison.orbit.core.satellite.Satellite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class GroundStationController(
    val rotator: Rotator,
    private val hardwareDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) {

    private val _groundStationLocation = MutableStateFlow(GroundStationLocationStore.load())
    val groundStationLocation: StateFlow<ObserverCoordinates?> = _groundStationLocation.asStateFlow()

    private val _status = MutableStateFlow<GroundStationStatus>(GroundStationStatus.Idle)
    val status = _status.asStateFlow()

    private var trackingJob: Job? = null

    fun updateLocation(coordinates: ObserverCoordinates) {
        _groundStationLocation.value = coordinates
        GroundStationLocationStore.save(coordinates)
    }

    /** Called by [com.github.arapy.groundstation.connection.RelayConnection] right after the handshake succeeds. */
    fun markConnected() {
        if (_status.value !is GroundStationStatus.Connected) {
            _status.value = GroundStationStatus.Connected.Ready
        }
    }

    fun startTracking(satellite: Satellite) {
        val observer = groundStationLocation.value
        if (observer == null) {
            _status.value = GroundStationStatus.Connected.Error(
                message = "Ubicación de la estación no configurada",
                recoverable = true
            )
            return
        }

        trackingJob?.cancel()
        trackingJob = scope.launch(hardwareDispatcher) {
            val now = Clock.System.now()

            val passes = satellite.nextPassesFrom(
                observer,
                from = now,
                searchWindow = 24.hours,
                minElevationDeg = 10.0
            )
            val nextPass = passes.firstOrNull() ?: run {
                _status.value = GroundStationStatus.Connected.Ready
                return@launch
            }
            val aosLookAngles = satellite.lookAnglesFrom(observer, nextPass.aos)

            _status.value = GroundStationStatus.Connected.PreparingForPass(
                omm = satellite.omm,
                aosAzimuth = aosLookAngles.azimuthDeg,
                aosTime = nextPass.aos
            )
            rotator.moveTo(aosLookAngles.azimuthDeg, aosLookAngles.elevationDeg)
            val waitMs = (nextPass.aos - Clock.System.now())
            if (waitMs > 0.milliseconds) delay(waitMs)

            trackLoop(satellite, observer, nextPass.los)
        }
    }

    private suspend fun trackLoop(satellite: Satellite, observer: ObserverCoordinates, until: Instant) {
        while (currentCoroutineContext().isActive && Clock.System.now() < until) {
            val look = satellite.lookAnglesFrom(observer, Clock.System.now())
            if (look.elevationDeg <= 0) break

            rotator.moveTo(look.azimuthDeg, look.elevationDeg)
            _status.value = GroundStationStatus.Connected.Tracking(
                omm = satellite.omm,
                azimuth = look.azimuthDeg,
                elevation = look.elevationDeg,
            )

            delay(600.milliseconds)
        }
        _status.value = GroundStationStatus.Connected.PassComplete
        rotator.resetPosition()
    }

    fun stopTracking() {
        val job = trackingJob
        trackingJob = null
        _status.value = GroundStationStatus.Connected.Ready
        scope.launch(hardwareDispatcher) {
            job?.cancelAndJoin()
            rotator.resetPosition()
        }
    }

}