package app.ffglifos

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Qué métrica horizontal de un glifo se cambia. */
enum class Metrica(val nombre: String) {
    ANCHO("Ancho"), IZQUIERDO("Margen izquierdo"), DERECHO("Margen derecho"), CENTRAR("Centrar en el ancho")
}

/**
 * Métricas horizontales de un glifo, como en FontForge:
 * margen izquierdo (LBearing) = borde izquierdo del dibujo; margen derecho (RBearing) = ancho − borde derecho.
 */
object Metricas {

    /** Bordes izquierdo y derecho exactos del dibujo (con las curvas), o null si la capa está vacía. */
    fun limitesX(g: Glifo): DoubleArray? {
        var mn = Double.POSITIVE_INFINITY
        var mx = Double.NEGATIVE_INFINITY
        for (c in g.contornos) {
            for (p in c.puntos) { mn = min(mn, p.x); mx = max(mx, p.x) }
            for (i in 0 until c.tramos) {
                val t = c.tramo(i)
                for (u in extremosX(t[0], t[2], t[4], t[6])) {
                    val x = bezier(t, u).first
                    mn = min(mn, x); mx = max(mx, x)
                }
            }
        }
        return if (mn > mx) null else doubleArrayOf(mn, mx)
    }

    /** Valores de t en (0, 1) donde la x de la curva tiene un máximo o un mínimo. */
    private fun extremosX(x0: Double, x1: Double, x2: Double, x3: Double): List<Double> {
        val d0 = x1 - x0; val d1 = x2 - x1; val d2 = x3 - x2
        val a = d0 - 2 * d1 + d2; val b = 2 * (d1 - d0); val c = d0
        val r = ArrayList<Double>(2)
        if (abs(a) < 1e-12) {
            if (abs(b) > 1e-12) r += -c / b
        } else {
            val disc = b * b - 4 * a * c
            if (disc >= 0) { val s = sqrt(disc); r += (-b + s) / (2 * a); r += (-b - s) / (2 * a) }
        }
        return r.filter { it > 0 && it < 1 }
    }

    fun izquierdo(g: Glifo): Double? = limitesX(g)?.get(0)
    fun derecho(g: Glifo): Double? = limitesX(g)?.let { g.ancho - it[1] }

    /**
     * Aplica una métrica a la capa Fore (y mueve igual la capa Atrás, para que el fondo siga a la letra).
     * Devuelve el desplazamiento horizontal aplicado al dibujo (para mover también las imágenes),
     * o null si no se puede (por ejemplo, margen de un glifo vacío).
     */
    fun aplicar(fore: Glifo, atras: Glifo?, m: Metrica, valor: Double): Double? {
        if (m == Metrica.ANCHO) { fore.ancho = max(0.0, valor); return 0.0 }
        val lim = limitesX(fore) ?: return null
        when (m) {
            Metrica.DERECHO -> { fore.ancho = max(0.0, lim[1] + valor); return 0.0 }
            Metrica.IZQUIERDO -> {
                val dx = valor - lim[0]
                desplazar(fore, dx); if (atras != null) desplazar(atras, dx)
                fore.ancho = max(0.0, fore.ancho + dx)     // el margen derecho no cambia, igual que en FontForge
                return dx
            }
            else -> {   // centrar: márgenes iguales sin cambiar el ancho
                val dx = (fore.ancho - (lim[1] - lim[0])) / 2 - lim[0]
                desplazar(fore, dx); if (atras != null) desplazar(atras, dx)
                return dx
            }
        }
    }

    fun desplazar(g: Glifo, dx: Double) { for (p in g.puntos()) p.mover(dx, 0.0) }

    /** Número redondeado para mostrar. */
    fun texto(v: Double?): String = if (v == null) "—" else Math.round(v).toString()
}
