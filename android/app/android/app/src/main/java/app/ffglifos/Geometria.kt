package app.ffglifos

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Operaciones geométricas sobre contornos, sin dependencias de Android (se prueban en PC). */
object Geometria {

    private const val KAPPA = 0.5522847498307936

    /**
     * Divide el tramo 'ti' del contorno en el parámetro t (De Casteljau) e inserta un punto.
     * Devuelve el índice del punto nuevo.
     */
    fun dividir(c: Contorno, ti: Int, t: Double, tipo: Int? = null): Int {
        val a = c.puntos[ti]
        val b = c.puntos[(ti + 1) % c.puntos.size]
        fun lerp(p: Double, q: Double) = p + (q - p) * t
        val recta = !a.tieneNext && !b.tienePrev
        val x01 = lerp(a.x, a.nx); val y01 = lerp(a.y, a.ny)
        val x12 = lerp(a.nx, b.px); val y12 = lerp(a.ny, b.py)
        val x23 = lerp(b.px, b.x); val y23 = lerp(b.py, b.y)
        val xa = lerp(x01, x12); val ya = lerp(y01, y12)
        val xb = lerp(x12, x23); val yb = lerp(y12, y23)
        val xm = xa + (xb - xa) * t; val ym = ya + (yb - ya) * t
        val nuevo = if (recta) {
            Punto(xm, ym, tipo ?: TipoPunto.ESQUINA)
        } else {
            a.nx = x01; a.ny = y01
            b.px = x23; b.py = y23
            Punto(xm, ym, xa, ya, xb, yb, tipo ?: TipoPunto.CURVA)
        }
        c.puntos.add(ti + 1, nuevo)
        return ti + 1
    }

    /** Punto más cercano sobre los tramos de una capa: (contorno, tramo, t, distancia²) en unidades de fuente. */
    fun tramoCercano(capa: Glifo, x: Double, y: Double, maxDist: Double): Triple<Int, Int, Double>? {
        var mejor = maxDist * maxDist
        var res: Triple<Int, Int, Double>? = null
        capa.contornos.forEachIndexed { ci, c ->
            for (ti in 0 until c.tramos) {
                val t = c.tramo(ti)
                var u0 = 0.0
                var d0 = Double.MAX_VALUE
                for (k in 0..48) {
                    val u = k / 48.0
                    val (bx, by) = bezier(t, u)
                    val d = (bx - x) * (bx - x) + (by - y) * (by - y)
                    if (d < d0) { d0 = d; u0 = u }
                }
                // refinar
                var paso = 1.0 / 48
                repeat(12) {
                    for (u in doubleArrayOf(u0 - paso, u0 + paso)) {
                        if (u < 0 || u > 1) continue
                        val (bx, by) = bezier(t, u)
                        val d = (bx - x) * (bx - x) + (by - y) * (by - y)
                        if (d < d0) { d0 = d; u0 = u }
                    }
                    paso /= 2
                }
                if (d0 < mejor) { mejor = d0; res = Triple(ci, ti, u0) }
            }
        }
        return res
    }

    /** Cruces del segmento A-B con el tramo: valores t en (0,1). */
    fun cruces(t: DoubleArray, ax: Double, ay: Double, bx: Double, by: Double): List<Double> {
        val dx = bx - ax; val dy = by - ay
        val largo2 = dx * dx + dy * dy
        if (largo2 == 0.0) return emptyList()
        fun f(u: Double): Double { val (x, y) = bezier(t, u); return dx * (y - ay) - dy * (x - ax) }
        fun s(u: Double): Double { val (x, y) = bezier(t, u); return ((x - ax) * dx + (y - ay) * dy) / largo2 }
        val res = ArrayList<Double>()
        val n = 96
        var u0 = 0.0
        var f0 = f(0.0)
        for (k in 1..n) {
            val u1 = k.toDouble() / n
            val f1 = f(u1)
            if (f0 == 0.0 || f0 * f1 < 0) {
                var lo = u0; var hi = u1; var flo = f0
                repeat(40) {
                    val m = (lo + hi) / 2
                    val fm = f(m)
                    if (flo * fm <= 0) hi = m else { lo = m; flo = fm }
                }
                val u = (lo + hi) / 2
                val sp = s(u)
                if (u > 1e-4 && u < 1 - 1e-4 && sp in 0.0..1.0) res += u
            }
            u0 = u1; f0 = f1
        }
        return res
    }

    /** Cuchillo: corta los contornos de la capa por donde los cruza el segmento A-B. */
    fun cortar(capa: Glifo, ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        var hubo = false
        val nuevos = ArrayList<Contorno>()
        for (c in capa.contornos) {
            // cruces por tramo
            val porTramo = HashMap<Int, List<Double>>()
            for (ti in 0 until c.tramos) {
                val cr = cruces(c.tramo(ti), ax, ay, bx, by)
                if (cr.isNotEmpty()) porTramo[ti] = cr.sorted()
            }
            if (porTramo.isEmpty()) { nuevos += c; continue }
            hubo = true
            // insertar puntos de corte, del último tramo al primero y de mayor a menor t
            val cortes = ArrayList<Punto>()
            for (ti in porTramo.keys.sortedDescending()) {
                val ts = porTramo.getValue(ti).sortedDescending()
                var escala = 1.0
                for (t in ts) {
                    val idx = dividir(c, ti, t / escala, TipoPunto.ESQUINA)
                    cortes += c.puntos[idx]
                    escala = t
                }
            }
            nuevos += partirEn(c) { it in cortes }
        }
        capa.contornos.clear()
        capa.contornos += nuevos
        return hubo
    }

    /** Parte un contorno en los puntos que cumplen 'esCorte' (el punto queda al final de un trozo y al inicio del siguiente). */
    private fun partirEn(c: Contorno, esCorte: (Punto) -> Boolean): List<Contorno> {
        val pts = c.puntos
        val idx = pts.indices.filter { esCorte(pts[it]) }
        if (idx.isEmpty()) return listOf(c)
        val trozos = ArrayList<MutableList<Punto>>()
        if (c.cerrado) {
            val inicio = idx[0]
            val rotado = (pts.indices).map { pts[(inicio + it) % pts.size] } + pts[inicio]
            var actual = mutableListOf<Punto>()
            for ((k, p) in rotado.withIndex()) {
                if (esCorte(p) && k > 0) {
                    actual += p.copia()
                    trozos += actual
                    actual = mutableListOf()
                }
                actual += if (k == 0) p else p.copia()
            }
        } else {
            var actual = mutableListOf<Punto>()
            for ((k, p) in pts.withIndex()) {
                actual += p
                if (esCorte(p) && k > 0 && k < pts.size - 1) {
                    trozos += actual
                    actual = mutableListOf(p.copia())
                }
            }
            trozos += actual
        }
        return trozos.filter { it.size >= 2 }.map { lista ->
            lista.first().quitarPrev()
            lista.last().quitarNext()
            for (p in listOf(lista.first(), lista.last())) if (p.tipo != TipoPunto.ESQUINA) p.tipo = TipoPunto.ESQUINA
            Contorno(lista, false)
        }
    }

    /** Suprimir: borra los puntos seleccionados; el contorno queda abierto donde estaban. */
    fun suprimir(capa: Glifo): Boolean {
        var hubo = false
        val nuevos = ArrayList<Contorno>()
        for (c in capa.contornos) {
            if (c.puntos.none { it.sel }) { nuevos += c; continue }
            hubo = true
            val pts = c.puntos
            val tramos = ArrayList<MutableList<Punto>>()
            if (c.cerrado) {
                val inicio = pts.indices.firstOrNull { pts[it].sel } ?: continue
                var actual = mutableListOf<Punto>()
                for (k in 1..pts.size) {
                    val p = pts[(inicio + k) % pts.size]
                    if (p.sel) { if (actual.isNotEmpty()) tramos += actual; actual = mutableListOf() } else actual += p
                }
                if (actual.isNotEmpty()) tramos += actual
            } else {
                var actual = mutableListOf<Punto>()
                for (p in pts) {
                    if (p.sel) { if (actual.isNotEmpty()) tramos += actual; actual = mutableListOf() } else actual += p
                }
                if (actual.isNotEmpty()) tramos += actual
            }
            for (t in tramos) {
                if (t.size < 2) continue
                t.first().quitarPrev(); t.last().quitarNext()
                nuevos += Contorno(t, false)
            }
        }
        capa.contornos.clear()
        capa.contornos += nuevos
        return hubo
    }

    /** Punto > Hacer línea: quita los manejadores entre puntos seleccionados contiguos. */
    fun hacerLinea(capa: Glifo): Boolean {
        var hubo = false
        for (c in capa.contornos) {
            val tocados = HashSet<Punto>()
            for (ti in 0 until c.tramos) {
                val a = c.puntos[ti]
                val b = c.puntos[(ti + 1) % c.puntos.size]
                if (a.sel && b.sel) {
                    a.quitarNext(); b.quitarPrev()
                    tocados += a; tocados += b
                }
            }
            for (p in tocados) {
                hubo = true
                p.tipo = if (p.tienePrev || p.tieneNext) TipoPunto.TANGENTE else TipoPunto.ESQUINA
            }
        }
        return hubo
    }

    /** Aplica una transformación a los puntos seleccionados (o a todos si no hay selección). */
    fun transformar(capa: Glifo, f: (Double, Double) -> Pair<Double, Double>) {
        val todos = capa.puntos().none { it.sel }
        for (p in capa.puntos()) {
            if (!todos && !p.sel) continue
            val (x, y) = f(p.x, p.y)
            val (px, py) = if (p.tienePrev) f(p.px, p.py) else Pair(x, y)
            val (nx, ny) = if (p.tieneNext) f(p.nx, p.ny) else Pair(x, y)
            p.x = x; p.y = y; p.px = px; p.py = py; p.nx = nx; p.ny = ny
        }
    }

    // ---- Transformaciones (origen ox, oy) ----

    fun escalar(ox: Double, oy: Double, sx: Double, sy: Double) =
        { x: Double, y: Double -> Pair(ox + (x - ox) * sx, oy + (y - oy) * sy) }

    fun rotar(ox: Double, oy: Double, grados: Double): (Double, Double) -> Pair<Double, Double> {
        val a = grados * PI / 180; val c = cos(a); val s = sin(a)
        return { x, y -> Pair(ox + (x - ox) * c - (y - oy) * s, oy + (x - ox) * s + (y - oy) * c) }
    }

    fun inclinar(ox: Double, oy: Double, grados: Double): (Double, Double) -> Pair<Double, Double> {
        val k = tan(grados * PI / 180)
        return { x, y -> Pair(x + (y - oy) * k, y) }
    }

    /** Rotación en 3D alrededor del eje vertical (ay) y horizontal (ax), proyectada sobre el plano. */
    fun rotar3d(ox: Double, oy: Double, axGrados: Double, ayGrados: Double): (Double, Double) -> Pair<Double, Double> {
        val ax = axGrados * PI / 180; val ay = ayGrados * PI / 180
        return { x, y ->
            val X = x - ox; val Y = y - oy
            val x1 = X * cos(ay); val z1 = -X * sin(ay)
            val y2 = Y * cos(ax) - z1 * sin(ax)
            Pair(ox + x1, oy + y2)
        }
    }

    /** Perspectiva: lo que está más arriba (k > 0) se achica hacia un punto de fuga horizontal vx. */
    fun perspectiva(ox: Double, oy: Double, k: Double, vx: Double): (Double, Double) -> Pair<Double, Double> = { x, y ->
        val f = 1.0 / max(0.05, 1 + k * (y - oy))
        Pair(vx + (x - vx) * f, oy + (y - oy) * f)
    }

    // ---- Figuras (sentido horario, como espera FontForge para contornos exteriores) ----

    fun rectangulo(x0: Double, y0: Double, x1: Double, y1: Double): Contorno {
        val l = min(x0, x1); val r = max(x0, x1); val b = min(y0, y1); val t = max(y0, y1)
        return Contorno(mutableListOf(
            Punto(l, b, TipoPunto.ESQUINA), Punto(l, t, TipoPunto.ESQUINA),
            Punto(r, t, TipoPunto.ESQUINA), Punto(r, b, TipoPunto.ESQUINA)), true)
    }

    fun elipse(cx: Double, cy: Double, rx: Double, ry: Double): Contorno {
        val kx = abs(rx) * KAPPA; val ky = abs(ry) * KAPPA
        val a = abs(rx); val b = abs(ry)
        return Contorno(mutableListOf(
            Punto(cx - a, cy, cx - a, cy - ky, cx - a, cy + ky, TipoPunto.CURVA),
            Punto(cx, cy + b, cx - kx, cy + b, cx + kx, cy + b, TipoPunto.CURVA),
            Punto(cx + a, cy, cx + a, cy + ky, cx + a, cy - ky, TipoPunto.CURVA),
            Punto(cx, cy - b, cx + kx, cy - b, cx - kx, cy - b, TipoPunto.CURVA)), true)
    }

    fun poligono(cx: Double, cy: Double, radio: Double, anguloGrados: Double, lados: Int, estrella: Boolean, interior: Double): Contorno {
        val pts = mutableListOf<Punto>()
        val n = max(3, lados)
        val a0 = anguloGrados * PI / 180
        val pasos = if (estrella) 2 * n else n
        for (k in 0 until pasos) {
            val a = a0 - 2 * PI * k / pasos          // sentido horario
            val r = if (estrella && k % 2 == 1) radio * interior else radio
            pts += Punto(cx + r * cos(a), cy + r * sin(a), TipoPunto.ESQUINA)
        }
        return Contorno(pts, true)
    }

    /** Ángulo en grados de A a B. */
    fun angulo(ax: Double, ay: Double, bx: Double, by: Double) = atan2(by - ay, bx - ax) * 180 / PI

    /** Restringe B respecto de A a horizontal, vertical o 45° (como Shift en FontForge). */
    fun restringir(ax: Double, ay: Double, bx: Double, by: Double): Pair<Double, Double> {
        val dx = bx - ax; val dy = by - ay
        val ang = atan2(dy, dx)
        val paso = PI / 4
        val q = Math.round(ang / paso) * paso
        val largo = sqrt(dx * dx + dy * dy)
        val proy = largo * cos(ang - q)
        return Pair(ax + proy * cos(q), ay + proy * sin(q))
    }
}
