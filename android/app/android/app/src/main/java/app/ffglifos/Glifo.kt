package app.ffglifos

import kotlin.math.hypot

/** Tipos de punto, igual que FontForge (enum pointtype). */
object TipoPunto {
    const val CURVA = 0
    const val ESQUINA = 1
    const val TANGENTE = 2
    const val CURVA_HV = 3
}

/** Punto de un contorno: posición y manejadores Bézier (prev = entrada, next = salida). */
class Punto(
    var x: Double, var y: Double,
    var px: Double, var py: Double,
    var nx: Double, var ny: Double,
    var tipo: Int,
    var sel: Boolean = false,
) {
    constructor(x: Double, y: Double, tipo: Int) : this(x, y, x, y, x, y, tipo)

    fun copia() = Punto(x, y, px, py, nx, ny, tipo, sel)
    fun mover(dx: Double, dy: Double) {
        x += dx; y += dy; px += dx; py += dy; nx += dx; ny += dy
    }
    val esquina get() = tipo == TipoPunto.ESQUINA
    val tienePrev get() = px != x || py != y
    val tieneNext get() = nx != x || ny != y
    fun quitarPrev() { px = x; py = y }
    fun quitarNext() { nx = x; ny = y }
}

class Contorno(val puntos: MutableList<Punto>, var cerrado: Boolean) {
    fun copia() = Contorno(puntos.mapTo(mutableListOf()) { it.copia() }, cerrado)

    /** Cantidad de tramos (curvas entre dos puntos). */
    val tramos get() = if (puntos.isEmpty()) 0 else if (cerrado) puntos.size else puntos.size - 1

    /** Puntos de control del tramo i: x0,y0, x1,y1, x2,y2, x3,y3. */
    fun tramo(i: Int): DoubleArray {
        val a = puntos[i]
        val b = puntos[(i + 1) % puntos.size]
        return doubleArrayOf(a.x, a.y, a.nx, a.ny, b.px, b.py, b.x, b.y)
    }

    /** Invierte el sentido del contorno. */
    fun invertir() {
        puntos.reverse()
        for (p in puntos) { val tx = p.px; val ty = p.py; p.px = p.nx; p.py = p.ny; p.nx = tx; p.ny = ty }
    }
}

/** Una capa de un glifo: sus contornos y (en Fore) el ancho de avance. */
class Glifo(var ancho: Double, val contornos: MutableList<Contorno>) {
    fun copia() = Glifo(ancho, contornos.mapTo(mutableListOf()) { it.copia() })

    fun puntos(): Sequence<Punto> = contornos.asSequence().flatMap { it.puntos.asSequence() }
    fun seleccionados(): List<Punto> = puntos().filter { it.sel }.toList()
    fun deseleccionar() { for (p in puntos()) p.sel = false }

    fun aDatos(): DoubleArray {
        val v = ArrayList<Double>()
        v += ancho
        v += contornos.size.toDouble()
        for (c in contornos) {
            v += c.puntos.size.toDouble()
            v += if (c.cerrado) 1.0 else 0.0
            for (p in c.puntos) {
                v += p.x; v += p.y; v += p.px; v += p.py; v += p.nx; v += p.ny
                v += p.tipo.toDouble()
                v += if (p.sel) 1.0 else 0.0
            }
        }
        return v.toDoubleArray()
    }

    companion object {
        const val VALORES = 8

        fun desdeDatos(d: DoubleArray?, anchoPorDefecto: Double): Glifo {
            if (d == null || d.size < 2) return Glifo(anchoPorDefecto, mutableListOf())
            val g = Glifo(d[0], mutableListOf())
            var i = 2
            repeat(d[1].toInt()) {
                val n = d[i].toInt()
                val cerrado = d[i + 1] != 0.0
                i += 2
                val pts = mutableListOf<Punto>()
                repeat(n) {
                    pts += Punto(d[i], d[i + 1], d[i + 2], d[i + 3], d[i + 4], d[i + 5], d[i + 6].toInt(), d[i + 7] != 0.0)
                    i += VALORES
                }
                g.contornos += Contorno(pts, cerrado)
            }
            return g
        }
    }
}

/** Evalúa una curva Bézier cúbica (formato de Contorno.tramo) en t. */
fun bezier(c: DoubleArray, t: Double): Pair<Double, Double> {
    val u = 1 - t
    val a = u * u * u; val b = 3 * u * u * t; val cc = 3 * u * t * t; val d = t * t * t
    return Pair(a * c[0] + b * c[2] + cc * c[4] + d * c[6], a * c[1] + b * c[3] + cc * c[5] + d * c[7])
}

fun distancia(x1: Double, y1: Double, x2: Double, y2: Double) = hypot(x1 - x2, y1 - y2)
