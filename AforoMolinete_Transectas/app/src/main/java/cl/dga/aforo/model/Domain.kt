
package cl.dga.aforo.model

data class TwoPointVelocity(
    var v02: Double? = null,
    var v08: Double? = null
) {
    fun mean(): Double? = if (v02 != null && v08 != null) (v02!! + v08!!) / 2.0 else null
}

enum class VertMode { ONE_POINT, TWO_POINTS }

data class Vertical(
    val index: Int,
    var dx: Double,
    var h: Double,
    var mode: VertMode = VertMode.ONE_POINT,
    var v06: Double? = null,
    var v2p: TwoPointVelocity? = null,
    var notas: String = ""
) {
    fun vMedida(): Double? = when (mode) {
        VertMode.ONE_POINT -> v06
        VertMode.TWO_POINTS -> v2p?.mean()
    }
}

data class SectionInput(
    var leftEdgeDist: Double = 0.5,
    var rightEdgeDist: Double = 0.5
)

data class Calibration(
    var a: Double = 0.0,
    var b: Double = 0.025,
    var pulsesPerRev: Int = 1
)

data class Transecta(
    var nombre: String = "Transecta 1",
    var fechaIso: String = "",
    var ubicacion: String = "",
    var timerSeg: Int = 40,
    var thresholdDosPuntos: Double = 0.75,
    var edges: SectionInput = SectionInput(),
    var verticales: MutableList<Vertical> = mutableListOf()
)

object FlowCalculator {
    fun toAbsolutes(dx: List<Double>): List<Double> {
        val xs = mutableListOf<Double>()
        var acc = 0.0
        dx.forEach { d -> acc += d; xs.add(acc) }
        return xs
    }

    fun computeAreas(verticals: List<Vertical>, edges: SectionInput): List<Double> {
        if (verticals.isEmpty()) return emptyList()
        val xs = toAbsolutes(verticals.map { it.dx })
        val hs = verticals.map { it.h }
        val n = verticals.size
        val areas = MutableList(n) { 0.0 }
        for (i in 0 until n) {
            val leftDx = if (i == 0) edges.leftEdgeDist else (xs[i] - xs[i - 1]) / 2.0
            val rightDx = if (i == n - 1) edges.rightEdgeDist else (xs[i + 1] - xs[i]) / 2.0
            areas[i] = hs[i] * (leftDx + rightDx)
        }
        return areas
    }

    fun computeDischarge(verticals: List<Vertical>, edges: SectionInput): Pair<Double, List<Double>> {
        val areas = computeAreas(verticals, edges)
        var q = 0.0
        val qi = MutableList(verticals.size) { 0.0 }
        for (i in verticals.indices) {
            val v = verticals[i].vMedida() ?: 0.0
            qi[i] = v * areas[i]
            q += qi[i]
        }
        return q to qi
    }

    fun targetDepths(h: Double, threshold: Double): List<Double> {
        return if (h <= threshold) listOf(0.6 * h) else listOf(0.2 * h, 0.8 * h)
    }

    fun velocityFromRps(rps: Double, calib: Calibration): Double = calib.a + calib.b * rps
}

object Validators {
    data class Result(val ok: Boolean, val message: String? = null)

    fun positive(name: String, v: Double?): Result =
        if (v != null && v > 0.0) Result(true) else Result(false, "$name debe ser > 0")

    fun dxReasonable(dx: List<Double>): Result {
        if (dx.isEmpty()) return Result(false, "Debe haber al menos una vertical")
        if (dx.any { it <= 0 }) return Result(false, "Todas las Δx deben ser > 0")
        if (dx.sum() > 500) return Result(false, "Ancho total > 500 m, verifique Δx")
        return Result(true)
    }

    fun depthsReasonable(hs: List<Double>): Result {
        if (hs.any { it <= 0 }) return Result(false, "Todas las profundidades h deben ser > 0")
        if (hs.any { it > 20 }) return Result(false, "h > 20 m parece inusual; verifique")
        return Result(true)
    }
}
