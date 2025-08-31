
package cl.dga.aforo.util

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

class MeasureTimer(private val totalSec: Int) {
    val remaining = MutableStateFlow(totalSec)
    val running = MutableStateFlow(false)
    private val beeper = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    suspend fun start(onStart: () -> Unit = {}, onFinish: () -> Unit = {}) {
        running.value = true
        remaining.value = totalSec
        beepStart(); onStart()
        while (running.value && remaining.value > 0) {
            delay(1000)
            remaining.value -= 1
        }
        if (running.value) { beepEnd(); onFinish() }
        running.value = false
    }
    fun stop() { running.value = false }
    private fun beepStart() { beeper.startTone(ToneGenerator.TONE_PROP_BEEP, 200) }
    private fun beepEnd() { beeper.startTone(ToneGenerator.TONE_PROP_ACK, 300) }
}
