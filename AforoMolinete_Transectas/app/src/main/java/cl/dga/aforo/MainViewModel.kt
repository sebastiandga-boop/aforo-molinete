
package cl.dga.aforo

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.dga.aforo.bt.BluetoothClient
import cl.dga.aforo.model.*
import cl.dga.aforo.util.MeasureTimer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Telemetry(val cps: Double = 0.0, val rps: Double = 0.0, val total: Long = 0L, val ms: Long = 0L)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val bt = BluetoothClient()
    private var readerJob: Job? = null

    private val _paired = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val paired = _paired.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry = _telemetry.asStateFlow()

    private val _calib = MutableStateFlow(Calibration())
    val calib = _calib.asStateFlow()

    private val _transecta = MutableStateFlow(
        Transecta(
            nombre = "Transecta ${System.currentTimeMillis()%10000}",
            fechaIso = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            verticales = MutableList(6) { i -> Vertical(index = i, dx = if (i==0) 1.0 else 1.0, h = 0.8) }
        )
    )
    val transecta = _transecta.asStateFlow()

    private var measuringIdx: Int? = null
    private var twoPointStage: Int = 0
    private var accVsum = 0.0
    private var accCount = 0
    private var timer: MeasureTimer? = null

    fun refreshPaired() { _paired.value = bt.getPairedDevices().toList() }

    fun connect(dev: BluetoothDevice) {
        viewModelScope.launch {
            bt.disconnect(); readerJob?.cancel()
            bt.connectTo(dev)
            _connected.value = true
            readerJob = viewModelScope.launch { bt.lines.collect { parseLine(it) } }
            bt.send("SET PPR ${_calib.value.pulsesPerRev}")
            bt.send("STATUS")
        }
    }
    fun disconnect() { bt.disconnect(); readerJob?.cancel(); _connected.value = false }

    fun setCalibration(a: Double? = null, b: Double? = null, ppr: Int? = null) {
        _calib.value = _calib.value.copy(
            a = a ?: _calib.value.a, b = b ?: _calib.value.b,
            pulsesPerRev = ppr ?: _calib.value.pulsesPerRev
        )
        viewModelScope.launch { ppr?.let { bt.send("SET PPR $it") } }
    }

    private fun parseLine(line: String) {
        var cps = 0.0; var rps = 0.0; var total = 0L; var ms = 0L
        line.split(',').forEach { p ->
            val kv = p.split(':')
            if (kv.size == 2) {
                when (kv[0]) {
                    "CPS" -> cps = kv[1].toDoubleOrNull() ?: 0.0
                    "RPS" -> rps = kv[1].toDoubleOrNull() ?: 0.0
                    "TOTAL" -> total = kv[1].toLongOrNull() ?: 0L
                    "MS" -> ms = kv[1].toLongOrNull() ?: 0L
                }
            }
        }
        val ppr = _calib.value.pulsesPerRev.coerceAtLeast(1)
        val rpsEff = if (rps > 0) rps else cps / ppr.toDouble()
        _telemetry.value = Telemetry(cps, rpsEff, total, ms)
        measuringIdx?.let {
            val v = FlowCalculator.velocityFromRps(rpsEff, _calib.value)
            accVsum += v; accCount += 1
        }
    }

    fun setNombreFechaUbic(nombre: String?, fechaIso: String?, ubic: String?) {
        _transecta.value = _transecta.value.copy(
            nombre = nombre ?: _transecta.value.nombre,
            fechaIso = fechaIso ?: _transecta.value.fechaIso,
            ubicacion = ubic ?: _transecta.value.ubicacion
        )
    }

    fun setTimerThreshold(timerSeg: Int?, threshold: Double?) {
        _transecta.value = _transecta.value.copy(
            timerSeg = timerSeg ?: _transecta.value.timerSeg,
            thresholdDosPuntos = threshold ?: _transecta.value.thresholdDosPuntos
        )
    }

    fun setEdges(left: Double?, right: Double?) {
        val t = _transecta.value
        _transecta.value = t.copy(edges = t.edges.copy(
            leftEdgeDist = left ?: t.edges.leftEdgeDist,
            rightEdgeDist = right ?: t.edges.rightEdgeDist
        ))
    }

    fun setNumVerticals(n: Int) {
        val t = _transecta.value
        val cur = t.verticales
        val list = MutableList(n.coerceAtLeast(1)) { i ->
            if (i < cur.size) cur[i].copy(index = i)
            else Vertical(index = i, dx = if (i==0) 1.0 else 1.0, h = 0.8)
        }
        _transecta.value = t.copy(verticales = list.toMutableList())
    }

    fun updateVertical(i: Int, dx: Double? = null, h: Double? = null, mode: VertMode? = null) {
        val t = _transecta.value
        val list = t.verticales.toMutableList()
        val v = list[i]
        list[i] = v.copy(
            dx = dx ?: v.dx,
            h = h ?: v.h,
            mode = mode ?: v.mode
        )
        _transecta.value = t.copy(verticales = list)
    }

    fun clearVelocity(i: Int) {
        val t = _transecta.value; val list = t.verticales.toMutableList()
        val v = list[i]
        list[i] = v.copy(v06 = null, v2p = null)
        _transecta.value = t.copy(verticales = list)
    }

    fun startMeasure(i: Int) {
        val t = _transecta.value
        measuringIdx = i
        accVsum = 0.0; accCount = 0
        twoPointStage = 0
        timer = MeasureTimer(t.timerSeg)
        viewModelScope.launch {
            timer?.start(onStart = {}, onFinish = { onTimerFinish() })
        }
    }

    fun stopMeasure() { timer?.stop(); measuringIdx = null }

    private fun onTimerFinish() {
        val idx = measuringIdx ?: return
        val t = _transecta.value
        val avg = if (accCount > 0) accVsum / accCount else 0.0

        val list = t.verticales.toMutableList()
        val v = list[idx]
        when (v.mode) {
            VertMode.ONE_POINT -> {
                list[idx] = v.copy(v06 = avg)
                measuringIdx = null
            }
            VertMode.TWO_POINTS -> {
                if (twoPointStage == 0) {
                    val tp = (v.v2p ?: TwoPointVelocity()).apply { v02 = avg }
                    list[idx] = v.copy(v2p = tp)
                    _transecta.value = t.copy(verticales = list)
                    accVsum = 0.0; accCount = 0; twoPointStage = 1
                    timer = MeasureTimer(t.timerSeg)
                    androidx.lifecycle.viewModelScope.launch { timer?.start(onFinish = { onTimerFinish() }) }
                    return
                } else {
                    val tp = (v.v2p ?: TwoPointVelocity()).apply { v08 = avg }
                    list[idx] = v.copy(v2p = tp)
                    measuringIdx = null
                }
            }
        }
        _transecta.value = t.copy(verticales = list)
    }

    fun timerState() = timer?.remaining

    fun totalDischarge(): Pair<Double, List<Double>> {
        return FlowCalculator.computeDischarge(_transecta.value.verticales, _transecta.value.edges)
    }
}
