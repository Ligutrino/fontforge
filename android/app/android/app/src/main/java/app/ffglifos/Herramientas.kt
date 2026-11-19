package app.ffglifos

/**
 * Herramientas de la caja de FontForge, en el mismo orden (por filas, izquierda | derecha).
 * Spiro queda fuera.
 */
enum class Herramienta(val simbolo: String, val nombre: String) {
    FLECHA("↖", "Flecha"), LUPA("🔍", "Lupa"),
    LAPIZ("✎", "Lápiz"), MANO("✋", "Mano"),
    CUCHILLO("✂", "Cuchillo"), REGLA("📏", "Regla"),
    PLUMA("✒", "Pluma"), BORRAR("⌫", "Borrar contorno"),
    PUNTO_CURVO("●", "Punto curvo"), PUNTO_HV("◆", "Punto curvo H/V"),
    PUNTO_ESQUINA("■", "Punto esquina"), PUNTO_TANGENTE("▲", "Punto tangente"),
    ESCALAR("⤢", "Escalar"), VOLTEAR("⇋", "Voltear"),
    ROTAR("↻", "Rotar"), INCLINAR("⧄", "Inclinar"),
    ROTAR_3D("⟲", "Rotar en 3D"), PERSPECTIVA("⏢", "Perspectiva"),
    RECTANGULO("▭", "Rectángulo/elipse"), POLIGONO("⬠", "Polígono/estrella");

    /** Tipo de punto que crean las herramientas de agregar punto. */
    val tipoPunto: Int?
        get() = when (this) {
            PUNTO_CURVO -> TipoPunto.CURVA
            PUNTO_HV -> TipoPunto.CURVA_HV
            PUNTO_ESQUINA -> TipoPunto.ESQUINA
            PUNTO_TANGENTE -> TipoPunto.TANGENTE
            else -> null
        }

    val esTransformacion get() = this in setOf(ESCALAR, VOLTEAR, ROTAR, INCLINAR, ROTAR_3D, PERSPECTIVA)
    val tieneOpciones get() = this == RECTANGULO || this == POLIGONO
}

/** Capas de FontForge. */
object Capa {
    const val ATRAS = 0
    const val FORE = 1
    const val GUIA = -1
    fun nombre(c: Int) = when (c) { ATRAS -> "Atrás"; FORE -> "Fore"; else -> "Guía" }
}

/** Operaciones que hace el motor de FontForge sobre la capa activa. */
enum class OpNativa { COMBINAR, TIPO, EXTREMOS, DIRECCION, SOLAPAMIENTOS }

/** Opciones de herramientas y de ajuste (snap). */
class Opciones {
    var snapDistancia = 10            // en píxeles de pantalla (SnapDistance)
    var snapEnteros = false           // SnapToInt
    var snapAPuntos = true
    var manijasDeTodos = false
    var umbralArrastre = 12           // píxeles que hay que mover el lápiz antes de arrastrar puntos (evita moverlos al seleccionar)
    var elipse = false                // Rectángulo/elipse: dibuja elipse
    var desdeCentro = false           // Rectángulo/elipse: arrastrar desde el centro
    var lados = 5                     // Polígono/estrella
    var estrella = false
    var radioInterior = 0.5
}
