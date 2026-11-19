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

    // ---- Métricas y kerning ----
    /** Clave del glifo con ese nombre, o -1 si no existe. */
    @JvmStatic external fun glifoPorNombre(nombre: String): Int
    /** Mueve en horizontal las imágenes de todas las capas del glifo. */
    @JvmStatic external fun moverImagenes(clave: Int, dx: Double): Boolean
    /** Kerning del par: [valor, origen] con origen 0 nada, 1 par propio, 2 clase de kerning. */
    @JvmStatic external fun kern(izq: Int, der: Int): IntArray
    /** Fija el kerning del par (0 lo quita). Si el par está en una clase, cambia la clase. */
    @JvmStatic external fun fijarKern(izq: Int, der: Int, valor: Int): Boolean
    /** Todos los pares propios: tríos izq, der, valor. */
    @JvmStatic external fun paresKern(): IntArray
    /** Cantidad de clases de kerning de la fuente. */
    @JvmStatic external fun clasesKern(): Int
}
