package app.ffglifos

/**
 * Acceso al motor de FontForge (libfontforge) a través del puente nativo libffglifos.
 * Hay una sola fuente abierta a la vez.
 *
 * Formato de una capa: [ancho, nContornos, por contorno: nPuntos, cerrado(0/1),
 * por punto: x, y, prevX, prevY, nextX, nextY, tipo, seleccionado].
 * Capas: 0 Atrás, 1 Fore, -1 Guía (común a toda la fuente).
 */
object Motor {
    init {
        System.loadLibrary("fontforge")
        System.loadLibrary("ffglifos")
    }

    @JvmStatic external fun init(home: String): Boolean
    @JvmStatic external fun nuevaFuente(familia: String): Boolean
    @JvmStatic external fun abrir(ruta: String): Boolean
    @JvmStatic external fun guardarSfd(ruta: String): Boolean
    @JvmStatic external fun exportar(ruta: String): Boolean
    @JvmStatic external fun metricas(): DoubleArray?
    @JvmStatic external fun familia(): String?
    @JvmStatic external fun listaGlifos(): IntArray
    @JvmStatic external fun leerGlifo(unicode: Int): DoubleArray?
    @JvmStatic external fun escribirGlifo(unicode: Int, datos: DoubleArray): Boolean
    @JvmStatic external fun ajustarTrazo(xy: DoubleArray, cerrado: Boolean, tolerancia: Double): DoubleArray?
    @JvmStatic external fun corregirDireccion(unicode: Int): Boolean
    @JvmStatic external fun quitarSolapamientos(unicode: Int): Boolean

    @JvmStatic external fun leerCapa(unicode: Int, capa: Int): DoubleArray?
    @JvmStatic external fun escribirCapa(unicode: Int, capa: Int, datos: DoubleArray): Boolean
    @JvmStatic external fun corregirDireccionCapa(unicode: Int, capa: Int): Boolean
    @JvmStatic external fun quitarSolapamientosCapa(unicode: Int, capa: Int): Boolean
    @JvmStatic external fun combinar(unicode: Int, capa: Int): Boolean
    @JvmStatic external fun cambiarTipo(unicode: Int, capa: Int, tipo: Int): Boolean
    @JvmStatic external fun anadirExtremos(unicode: Int, capa: Int): Boolean

    /** Nombre del glifo (sirve para los que no tienen código Unicode, cuya clave es negativa). */
    @JvmStatic external fun nombreGlifo(clave: Int): String?
    /** Imágenes de una capa: 6 valores por imagen (x, y de la esquina superior izquierda, escala x, escala y, ancho, alto). */
    @JvmStatic external fun imagenes(clave: Int, capa: Int): DoubleArray
    /** Píxeles ARGB de una imagen (ancho × alto, por filas desde arriba). */
    @JvmStatic external fun pixelesImagen(clave: Int, capa: Int, indice: Int): IntArray?
}
