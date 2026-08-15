package com.github.arapy.groundstation

import com.github.arapy.groundstation.connection.RelayConnection
import com.github.arapy.groundstation.connection.client
import com.github.arapy.groundstation.controller.GroundStationController
import com.github.arapy.groundstation.hardware.Rotator
import com.github.arapy.groundstation.hardware.StepperMotor
import com.github.arapy.groundstation.identity.StationIdentityStore
import com.pi4j.Pi4J
import com.pi4j.context.Context
import com.pi4j.io.pwm.PwmType
import com.pi4j.ktx.io.digital.digitalOutput
import com.pi4j.ktx.io.digital.piGpioProvider
import com.pi4j.ktx.io.piGpioProvider
import com.pi4j.ktx.io.pwm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.time.Duration.Companion.milliseconds

const val AZ_PUL_PIN = 13
const val AZ_DIR_PIN = 27

const val EL_PUL_PIN = 12
const val EL_DIR_PIN = 23

const val RELAY_URL = "ws://10.154.42.227:8081/ws/stations"
//const val RELAY_URL = "ws://10.154.42.48:8081/ws/stations"

fun main(args: Array<String>): Unit = runBlocking {
    println("Estación terrena iniciada")
    waitForNetwork()

    val identity = StationIdentityStore.getOrCreate()
    println("Estación: ${identity.name} (${identity.id})")

    val pi = Pi4J.newAutoContext()
    val hardwareDispatcher = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val rotator = Rotator(
        azMotor = StepperMotor(
            pulPin = pi.hardwarePwm(AZ_PUL_PIN),
            dirPin = pi.directionPin(AZ_DIR_PIN, "azimuth_dir"),
        ),
        elMotor = StepperMotor(
            pulPin = pi.hardwarePwm(EL_PUL_PIN),
            dirPin = pi.directionPin(EL_DIR_PIN, "elevation_dir"),
        )
    )

    val controller = GroundStationController(
        rotator,
        hardwareDispatcher,
        scope
    )
    val relayConnection = RelayConnection(
        RELAY_URL,
        identity,
        controller
    )

    scope.launch {
        relayConnection.runForever()
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        scope.cancel()
        client.close()
        pi.shutdown()
    })

    awaitCancellation()
}


private fun Context.hardwarePwm(address: Int) = pwm(address) {
    piGpioProvider()
    pwmType(PwmType.HARDWARE)
}

private fun Context.directionPin(address: Int, id: String) = digitalOutput(address) {
    piGpioProvider()
    id(id)
}


private suspend fun waitForNetwork(checkIntervalMs: Long = 3000) {
    while (!hasActiveNetworkInterface()) {
        delay(checkIntervalMs.milliseconds)
    }
}

private fun hasActiveNetworkInterface(): Boolean =
    NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .any { iface -> iface.inetAddresses.asSequence().any { it is Inet4Address } }