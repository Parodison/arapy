package com.github.arapy.groundstation.hardware

import com.pi4j.io.gpio.digital.DigitalOutput
import com.pi4j.io.pwm.Pwm
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.*
import kotlin.time.Duration.Companion.milliseconds

class StepperMotor(
    private val pulPin: Pwm,
    private val dirPin: DigitalOutput,
    private val stepsPerRev: Int = 200,
    private val microstepping: Int = 8,
    private val gearRatio: Int = 60,
    private val minFrequency: Int = 80,
    private val maxFrequency: Int = 800,
) {

    private val pulsesPerDegree: Double =
        (stepsPerRev * microstepping * gearRatio) / 360.0
    private var currentPulses: Long = 0

    private val _currentDegrees = MutableStateFlow(0.0)
    val currentDegrees = _currentDegrees.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    fun setOrigin(actualDegrees: Double = 0.0) {
        currentPulses = (actualDegrees * pulsesPerDegree).toLong()
        _currentDegrees.value = actualDegrees
    }

    suspend fun moveTo(targetDegrees: Double) {
        val targetPulses = (targetDegrees * pulsesPerDegree).toLong()
        val delta = targetPulses - currentPulses
        if (delta == 0L) return

        _isMoving.value = true
        val direction = if (delta > 0) 1 else -1
        if (delta > 0) dirPin.high() else dirPin.low()

        println("StepperMotor: moviendo ${abs(delta)} pulsos (${targetDegrees}°, dir=${if (delta > 0) "+" else "-"})")
        executeTrapezoidalProfile(abs(delta), direction)

        // Safety net: forces the exact target after rounding across all the chunked updates below.
        currentPulses = targetPulses
        _currentDegrees.value = currentPulses / pulsesPerDegree
        _isMoving.value = false
    }

    private suspend fun executeTrapezoidalProfile(totalPulses: Long, direction: Int) {
        // finally guarantees the PWM signal is always cut — on the short-move path (which used
        // to fall off the end without ever calling off()), and on cancellation (e.g. stopTracking
        // interrupting an in-flight move), not just on a normal full-ramp completion. Without this,
        // the motor keeps stepping forever in whatever direction/frequency was last configured.
        try {
            if (totalPulses < (minFrequency * 0.5)) {
                sendPulses(totalPulses, minFrequency, direction)
                return
            }

            val steps = 10
            val freqIncrement = (maxFrequency - minFrequency) / steps.toDouble()
            val rampPulses = min((totalPulses * 0.25).toLong(), 600L)
            val pulsesPerStep = rampPulses / steps

            var pulsesSent = 0L
            var freq = minFrequency.toDouble()

            repeat(steps) {
                sendPulses(pulsesPerStep, freq.toInt(), direction)
                freq += freqIncrement
                pulsesSent += pulsesPerStep
            }

            val cruisePulses = totalPulses - (pulsesSent * 2)
            if (cruisePulses > 0) {
                println("StepperMotor: crucero de $cruisePulses pulsos a ${maxFrequency}Hz (~${cruisePulses / maxFrequency}s)")
                sendPulses(cruisePulses, maxFrequency, direction)
                pulsesSent += cruisePulses
            }

            freq = maxFrequency.toDouble()
            val remaining = totalPulses - pulsesSent
            val downStep = remaining / steps
            repeat(steps) { i ->
                val toSend = if (i == steps - 1) totalPulses - pulsesSent else downStep
                sendPulses(toSend, freq.toInt(), direction)
                freq -= freqIncrement
                pulsesSent += toSend
            }
        } finally {
            pulPin.off()
        }
    }

    /**
     * Sends [count] pulses at [frequency] Hz. The PWM signal itself runs continuously in
     * hardware for the whole batch (one `on()` call), but the wait is split into small chunks
     * so [currentDegrees] advances progressively instead of jumping only once per batch —
     * batches can be tens of seconds long during a big slew's cruise phase.
     */
    private suspend fun sendPulses(count: Long, frequency: Int, direction: Int) {
        if (count <= 0 || frequency <= 0) return
        pulPin.frequency(frequency)
        pulPin.dutyCycle(50.0)
        pulPin.on()

        val chunkDurationMs = 150L
        var remaining = count
        while (remaining > 0) {
            val chunkPulses = ((chunkDurationMs * frequency) / 1000L).coerceIn(1L, remaining)
            delay(((chunkPulses * 1000L) / frequency).milliseconds)
            currentPulses += direction * chunkPulses
            _currentDegrees.value = currentPulses / pulsesPerDegree
            remaining -= chunkPulses
        }
    }
}