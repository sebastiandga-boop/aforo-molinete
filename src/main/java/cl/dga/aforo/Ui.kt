
package cl.dga.aforo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cl.dga.aforo.model.*
import kotlin.math.max

@Composable
fun TransectaScreen(vm: MainViewModel, onExport: () -> Unit) {
    val tr by vm.transecta.collectAsState()
    val tel by vm.telemetry.collectAsState()
    val calib by vm.calib.collectAsState()
    val connected by vm.connected.collectAsState()
    val timerRemaining = vm.timerState()?.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Aforo por Transecta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceDropdown(vm)
            Spacer(Modifier.width(8.dp))
            Text(if (connected) "Conectado" else "Desconectado")
        }
        Text("CPS=%.2f | RPS=%.2f".format(tel.cps, tel.rps))
        Spacer(Modifier.height(8.dp))
        TransectaHeader(tr, onChange = { nombre, fecha, ubic -> vm.setNombreFechaUbic(nombre, fecha, ubic) })
        Spacer(Modifier.height(6.dp))
        SamplingParams(tr, onChange = { timer, thr -> vm.setTimerThreshold(timer, thr) })
        Spacer(Modifier.height(6.dp))
        CalibrationPanel(calib, onAorB = { a, b -> vm.setCalibration(a, b, null) }, onPpr = { ppr -> vm.setCalibration(ppr = ppr) })
        Spacer(Modifier.height(6.dp))
        EdgesPanel(tr.edges) { l, r -> vm.setEdges(l, r) }
        Spacer(Modifier.height(8.dp))
        VerticalesPanel(tr, vm, timerRemaining?.value ?: tr.timerSeg)
        Spacer(Modifier.height(6.dp))
        val (Q, _) = vm.totalDischarge()
        Text("Q total: %.3f m³/s".format(Q), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SectionSketchAbs(tr)
        Spacer(Modifier.height(10.dp))
        Row { Button(onClick = onExport) { Text("Exportar CSV + PDF") } }
    }
}

@Composable
fun DeviceDropdown(vm: MainViewModel) {
    val devices by vm.paired.collectAsState()
    val connected by vm.connected.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Row {
        Button(onClick = { vm.refreshPaired(); expanded = true }) { Text("Conectar…") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (devices.isEmpty()) DropdownMenuItem(text = { Text("Sin emparejados") }, onClick = {})
            devices.forEach { dev ->
                DropdownMenuItem(text = { Text("${dev.name} (${dev.address})") }, onClick = { expanded = false; vm.connect(dev) })
            }
        }
        if (connected) { Spacer(Modifier.width(6.dp)); Button(onClick = { vm.disconnect() }) { Text("Desconectar") } }
    }
}

@Composable
fun TransectaHeader(tr: Transecta, onChange: (String?, String?, String?) -> Unit) {
    var nombre by remember { mutableStateOf(tr.nombre) }
    var fecha by remember { mutableStateOf(tr.fechaIso) }
    var ubic by remember { mutableStateOf(tr.ubicacion) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(nombre, { nombre = it }, label = { Text("Nombre") }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(fecha, { fecha = it }, label = { Text("Fecha (ISO)") }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(ubic, { ubic = it }, label = { Text("Ubicación") }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Button(onClick = { onChange(nombre, fecha, ubic) }) { Text("OK") }
    }
}

@Composable
fun SamplingParams(tr: Transecta, onChange: (Int?, Double?) -> Unit) {
    var timerS by remember { mutableStateOf(tr.timerSeg.toString()) }
    var thr by remember { mutableStateOf(tr.thresholdDosPuntos.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(timerS, { timerS = it }, label = { Text("Temporizador (s)") }, modifier = Modifier.width(180.dp))
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(thr, { thr = it }, label = { Text("Umbral 2 puntos (m)") }, modifier = Modifier.width(200.dp))
        Spacer(Modifier.width(6.dp))
        Button(onClick = { onChange(timerS.toIntOrNull(), thr.toDoubleOrNull()) }) { Text("OK") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationPanel(calib: Calibration, onAorB: (Double?, Double?) -> Unit, onPpr: (Int) -> Unit) {
    var a by remember { mutableStateOf(calib.a.toString()) }
    var b by remember { mutableStateOf(calib.b.toString()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(a, { a = it }, label = { Text("a (m/s)") }, modifier = Modifier.width(130.dp))
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(b, { b = it }, label = { Text("b (m/s·s)") }, modifier = Modifier.width(130.dp))
        Spacer(Modifier.width(12.dp))
        PprSelector(current = calib.pulsesPerRev, onSelected = onPpr)
        Spacer(Modifier.width(12.dp))
        Button(onClick = { onAorB(a.toDoubleOrNull(), b.toDoubleOrNull()) }) { Text("Aplicar a,b") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PprSelector(current: Int, onSelected: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf(1, 5)
    var selected by remember(current) { mutableStateOf(current.coerceIn(options)) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selected.toString(),
            onValueChange = {},
            readOnly = true,
            label = { Text("PPR") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().width(110.dp)
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt.toString()) }, onClick = {
                    selected = opt
                    expanded = false
                    onSelected(opt)
                })
            }
        }
    }
}

@Composable
fun EdgesPanel(edges: SectionInput, onApply: (Double?, Double?) -> Unit) {
    var l by remember { mutableStateOf(edges.leftEdgeDist.toString()) }
    var r by remember { mutableStateOf(edges.rightEdgeDist.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(l, { l = it }, label = { Text("Borde izq → V1 (m)") }, modifier = Modifier.width(220.dp))
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(r, { r = it }, label = { Text("VN → borde der (m)") }, modifier = Modifier.width(220.dp))
        Spacer(Modifier.width(6.dp))
        Button(onClick = { onApply(l.toDoubleOrNull(), r.toDoubleOrNull()) }) { Text("OK") }
    }
}

@Composable
fun VerticalesPanel(tr: Transecta, vm: MainViewModel, timerRemaining: Int) {
    var n by remember { mutableStateOf(tr.verticales.size.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(n, { n = it }, label = { Text("N° verticales") }, modifier = Modifier.width(160.dp))
        Spacer(Modifier.width(6.dp))
        Button(onClick = { vm.setNumVerticals(n.toIntOrNull() ?: tr.verticales.size) }) { Text("Aplicar") }
    }

    val dxList = tr.verticales.map { it.dx }
    val hsList = tr.verticales.map { it.h }
    val vdx = Validators.dxReasonable(dxList)
    val vhs = Validators.depthsReasonable(hsList)
    if (!vdx.ok || !vhs.ok) {
        Text("⚠ ${listOfNotNull(vdx.message, vhs.message).joinToString(" · ")}", color = Color(0xFFB00020))
    }

    LazyColumn(Modifier.heightIn(max = 380.dp)) {
        items(tr.verticales) { v ->
            VerticalRow(v.index, v, tr.thresholdDosPuntos, vm, timerRemaining)
        }
    }
}

@Composable
fun VerticalRow(i: Int, v: Vertical, threshold: Double, vm: MainViewModel, timerRemaining: Int) {
    val tel by vm.telemetry.collectAsState()
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text("Vertical ${i+1}", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                var dx by remember { mutableStateOf(v.dx.toString()) }
                var h by remember { mutableStateOf(v.h.toString()) }
                OutlinedTextField(dx, { dx = it }, label = { Text("Δx (m)") }, modifier = Modifier.width(140.dp))
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(h, { h = it }, label = { Text("h (m)") }, modifier = Modifier.width(140.dp))
                Spacer(Modifier.width(6.dp))
                var mode by remember { mutableStateOf(v.mode) }
                AssistChip(onClick = { mode = if (mode == VertMode.ONE_POINT) VertMode.TWO_POINTS else VertMode.ONE_POINT },
                    label = { Text(if (mode == VertMode.ONE_POINT) "Modo: 0.6h" else "Modo: 0.2/0.8h") })
                Spacer(Modifier.width(6.dp))
                Button(onClick = { vm.updateVertical(i, dx.toDoubleOrNull(), h.toDoubleOrNull(), mode) }) { Text("OK") }
                Spacer(Modifier.width(10.dp))
                Button(onClick = { vm.clearVelocity(i) }) { Text("Limpiar v") }
            }
            val tgts = FlowCalculator.targetDepths(v.h, threshold)
            Text("Objetivo(s): ${tgts.joinToString { "%.2f m".format(it) }}  |  RPS=%.2f  v≈%.3f m/s".format(
                tel.rps, FlowCalculator.velocityFromRps(tel.rps, vm.calib.value)
            ))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.startMeasure(i) }) { Text("Iniciar") }
                Spacer(Modifier.width(6.dp))
                Button(onClick = { vm.stopMeasure() }) { Text("Detener") }
                Spacer(Modifier.width(12.dp))
                Text("⏱ ${timerRemaining}s")
                Spacer(Modifier.width(12.dp))
                when (v.mode) {
                    VertMode.ONE_POINT -> Text("v(0.6h) = ${v.v06?.let { "%.3f".format(it) } ?: "--"} m/s")
                    VertMode.TWO_POINTS -> Text("v(0.2h)=${v.v2p?.v02?.let { "%.3f".format(it) } ?: "--"}  |  v(0.8h)=${v.v2p?.v08?.let { "%.3f".format(it) } ?: "--"}  |  v̄=${v.v2p?.mean()?.let { "%.3f".format(it) } ?: "--"}")
                }
            }
        }
    }
}

@Composable
fun SectionSketchAbs(tr: Transecta) {
    val xs = FlowCalculator.toAbsolutes(tr.verticales.map { it.dx })
    val hs = tr.verticales.map { it.h }
    val maxX = xs.maxOrNull() ?: 1.0
    val maxH = max(1.0, hs.maxOrNull() ?: 1.0)

    Box(Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawLine(Color(0xFF7A5F45), Offset(0f, h-10f), Offset(w, h-10f), strokeWidth = 6f)
            xs.zip(hs).forEach { (x, hd) ->
                val xPx = (x / maxX * (w * 0.95)).toFloat() + w*0.025f
                val yTop = (h - 10f - (hd / maxH * (h*0.7f))).coerceAtLeast(20f)
                drawLine(Color(0xFF1E88E5), Offset(xPx, yTop), Offset(xPx, h-10f), strokeWidth = 4f)
            }
        }
    }
}
