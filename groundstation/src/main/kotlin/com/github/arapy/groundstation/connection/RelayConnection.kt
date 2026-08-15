package com.github.arapy.groundstation.connection

import com.github.arapy.groundstation.controller.GroundStationController
import com.github.arapy.groundstation.identity.StationIdentity
import com.parodison.orbit.core.groundstation.dto.GroundStation
import com.parodison.orbit.core.protocol.WebsocketPayload
import com.parodison.orbit.core.satellite.model.toSatellite
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps a websocket connection to the server's `/ws/stations` endpoint alive — handshakes on
 * connect, forwards this station's [GroundStationController.status] up to the server as it
 * changes, and dispatches incoming commands ([WebsocketPayload.TrackSatellite],
 * [WebsocketPayload.StopTracking], [WebsocketPayload.UpdateStationLocation]) to [controller].
 *
 * The server itself is what decides "is this station active" (it only knows what it currently
 * has a live connection for) — this class's only job is to actually maintain that connection and
 * reconnect when it drops, so the server's view has something real to reflect.
 */
class RelayConnection(
    private val url: String,
    private val identity: StationIdentity,
    private val controller: GroundStationController,
) {
    private var session: DefaultClientWebSocketSession? = null
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun runForever() {
        while (true) {
            try {
                client.webSocket(url) {
                    println("Conectado al relay: $url")

                    sendPayload(WebsocketPayload.StationHandshake(identity.id, identity.name))
                    controller.markConnected()
                    session = this

                    coroutineScope {
                        controller.status
                            .onEach { status -> sendPayload(WebsocketPayload.StatusUpdate(identity.id, status)) }
                            .launchIn(this)

                        controller.rotator.position
                            .onEach { position -> sendPayload(WebsocketPayload.RotatorPositionChange(stationId = identity.id, position) )}
                            .launchIn(this)

                        controller.groundStationLocation
                            .filterNotNull()
                            .onEach { coordinates -> sendPayload(WebsocketPayload.UpdateStationLocation(identity.id, coordinates)) }
                            .launchIn(this)

                        for (frame in incoming) {
                            if (frame is Frame.Binary) {
                                val payload = Cbor.decodeFromByteArray(WebsocketPayload.serializer(), frame.readBytes())
                                handleIncoming(payload)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("Conexión con el relay perdida (${e.message}), reintentando...")
            }
            delay(3.seconds)
        }
    }

    private suspend fun handleIncoming(payload: WebsocketPayload) {
        when (payload) {
            is WebsocketPayload.TrackSatellite -> {
                if (payload.stationId == identity.id) controller.startTracking(payload.omm.toSatellite())
            }

            is WebsocketPayload.StopTracking -> {
                if (payload.stationId == identity.id) controller.stopTracking()
            }

            is WebsocketPayload.UpdateStationLocation -> {
                if (payload.stationId == identity.id) controller.updateLocation(payload.coordinates)
            }

            is WebsocketPayload.RequestCompleteStationStatus -> {
                if (payload.stationId == identity.id) {
                    val response = GroundStation(
                        id = identity.id,
                        name = identity.name,
                        status = controller.status.value,
                        coordinates = controller.groundStationLocation.value,
                        rotatorPosition = controller.rotator.position.value,
                    )

                    session?.sendPayload(WebsocketPayload.ResponseCompleteStationStatus(payload.requesterId, response))
                }
            }
            else -> {}
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
private suspend fun DefaultClientWebSocketSession.sendPayload(payload: WebsocketPayload) {
    val bytes = Cbor.encodeToByteArray(WebsocketPayload.serializer(), payload)
    send(Frame.Binary(true, bytes))
}
