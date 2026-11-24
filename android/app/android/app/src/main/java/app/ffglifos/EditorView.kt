package app.ffglifos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lienzo de edición de un glifo, al estilo de la ventana de glifo de FontForge.
 *
 * - El S Pen usa la herramienta del lápiz (Mse1). Con el botón lateral, o con Ctrl activo,
 *   usa la segunda herramienta (^Mse1).
 * - Los dedos solo mueven (un dedo) y hacen zoom (dos dedos); la palma nunca edita.
 * - Tres capas: Guía, Fore (la letra) y Atrás. Se edita la capa activa.
 */
class EditorView(context: Context, private val puente: Puente) : View(context) {

    interface Puente {
        fun leer(capa: Int): Glifo
        fun guardar(capa: Int, g: Glifo)
        fun nativo(capa: Int, op: OpNativa, arg: Int = 0): Boolean
        fun informar(texto: String)
        /** Imágenes de fondo de una capa (en FontForge suelen ir en Atrás). */
        fun imagenes(capa: Int): List<ImagenFondo> = emptyList()
        /** Mueve en horizontal las imágenes del glifo en el motor. */
        fun moverImagenes(dx: Double) {}
    }

    /** Imagen colocada en el glifo: esquina superior izquierda (x, y) y tamaño de un píxel en unidades. */
    class ImagenFondo(val bmp: Bitmap, var x: Double, val y: Double, val escalaX: Double, val escalaY: Double)

    private val imagenes = HashMap<Int, List<ImagenFondo>>()
    private val pImagen = Paint().apply { isFilterBitmap = false }   // píxeles nítidos al hacer zoom
    private val rectImagen = RectF()

    val opciones = Opciones()

    var herramientaLapiz = Herramienta.FLECHA
        set(v) { field = v; finGesto(); invalidate(); informar() }
    var herramientaBoton = Herramienta.MANO
        set(v) { field = v; informar() }
    /** Equivalentes en pantalla de las teclas Shift y Ctrl. */
    var shift = false
        set(v) { field = v; informar() }
    var ctrl = false
        set(v) { field = v; informar() }
    /** Si es verdadero, un dedo también usa las herramientas (sin lápiz). */
    var dedoEdita = false

    // ---- Capas ----
    private val capas = HashMap<Int, Glifo>()
    val visible = hashMapOf(Capa.ATRAS to true, Capa.FORE to true, Capa.GUIA to true)
    var capaActiva = Capa.FORE
        set(v) { activa.deseleccionar(); field = v; visible[v] = true; finGesto(); invalidate(); informar() }
    private val activa get() = capas.getValue(capaActiva)
    private val fore get() = capas.getValue(Capa.FORE)

    private var ascenso = 800.0
    private var descenso = 200.0

    // ---- Historial ----
    private class Estado(val capas: Map<Int, Glifo>, val dxImagenes: Double)
    /** Cuánto se han movido las imágenes desde que se abrió el glifo (para deshacer). */
    private var dxImagenes = 0.0
    private val deshacerPila = ArrayDeque<Estado>()
    private val rehacerPila = ArrayDeque<Estado>()

    // ---- Vista: pantalla = (x * escala + ox, -y * escala + oy) ----
    private var escala = 0.5f
    private var ox = 0f
    private var oy = 0f
    private var vistaLista = false

    private val densidad = resources.displayMetrics.density
    private val radioToque = 20f * densidad
    private val anchoRegla = 22f * densidad

    // ---- Estado del gesto del lápiz ----
    private enum class Modo { NADA, MOVER, MANIJA_PREV, MANIJA_NEXT, ANCHO, RECUADRO, PAN, TRAZO, LINEA, PLUMA, TRANSFORMAR, FIGURA, GUIA_H, GUIA_V, BORRAR }
    private var modo = Modo.NADA
    private var herramientaGesto = Herramienta.FLECHA
    private var lapizActivo = false
    private var lapizId = -1
    private var cambioEnGesto = false
    private var x0 = 0.0; private var y0 = 0.0          // inicio del gesto (fuente)
    private var x1 = 0.0; private var y1 = 0.0          // posición actual (fuente)
    private var sx0 = 0f; private var sy0 = 0f          // inicio del gesto (pantalla)
    private var refX = 0.0; private var refY = 0.0      // posición original del punto arrastrado
    private var puntoArrastre: Punto? = null
    private var moviendo: List<Punto> = emptyList()
    private var origenes: List<DoubleArray> = emptyList()
    private var baseTransformacion: Glifo? = null
    private val trazo = ArrayList<Double>()
    private var reglaVisible = false
    private var ultimoToque = 0L
    private var ultimoToqueX = 0f
    private var ultimoToqueY = 0f

    // ---- Dedos ----
    private var panId = -1
    private var panX = 0f
    private var panY = 0f
    private val zoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomEn(d.focusX, d.focusY, d.scaleFactor)
            return true
        }
    })

    // ---- Pinturas ----
    private fun pintura(c: Int, ancho: Float = 1f, estilo: Paint.Style = Paint.Style.STROKE) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; strokeWidth = ancho * densidad; style = estilo }
    private val pRelleno = pintura(Color.argb(55, 20, 20, 30), estilo = Paint.Style.FILL)
    private val pContorno = pintura(Color.rgb(15, 15, 20), 1.6f)
    private val pAtras = pintura(Color.rgb(150, 150, 160), 1.4f)
    private val pGuiaCapa = pintura(Color.rgb(120, 60, 200), 1f)
    private val pMetrica = pintura(Color.rgb(170, 190, 220), 1f)
    private val pBase = pintura(Color.rgb(90, 130, 200), 1.4f)
    private val pAncho = pintura(Color.rgb(200, 80, 60), 1.4f)
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 110, 150); textSize = 11f * densidad }
    private val pCurva = pintura(Color.rgb(30, 130, 60), estilo = Paint.Style.FILL)
    private val pEsquina = pintura(Color.rgb(40, 80, 200), estilo = Paint.Style.FILL)
    private val pTangente = pintura(Color.rgb(130, 60, 180), estilo = Paint.Style.FILL)
    private val pHv = pintura(Color.rgb(0, 140, 150), estilo = Paint.Style.FILL)
    private val pSel = pintura(Color.rgb(240, 130, 0), 2.4f)
    private val pManija = pintura(Color.rgb(200, 50, 150), 1.2f)
    private val pTrazo = pintura(Color.rgb(230, 90, 30), 2.4f).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pAyuda = pintura(Color.rgb(230, 90, 30), 1.4f).apply { pathEffect = DashPathEffect(floatArrayOf(8f * densidad, 5f * densidad), 0f) }
    private val pRecuadro = pintura(Color.rgb(60, 120, 220), 1.2f).apply { pathEffect = DashPathEffect(floatArrayOf(6f * densidad, 4f * densidad), 0f) }
    private val pReglaFondo = pintura(Color.rgb(236, 238, 243), estilo = Paint.Style.FILL)
    private val pReglaLinea = pintura(Color.rgb(150, 155, 170), 1f)
    private val pReglaTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 105, 120); textSize = 9f * densidad }
    private val pMedida = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 70, 20); textSize = 13f * densidad; isFakeBoldText = true }

    init {
        setBackgroundColor(Color.WHITE)
        for (c in listOf(Capa.ATRAS, Capa.FORE, Capa.GUIA)) capas[c] = Glifo(1000.0, mutableListOf())
    }

    // =====================================================================================
    // Carga, historial y comunicación con el motor
    // =====================================================================================

    fun cargar(asc: Double, desc: Double) {
        ascenso = asc; descenso = desc
        for (c in listOf(Capa.ATRAS, Capa.FORE, Capa.GUIA)) capas[c] = puente.leer(c).also { it.deseleccionar() }
        imagenes.values.flatten().forEach { it.bmp.recycle() }
        imagenes.clear()
        for (c in listOf(Capa.ATRAS, Capa.FORE)) imagenes[c] = try { puente.imagenes(c) } catch (e: OutOfMemoryError) { emptyList() }
        deshacerPila.clear(); rehacerPila.clear()
        dxImagenes = 0.0
        finGesto()
        vistaLista = false
        invalidate()
        informar()
    }

    private fun foto() = Estado(capas.mapValues { it.value.copia() }, dxImagenes)

    fun guardarEstado() {
        deshacerPila.addLast(foto())
        if (deshacerPila.size > 100) deshacerPila.removeFirst()
        rehacerPila.clear()
    }

    private fun restaurar(e: Estado) {
        for ((c, g) in e.capas) { capas[c] = g; puente.guardar(c, g) }
        moverImagenes(e.dxImagenes - dxImagenes)
        invalidate(); informar()
    }

    fun deshacer() { val e = deshacerPila.removeLastOrNull() ?: return; rehacerPila.addLast(foto()); restaurar(e) }
    fun rehacer() { val e = rehacerPila.removeLastOrNull() ?: return; deshacerPila.addLast(foto()); restaurar(e) }

    /** Guarda la capa activa en el motor. */
    private fun confirmar(capa: Int = capaActiva) {
        puente.guardar(capa, capas.getValue(capa))
        invalidate(); informar()
    }

    /** Ejecuta una operación de FontForge sobre la capa activa (usa la selección actual). */
    fun operacion(op: OpNativa, arg: Int = 0, conHistorial: Boolean = true) {
        if (conHistorial) guardarEstado()
        puente.guardar(capaActiva, activa)
        puente.nativo(capaActiva, op, arg)
        val leido = puente.leer(capaActiva)
        if (capaActiva == Capa.FORE) leido.ancho = fore.ancho
        capas[capaActiva] = leido
        invalidate(); informar()
    }

    // ---- Operaciones de menú ----

    fun seleccionarTodo() { for (p in activa.puntos()) p.sel = true; invalidate(); informar() }
    fun deseleccionar() { activa.deseleccionar(); invalidate(); informar() }

    fun suprimir() {
        if (activa.seleccionados().isEmpty()) { aviso("Selecciona puntos con la Flecha"); return }
        guardarEstado(); Geometria.suprimir(activa); confirmar()
    }

    fun combinar() {
        if (activa.seleccionados().isEmpty()) { aviso("Selecciona los puntos que quieres quitar"); return }
        operacion(OpNativa.COMBINAR)
    }

    fun cambiarTipo(tipo: Int) {
        if (activa.seleccionados().isEmpty()) { aviso("Selecciona puntos con la Flecha"); return }
        operacion(OpNativa.TIPO, tipo)
    }

    fun hacerLinea() {
        guardarEstado()
        if (Geometria.hacerLinea(activa)) confirmar() else { deshacerPila.removeLastOrNull(); aviso("Selecciona los dos puntos de los extremos del tramo") }
    }

    private fun moverImagenes(dx: Double) {
        if (dx == 0.0) return
        for (im in imagenes.values.flatten()) im.x += dx
        dxImagenes += dx
        puente.moverImagenes(dx)
    }

    /** Métricas › Ancho, márgenes o centrar (mueve también la capa Atrás y sus imágenes). */
    fun fijarMetrica(m: Metrica, valor: Double = 0.0) {
        val antes = foto()
        val dx = Metricas.aplicar(fore, capas.getValue(Capa.ATRAS), m, valor)
        if (dx == null) { aviso("El glifo no tiene dibujo en Fore: no hay márgenes que cambiar"); return }
        deshacerPila.addLast(antes); if (deshacerPila.size > 100) deshacerPila.removeFirst(); rehacerPila.clear()
        puente.guardar(Capa.FORE, fore)
        puente.guardar(Capa.ATRAS, capas.getValue(Capa.ATRAS))
        moverImagenes(dx)
        invalidate(); informar()
    }

    val ancho get() = fore.ancho
    val margenIzquierdo get() = Metricas.izquierdo(fore)
    val margenDerecho get() = Metricas.derecho(fore)

    /** Mueve la selección con las flechas: 1 unidad, o 10 con Shift. */
    fun empujar(dx: Int, dy: Int) {
        val sel = activa.seleccionados()
        if (sel.isEmpty()) { aviso("Selecciona puntos con la Flecha"); return }
        val k = if (shift) 10.0 else 1.0
        guardarEstado()
        for (p in sel) p.mover(dx * k, dy * k)
        confirmar()
    }

    // =====================================================================================
    // Vista
    // =====================================================================================

    fun ajustarVista() {
        if (width == 0 || height == 0) return
        val em = ascenso + descenso
        val anchoCaja = max(fore.ancho, em * 0.6)
        val margen = 36f * densidad
        val ex = (width - anchoRegla - 2 * margen) / anchoCaja.toFloat()
        val ey = (height - anchoRegla - 2 * margen) / (em * 1.2).toFloat()
        escala = min(ex, ey)
        ox = anchoRegla + (width - anchoRegla - anchoCaja.toFloat() * escala) / 2f
        oy = anchoRegla + (height - anchoRegla) / 2f + ((ascenso - descenso) / 2).toFloat() * escala
        vistaLista = true
        invalidate()
    }

    private fun zoomEn(px: Float, py: Float, factor: Float) {
        val nueva = (escala * factor).coerceIn(0.01f, 60f)
        val real = nueva / escala
        ox = px - (px - ox) * real
        oy = py - (py - oy) * real
        escala = nueva
        invalidate()
    }

    private fun sx(x: Double) = (x * escala + ox).toFloat()
    private fun sy(y: Double) = (-y * escala + oy).toFloat()
    private fun fx(px: Float) = ((px - ox) / escala).toDouble()
    private fun fy(py: Float) = ((oy - py) / escala).toDouble()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (!vistaLista) ajustarVista()
    }

    // =====================================================================================
    // Dibujo
    // =====================================================================================

    private fun camino(g: Glifo): Path {
        val p = Path()
        p.fillType = Path.FillType.WINDING
        for (c in g.contornos) {
            if (c.puntos.isEmpty()) continue
            p.moveTo(sx(c.puntos[0].x), sy(c.puntos[0].y))
            for (i in 0 until c.tramos) {
                val t = c.tramo(i)
                p.cubicTo(sx(t[2]), sy(t[3]), sx(t[4]), sy(t[5]), sx(t[6]), sy(t[7]))
            }
            if (c.cerrado) p.close()
        }
        return p
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!vistaLista) ajustarVista()
        val w = width.toFloat(); val h = height.toFloat()

        // Imágenes de fondo (debajo de todo, como en FontForge)
        for (capa in listOf(Capa.ATRAS, Capa.FORE)) {
            if (visible[capa] != true) continue
            for (im in imagenes[capa].orEmpty()) {
                rectImagen.set(sx(im.x), sy(im.y), sx(im.x + im.bmp.width * im.escalaX), sy(im.y - im.bmp.height * im.escalaY))
                canvas.drawBitmap(im.bmp, null, rectImagen, pImagen)
            }
        }

        // Métricas
        for ((yv, nombre) in listOf(ascenso to "ascenso", -descenso to "descenso")) {
            canvas.drawLine(0f, sy(yv), w, sy(yv), pMetrica)
            canvas.drawText(nombre, anchoRegla + 4 * densidad, sy(yv) - 3 * densidad, pTexto)
        }
        canvas.drawLine(0f, sy(0.0), w, sy(0.0), pBase)
        canvas.drawLine(sx(0.0), 0f, sx(0.0), h, pMetrica)
        canvas.drawLine(sx(fore.ancho), 0f, sx(fore.ancho), h, pAncho)
        canvas.drawText("ancho ${fore.ancho.roundToInt()}", sx(fore.ancho) + 4 * densidad, anchoRegla + 14 * densidad, pTexto)
        Metricas.limitesX(fore)?.let { lim ->
            val yb = sy(-descenso) + 14 * densidad
            canvas.drawText("izq ${Metricas.texto(lim[0])}", sx(0.0) + 4 * densidad, yb, pTexto)
            canvas.drawText("der ${Metricas.texto(fore.ancho - lim[1])}", sx(fore.ancho) + 4 * densidad, yb, pTexto)
        }

        // Capas: Atrás, Guía, Fore
        if (visible[Capa.ATRAS] == true) canvas.drawPath(camino(capas.getValue(Capa.ATRAS)), pAtras)
        if (visible[Capa.GUIA] == true) {
            val g = capas.getValue(Capa.GUIA)
            for (c in g.contornos) {
                // guías rectas: se dibujan a lo ancho de la pantalla
                if (c.puntos.size == 2 && !c.puntos[0].tieneNext && !c.puntos[1].tienePrev) {
                    val a = c.puntos[0]; val b = c.puntos[1]
                    if (a.y == b.y) { canvas.drawLine(0f, sy(a.y), w, sy(a.y), pGuiaCapa); continue }
                    if (a.x == b.x) { canvas.drawLine(sx(a.x), 0f, sx(a.x), h, pGuiaCapa); continue }
                }
                canvas.drawPath(camino(Glifo(0.0, mutableListOf(c))), pGuiaCapa)
            }
        }
        if (visible[Capa.FORE] == true) {
            val p = camino(fore)
            canvas.drawPath(p, pRelleno)
            canvas.drawPath(p, pContorno)
        }

        dibujarPuntos(canvas)
        dibujarGesto(canvas)
        dibujarReglas(canvas, w, h)
    }

    private fun dibujarPuntos(canvas: Canvas) {
        if (visible[capaActiva] != true) return
        val r = 4.5f * densidad
        val g = activa
        for (c in g.contornos) for ((i, p) in c.puntos.withIndex()) {
            val x = sx(p.x); val y = sy(p.y)
            if (p.sel || opciones.manijasDeTodos) {
                if (p.tienePrev) { canvas.drawLine(x, y, sx(p.px), sy(p.py), pManija); dibujarX(canvas, sx(p.px), sy(p.py)) }
                if (p.tieneNext) { canvas.drawLine(x, y, sx(p.nx), sy(p.ny), pManija); dibujarX(canvas, sx(p.nx), sy(p.ny)) }
            }
            when (p.tipo) {
                TipoPunto.ESQUINA -> canvas.drawRect(x - r, y - r, x + r, y + r, pEsquina)
                TipoPunto.TANGENTE -> canvas.drawPath(Path().apply { moveTo(x, y - r * 1.3f); lineTo(x + r * 1.2f, y + r); lineTo(x - r * 1.2f, y + r); close() }, pTangente)
                TipoPunto.CURVA_HV -> canvas.drawPath(Path().apply { moveTo(x, y - r * 1.3f); lineTo(x + r * 1.3f, y); lineTo(x, y + r * 1.3f); lineTo(x - r * 1.3f, y); close() }, pHv)
                else -> canvas.drawCircle(x, y, r, pCurva)
            }
            if (i == 0 && c.puntos.size > 1) canvas.drawCircle(x, y, r * 1.6f, pMetrica)   // inicio del contorno
            if (p.sel) canvas.drawCircle(x, y, r * 2f, pSel)
        }
    }

    private fun dibujarX(canvas: Canvas, x: Float, y: Float) {
        val k = 4f * densidad
        canvas.drawLine(x - k, y - k, x + k, y + k, pManija)
        canvas.drawLine(x - k, y + k, x + k, y - k, pManija)
    }

    private fun dibujarGesto(canvas: Canvas) {
        when (modo) {
            Modo.TRAZO -> if (trazo.size >= 4) {
                val t = Path(); t.moveTo(sx(trazo[0]), sy(trazo[1]))
                var i = 2
                while (i < trazo.size) { t.lineTo(sx(trazo[i]), sy(trazo[i + 1])); i += 2 }
                canvas.drawPath(t, pTrazo)
            }
            Modo.RECUADRO -> canvas.drawRect(min(sx(x0), sx(x1)), min(sy(y0), sy(y1)), max(sx(x0), sx(x1)), max(sy(y0), sy(y1)), pRecuadro)
            Modo.FIGURA -> figuraActual()?.let { canvas.drawPath(camino(Glifo(0.0, mutableListOf(it))), pAyuda) }
            Modo.GUIA_H -> canvas.drawLine(0f, sy(y1), width.toFloat(), sy(y1), pAyuda)
            Modo.GUIA_V -> canvas.drawLine(sx(x1), 0f, sx(x1), height.toFloat(), pAyuda)
            Modo.TRANSFORMAR -> { canvas.drawCircle(sx(x0), sy(y0), 6 * densidad, pAyuda); canvas.drawLine(sx(x0), sy(y0), sx(x1), sy(y1), pAyuda) }
            else -> {}
        }
        if ((modo == Modo.LINEA && herramientaGesto == Herramienta.CUCHILLO) || (herramientaGesto == Herramienta.REGLA && (modo == Modo.LINEA || reglaVisible))) {
            canvas.drawLine(sx(x0), sy(y0), sx(x1), sy(y1), pAyuda)
            if (herramientaGesto == Herramienta.REGLA) {
                val d = distancia(x0, y0, x1, y1)
                val a = Geometria.angulo(x0, y0, x1, y1)
                canvas.drawText("${"%.1f".format(d)}  ·  ${"%.1f".format(a)}°", sx(x1) + 10 * densidad, sy(y1) - 10 * densidad, pMedida)
            }
        }
    }

    private fun dibujarReglas(canvas: Canvas, w: Float, h: Float) {
        canvas.drawRect(0f, 0f, w, anchoRegla, pReglaFondo)
        canvas.drawRect(0f, 0f, anchoRegla, h, pReglaFondo)
        canvas.drawLine(0f, anchoRegla, w, anchoRegla, pReglaLinea)
        canvas.drawLine(anchoRegla, 0f, anchoRegla, h, pReglaLinea)
        // paso de las marcas: 1, 2 o 5 × 10^n unidades, con al menos ~60 px entre etiquetas
        val bruto = 60.0 * densidad / escala
        val base = 10.0.pow(floor(log10(bruto)))
        val paso = listOf(1.0, 2.0, 5.0, 10.0).map { it * base }.first { it >= bruto }
        var v = floor(fx(anchoRegla) / paso) * paso
        while (sx(v) < w) {
            val x = sx(v)
            if (x > anchoRegla) { canvas.drawLine(x, anchoRegla * 0.55f, x, anchoRegla, pReglaLinea); canvas.drawText(v.roundToInt().toString(), x + 2 * densidad, anchoRegla * 0.5f, pReglaTexto) }
            v += paso
        }
        v = floor(fy(h) / paso) * paso
        while (sy(v) > 0) {
            val y = sy(v)
            if (y > anchoRegla) { canvas.drawLine(anchoRegla * 0.55f, y, anchoRegla, y, pReglaLinea); canvas.save(); canvas.rotate(-90f, anchoRegla * 0.5f, y - 2 * densidad); canvas.drawText(v.roundToInt().toString(), anchoRegla * 0.5f, y - 2 * densidad, pReglaTexto); canvas.restore() }
            v += paso
        }
    }

    // =====================================================================================
    // Entrada
    // =====================================================================================

    private fun esLapiz(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER ||
            (dedoEdita && t == MotionEvent.TOOL_TYPE_FINGER && e.pointerCount == 1) ||
            t == MotionEvent.TOOL_TYPE_MOUSE
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = e.actionMasked
        val i = e.actionIndex
        if (!lapizActivo) {
            // El lápiz manda: si toca, aunque la palma ya esté apoyada, empieza a editar.
            if ((m == MotionEvent.ACTION_DOWN || m == MotionEvent.ACTION_POINTER_DOWN) && esLapiz(e, i)) {
                panId = -1
                lapizActivo = true
                lapizId = e.getPointerId(i)
                lapizAbajo(e, i)
            } else entradaDedos(e)
            return true
        }
        // Mientras el lápiz está en contacto se ignoran los dedos (rechazo de palma).
        val idx = e.findPointerIndex(lapizId)
        if (idx < 0) { lapizActivo = false; return true }
        when (m) {
            MotionEvent.ACTION_MOVE -> {
                for (k in 0 until e.historySize) lapizMovido(e.getHistoricalX(idx, k), e.getHistoricalY(idx, k), false)
                lapizMovido(e.getX(idx), e.getY(idx), true)
            }
            MotionEvent.ACTION_UP -> lapizArriba(e.getX(idx), e.getY(idx), false)
            MotionEvent.ACTION_POINTER_UP -> if (i == idx) lapizArriba(e.getX(idx), e.getY(idx), false)
            MotionEvent.ACTION_CANCEL -> lapizArriba(e.getX(idx), e.getY(idx), true)
        }
        return true
    }

    private fun entradaDedos(e: MotionEvent) {
        zoom.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { panId = e.getPointerId(0); panX = e.x; panY = e.y }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val resto = (0 until e.pointerCount).firstOrNull { it != e.actionIndex || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN } ?: 0
                panId = e.getPointerId(resto); panX = e.getX(resto); panY = e.getY(resto)
            }
            MotionEvent.ACTION_MOVE -> {
                val k = e.findPointerIndex(panId)
                if (k >= 0) {
                    if (!zoom.isInProgress) { ox += e.getX(k) - panX; oy += e.getY(k) - panY; invalidate() }
                    panX = e.getX(k); panY = e.getY(k)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> panId = -1
        }
    }

    private fun finGesto() {
        modo = Modo.NADA
        puntoArrastre = null
        moviendo = emptyList()
        baseTransformacion = null
        trazo.clear()
        reglaVisible = false
    }

    private fun lapizAbajo(e: MotionEvent, idx: Int) {
        val x = e.getX(idx); val y = e.getY(idx)
        val boton = (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
        val borrador = e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
        herramientaGesto = when {
            borrador -> Herramienta.BORRAR
            boton || ctrl -> herramientaBoton
            else -> herramientaLapiz
        }
        finGesto()
        cambioEnGesto = false
        sx0 = x; sy0 = y
        x0 = fx(x); y0 = fy(y); x1 = x0; y1 = y0

        // Doble toque (para seleccionar un contorno completo con la Flecha)
        val ahora = System.currentTimeMillis()
        val doble = ahora - ultimoToque < 350 && hypot(x - ultimoToqueX, y - ultimoToqueY) < radioToque
        ultimoToque = ahora; ultimoToqueX = x; ultimoToqueY = y

        // Reglas: arrastrar desde ellas crea una guía
        if (y < anchoRegla && x > anchoRegla) { modo = Modo.GUIA_H; y1 = fy(y); invalidate(); return }
        if (x < anchoRegla && y > anchoRegla) { modo = Modo.GUIA_V; x1 = fx(x); invalidate(); return }

        when (herramientaGesto) {
            Herramienta.FLECHA -> abajoFlecha(x, y, doble)
            Herramienta.LUPA -> modo = Modo.RECUADRO
            Herramienta.MANO -> modo = Modo.PAN
            Herramienta.LAPIZ -> { modo = Modo.TRAZO; trazo += x0; trazo += y0 }
            Herramienta.CUCHILLO, Herramienta.REGLA -> {
                val (a, b) = ajustar(x0, y0, emptySet())
                x0 = a; y0 = b; x1 = a; y1 = b
                modo = Modo.LINEA
            }
            Herramienta.PLUMA -> abajoPluma(x, y)
            Herramienta.PUNTO_CURVO, Herramienta.PUNTO_HV, Herramienta.PUNTO_ESQUINA, Herramienta.PUNTO_TANGENTE -> abajoAgregarPunto(x, y)
            Herramienta.BORRAR -> { modo = Modo.BORRAR; borrarEn(x, y) }
            Herramienta.RECTANGULO, Herramienta.POLIGONO -> {
                val (a, b) = ajustar(x0, y0, emptySet())
                x0 = a; y0 = b; x1 = a; y1 = b
                modo = Modo.FIGURA
            }
            else -> if (herramientaGesto.esTransformacion) {
                val (a, b) = ajustar(x0, y0, emptySet())
                x0 = a; y0 = b; x1 = a; y1 = b
                guardarEstado()
                baseTransformacion = activa.copia()
                modo = Modo.TRANSFORMAR
            }
        }
        invalidate(); informar()
    }

    private fun lapizMovido(x: Float, y: Float, ultimo: Boolean) {
        // Al tocar para seleccionar, el lápiz tiembla un poco: no arrastrar puntos, manijas ni el ancho
        // hasta que se mueva más que el umbral.
        if (!cambioEnGesto && (modo == Modo.MOVER || modo == Modo.MANIJA_PREV || modo == Modo.MANIJA_NEXT || modo == Modo.ANCHO) &&
            hypot(x - sx0, y - sy0) < opciones.umbralArrastre * densidad) return
        val fxv = fx(x); val fyv = fy(y)
        when (modo) {
            Modo.TRAZO -> {
                val n = trazo.size
                if (n < 2 || distancia(trazo[n - 2], trazo[n - 1], fxv, fyv) * escala >= 1.0) { trazo += fxv; trazo += fyv }
            }
            Modo.PAN -> { ox += x - sx0; oy += y - sy0; sx0 = x; sy0 = y }
            Modo.BORRAR -> borrarEn(x, y)
            Modo.RECUADRO -> { x1 = fxv; y1 = fyv }
            Modo.GUIA_H -> y1 = ajustarValor(fyv)
            Modo.GUIA_V -> x1 = ajustarValor(fxv)
            Modo.LINEA, Modo.FIGURA -> {
                var (a, b) = ajustar(fxv, fyv, emptySet())
                if (shift && modo == Modo.LINEA) { val r = Geometria.restringir(x0, y0, a, b); a = r.first; b = r.second }
                x1 = a; y1 = b
            }
            Modo.MOVER -> moverSeleccion(fxv, fyv)
            Modo.MANIJA_PREV, Modo.MANIJA_NEXT -> moverManija(fxv, fyv)
            Modo.PLUMA -> sacarManijas(fxv, fyv)
            Modo.ANCHO -> { if (!cambioEnGesto) { guardarEstado(); cambioEnGesto = true }; fore.ancho = max(0.0, ajustarValor(fxv).roundToInt().toDouble()) }
            Modo.TRANSFORMAR -> { x1 = fxv; y1 = fyv; aplicarTransformacion(x - sx0, y - sy0, false) }
            Modo.NADA -> {}
        }
        if (ultimo) { invalidate(); informar() }
    }

    private fun lapizArriba(x: Float, y: Float, cancelado: Boolean) {
        lapizActivo = false
        lapizId = -1
        if (cancelado) {
            if (modo == Modo.TRANSFORMAR) baseTransformacion?.let { capas[capaActiva] = it; deshacerPila.removeLastOrNull() }
            finGesto(); invalidate(); return
        }
        when (modo) {
            Modo.TRAZO -> terminarTrazo()
            Modo.RECUADRO -> if (herramientaGesto == Herramienta.LUPA) terminarLupa(x, y) else terminarRecuadro()
            Modo.LINEA -> if (herramientaGesto == Herramienta.CUCHILLO) {
                guardarEstado()
                if (Geometria.cortar(activa, x0, y0, x1, y1)) confirmar() else deshacerPila.removeLastOrNull()
            } else reglaVisible = true
            Modo.FIGURA -> figuraActual()?.let { c ->
                guardarEstado(); activa.deseleccionar()
                activa.contornos += c
                confirmar()
            }
            Modo.GUIA_H, Modo.GUIA_V -> {
                val em = ascenso + descenso
                val g = capas.getValue(Capa.GUIA)
                guardarEstado()
                g.contornos += if (modo == Modo.GUIA_H)
                    Contorno(mutableListOf(Punto(-2 * em, y1, TipoPunto.ESQUINA), Punto(4 * em, y1, TipoPunto.ESQUINA)), false)
                else Contorno(mutableListOf(Punto(x1, -2 * em, TipoPunto.ESQUINA), Punto(x1, 3 * em, TipoPunto.ESQUINA)), false)
                visible[Capa.GUIA] = true
                confirmar(Capa.GUIA)
            }
            Modo.TRANSFORMAR -> {
                if (herramientaGesto == Herramienta.VOLTEAR) aplicarTransformacion(x - sx0, y - sy0, true)
                if (hypot(x - sx0, y - sy0) < 3 && herramientaGesto != Herramienta.VOLTEAR) {
                    baseTransformacion?.let { capas[capaActiva] = it }; deshacerPila.removeLastOrNull()
                } else confirmar()
            }
            Modo.MOVER, Modo.MANIJA_PREV, Modo.MANIJA_NEXT, Modo.PLUMA, Modo.ANCHO, Modo.BORRAR -> {
                if (cambioEnGesto) confirmar(if (modo == Modo.ANCHO) Capa.FORE else capaActiva)
            }
            else -> {}
        }
        val regla = reglaVisible
        modo = Modo.NADA
        puntoArrastre = null
        baseTransformacion = null
        trazo.clear()
        reglaVisible = regla
        invalidate(); informar()
    }

    // ---- Flecha ----

    private fun abajoFlecha(x: Float, y: Float, doble: Boolean) {
        val g = activa
        // 1. manijas de los puntos seleccionados
        for (p in g.seleccionados()) {
            if (p.tieneNext && hypot(sx(p.nx) - x, sy(p.ny) - y) < radioToque) { empezarManija(p, Modo.MANIJA_NEXT); return }
            if (p.tienePrev && hypot(sx(p.px) - x, sy(p.py) - y) < radioToque) { empezarManija(p, Modo.MANIJA_PREV); return }
        }
        // 2. puntos
        val p = puntoCercano(x, y)
        if (p != null) {
            if (doble) {
                g.contornos.firstOrNull { p in it.puntos }?.puntos?.forEach { it.sel = true }
            } else if (shift) {
                p.sel = !p.sel
                if (!p.sel) return
            } else if (!p.sel) {
                g.deseleccionar(); p.sel = true
            }
            empezarMover(p)
            return
        }
        // 3. un tramo: selecciona sus dos extremos
        val tr = Geometria.tramoCercano(g, fx(x), fy(y), (radioToque / escala).toDouble())
        if (tr != null) {
            val c = g.contornos[tr.first]
            val a = c.puntos[tr.second]; val b = c.puntos[(tr.second + 1) % c.puntos.size]
            if (!shift) g.deseleccionar()
            a.sel = true; b.sel = true
            empezarMover(a)
            return
        }
        // 4. línea del ancho
        if (abs(sx(fore.ancho) - x) < radioToque) { modo = Modo.ANCHO; return }
        // 5. vacío: recuadro de selección
        if (!shift) g.deseleccionar()
        modo = Modo.RECUADRO
    }

    private fun empezarMover(p: Punto) {
        modo = Modo.MOVER
        puntoArrastre = p
        refX = p.x; refY = p.y
        moviendo = activa.seleccionados()
        origenes = moviendo.map { doubleArrayOf(it.x, it.y, it.px, it.py, it.nx, it.ny) }
    }

    private fun empezarManija(p: Punto, m: Modo) {
        modo = m
        puntoArrastre = p
        refX = if (m == Modo.MANIJA_NEXT) p.nx else p.px
        refY = if (m == Modo.MANIJA_NEXT) p.ny else p.py
    }

    private fun moverSeleccion(fxv: Double, fyv: Double) {
        var tx = refX + (fxv - x0); var ty = refY + (fyv - y0)
        if (shift) { val r = Geometria.restringir(refX, refY, tx, ty); tx = r.first; ty = r.second }
        val (ax, ay) = ajustar(tx, ty, moviendo.toSet())
        val dx = ax - refX; val dy = ay - refY
        if (!cambioEnGesto) { if (dx == 0.0 && dy == 0.0) return; guardarEstado(); cambioEnGesto = true }
        for ((k, p) in moviendo.withIndex()) {
            val o = origenes[k]
            p.x = o[0] + dx; p.y = o[1] + dy; p.px = o[2] + dx; p.py = o[3] + dy; p.nx = o[4] + dx; p.ny = o[5] + dy
        }
    }

    private fun moverManija(fxv: Double, fyv: Double) {
        val p = puntoArrastre ?: return
        var tx = refX + (fxv - x0); var ty = refY + (fyv - y0)
        if (shift) { val r = Geometria.restringir(p.x, p.y, tx, ty); tx = r.first; ty = r.second }
        val (ax, ay) = ajustar(tx, ty, setOf(p))
        if (!cambioEnGesto) { guardarEstado(); cambioEnGesto = true }
        if (modo == Modo.MANIJA_NEXT) { p.nx = ax; p.ny = ay } else { p.px = ax; p.py = ay }
        mantenerTipo(p, modo == Modo.MANIJA_NEXT)
    }

    /** Respeta el tipo del punto al mover una manija (curva: alineadas; H/V: horizontal o vertical). */
    private fun mantenerTipo(p: Punto, movioNext: Boolean) {
        if (p.tipo == TipoPunto.CURVA_HV) {
            if (movioNext) { if (abs(p.nx - p.x) >= abs(p.ny - p.y)) p.ny = p.y else p.nx = p.x }
            else { if (abs(p.px - p.x) >= abs(p.py - p.y)) p.py = p.y else p.px = p.x }
        }
        if (p.tipo == TipoPunto.CURVA || p.tipo == TipoPunto.CURVA_HV) {
            if (!p.tienePrev || !p.tieneNext) return
            if (movioNext) {
                val l = distancia(p.x, p.y, p.px, p.py); val d = distancia(p.x, p.y, p.nx, p.ny)
                if (d > 0) { p.px = p.x - (p.nx - p.x) / d * l; p.py = p.y - (p.ny - p.y) / d * l }
            } else {
                val l = distancia(p.x, p.y, p.nx, p.ny); val d = distancia(p.x, p.y, p.px, p.py)
                if (d > 0) { p.nx = p.x - (p.px - p.x) / d * l; p.ny = p.y - (p.py - p.y) / d * l }
            }
        }
    }

    private fun terminarRecuadro() {
        val l = min(x0, x1); val r = max(x0, x1); val b = min(y0, y1); val t = max(y0, y1)
        for (p in activa.puntos()) if (p.x in l..r && p.y in b..t) p.sel = true
    }

    private fun puntoCercano(x: Float, y: Float): Punto? {
        var mejor = radioToque
        var res: Punto? = null
        for (p in activa.puntos()) {
            val d = hypot(sx(p.x) - x, sy(p.y) - y)
            if (d < mejor) { mejor = d; res = p }
        }
        return res
    }

    // ---- Lupa ----

    private fun terminarLupa(x: Float, y: Float) {
        val ancho = abs(sx(x1) - sx(x0)); val alto = abs(sy(y1) - sy(y0))
        if (ancho > 20 * densidad && alto > 20 * densidad) {
            val f = min((width - anchoRegla) / ancho, (height - anchoRegla) / alto)
            val cx = (sx(x0) + sx(x1)) / 2; val cy = (sy(y0) + sy(y1)) / 2
            ox += (width + anchoRegla) / 2f - cx; oy += (height + anchoRegla) / 2f - cy
            zoomEn((width + anchoRegla) / 2f, (height + anchoRegla) / 2f, f)
        } else zoomEn(x, y, if (shift) 1 / 1.6f else 1.6f)    // con Shift aleja (Alt en el escritorio)
    }

    // ---- Lápiz a mano alzada ----

    private fun terminarTrazo() {
        val n = trazo.size / 2
        if (n < 3) return
        var largo = 0.0
        for (k in 1 until n) largo += distancia(trazo[2 * k - 2], trazo[2 * k - 1], trazo[2 * k], trazo[2 * k + 1])
        if (largo * escala < 8 * densidad) return
        val cerrado = distancia(trazo[0], trazo[1], trazo[2 * n - 2], trazo[2 * n - 1]) * escala < 28 * densidad
        val datos = Motor.ajustarTrazo(trazo.toDoubleArray(), cerrado, 2.0 * densidad / escala) ?: return
        val nuevo = Glifo.desdeDatos(datos, 0.0)
        if (nuevo.contornos.isEmpty()) return
        guardarEstado()
        activa.deseleccionar()
        activa.contornos += nuevo.contornos
        if (cerrado && capaActiva != Capa.GUIA) operacion(OpNativa.DIRECCION, conHistorial = false) else confirmar()
    }

    // ---- Pluma y agregar punto ----

    /** Extremo abierto seleccionado desde el que se sigue dibujando (lo deja al final del contorno). */
    private fun extremoActivo(): Contorno? {
        val sel = activa.seleccionados()
        if (sel.size != 1) return null
        val p = sel[0]
        val c = activa.contornos.firstOrNull { p in it.puntos } ?: return null
        if (c.cerrado) return null
        if (c.puntos.last() === p) return c
        if (c.puntos.first() === p) { c.invertir(); return c }
        return null
    }

    private fun abajoPluma(x: Float, y: Float) {
        guardarEstado()
        cambioEnGesto = true
        val c = extremoActivo()
        val toque = puntoCercano(x, y)
        // Cerrar: tocar el primer punto del contorno que se está dibujando
        if (c != null && toque != null && toque === c.puntos.first() && c.puntos.size >= 2) {
            c.cerrado = true
            activa.deseleccionar(); toque.sel = true
            puntoArrastre = toque; modo = Modo.PLUMA
            return
        }
        // Continuar desde otro extremo abierto
        if (toque != null && toque !== c?.puntos?.last()) {
            val otro = activa.contornos.firstOrNull { toque in it.puntos }
            if (otro != null && !otro.cerrado && (otro.puntos.first() === toque || otro.puntos.last() === toque)) {
                activa.deseleccionar(); toque.sel = true
                deshacerPila.removeLastOrNull(); cambioEnGesto = false
                return
            }
        }
        val (ax, ay) = ajustar(x0, y0, emptySet())
        val nuevo = Punto(ax, ay, TipoPunto.ESQUINA)
        if (c != null) c.puntos += nuevo else activa.contornos += Contorno(mutableListOf(nuevo), false)
        activa.deseleccionar(); nuevo.sel = true
        puntoArrastre = nuevo
        modo = Modo.PLUMA
    }

    /** Arrastrar con la Pluma saca las manijas del punto (simétricas, punto curvo). */
    private fun sacarManijas(fxv: Double, fyv: Double) {
        val p = puntoArrastre ?: return
        var (ax, ay) = ajustar(fxv, fyv, setOf(p))
        if (shift) { val r = Geometria.restringir(p.x, p.y, ax, ay); ax = r.first; ay = r.second }
        if (hypot(sx(ax) - sx(p.x), sy(ay) - sy(p.y)) < 4 * densidad) return
        p.nx = ax; p.ny = ay
        p.px = 2 * p.x - ax; p.py = 2 * p.y - ay
        p.tipo = TipoPunto.CURVA
    }

    private fun abajoAgregarPunto(x: Float, y: Float) {
        val tipo = herramientaGesto.tipoPunto ?: TipoPunto.CURVA
        val tr = Geometria.tramoCercano(activa, fx(x), fy(y), (radioToque / escala).toDouble())
        guardarEstado()
        cambioEnGesto = true
        if (tr != null && tr.third > 0.001 && tr.third < 0.999) {
            val c = activa.contornos[tr.first]
            val i = Geometria.dividir(c, tr.second, tr.third, tipo)
            val p = c.puntos[i]
            p.tipo = tipo
            activa.deseleccionar(); p.sel = true
            if (tipo == TipoPunto.TANGENTE || tipo == TipoPunto.CURVA_HV) {
                // FontForge ajusta las manijas según el tipo
                operacion(OpNativa.TIPO, tipo, conHistorial = false)
            }
            activa.seleccionados().firstOrNull()?.let { empezarMover(it) }
            cambioEnGesto = true
            return
        }
        // En el vacío: agrega un punto al extremo seleccionado o empieza un contorno
        val c = extremoActivo()
        val (ax, ay) = ajustar(x0, y0, emptySet())
        val nuevo = Punto(ax, ay, tipo)
        if (c != null) c.puntos += nuevo else activa.contornos += Contorno(mutableListOf(nuevo), false)
        activa.deseleccionar(); nuevo.sel = true
        empezarMover(nuevo)
        cambioEnGesto = true
    }

    // ---- Borrar contorno ----

    private fun borrarEn(x: Float, y: Float) {
        val tr = Geometria.tramoCercano(activa, fx(x), fy(y), (radioToque / escala).toDouble()) ?: return
        if (!cambioEnGesto) { guardarEstado(); cambioEnGesto = true }
        activa.contornos.removeAt(tr.first)
    }

    // ---- Transformaciones ----

    private fun aplicarTransformacion(dxPx: Float, dyPx: Float, final: Boolean) {
        val base = baseTransformacion ?: return
        val g = base.copia()
        val d = densidad
        var info = ""
        val f: ((Double, Double) -> Pair<Double, Double>)? = when (herramientaGesto) {
            Herramienta.ESCALAR -> {
                var sxv = max(0.02, 1 + dxPx / (150.0 * d))
                var syv = max(0.02, 1 - dyPx / (150.0 * d))
                if (shift) { val u = max(0.02, 1 + (dxPx - dyPx) / (300.0 * d)); sxv = u; syv = u }
                info = "Escala ${(sxv * 100).roundToInt()} % × ${(syv * 100).roundToInt()} %"
                Geometria.escalar(x0, y0, sxv, syv)
            }
            Herramienta.ROTAR -> {
                var a = -dxPx / (2.0 * d)
                if (shift) a = Math.round(a / 15.0) * 15.0
                info = "Rotar ${"%.1f".format(a)}°"
                Geometria.rotar(x0, y0, a)
            }
            Herramienta.INCLINAR -> {
                var a = (dxPx / (3.0 * d)).coerceIn(-80.0, 80.0)
                if (shift) a = Math.round(a / 5.0) * 5.0
                info = "Inclinar ${"%.1f".format(a)}°"
                Geometria.inclinar(x0, y0, a)
            }
            Herramienta.ROTAR_3D -> {
                val ay = dxPx / (2.0 * d); val ax = dyPx / (2.0 * d)
                info = "Rotar 3D: eje vertical ${"%.0f".format(ay)}°, horizontal ${"%.0f".format(ax)}°"
                Geometria.rotar3d(x0, y0, ax, ay)
            }
            Herramienta.PERSPECTIVA -> {
                val k = -dyPx / (400.0 * d) / (ascenso + descenso) * 2
                val vx = x0 + dxPx / escala
                info = "Perspectiva"
                Geometria.perspectiva(x0, y0, k, vx)
            }
            Herramienta.VOLTEAR -> if (final) {
                if (abs(dyPx) > abs(dxPx)) { info = "Volteado vertical"; Geometria.escalar(x0, y0, 1.0, -1.0) }
                else { info = "Volteado horizontal"; Geometria.escalar(x0, y0, -1.0, 1.0) }
            } else { info = if (abs(dyPx) > abs(dxPx)) "Voltear vertical" else "Voltear horizontal"; null }
            else -> null
        }
        if (f != null) {
            Geometria.transformar(g, f)
            if (herramientaGesto == Herramienta.VOLTEAR) for (c in g.contornos) if (c.cerrado) c.invertir()   // conservar la dirección
            capas[capaActiva] = g
        }
        if (info.isNotEmpty()) puente.informar(info)
    }

    // ---- Figuras ----

    private fun figuraActual(): Contorno? {
        val dx = x1 - x0; val dy = y1 - y0
        if (hypot(dx * escala, dy * escala) < 6 * densidad) return null
        return when (herramientaGesto) {
            Herramienta.RECTANGULO -> {
                var w = dx; var h = dy
                if (shift) { val m = max(abs(w), abs(h)); w = Math.copySign(m, w); h = Math.copySign(m, h) }
                if (opciones.elipse) {
                    if (opciones.desdeCentro) Geometria.elipse(x0, y0, w, h)
                    else Geometria.elipse(x0 + w / 2, y0 + h / 2, w / 2, h / 2)
                } else {
                    if (opciones.desdeCentro) Geometria.rectangulo(x0 - w, y0 - h, x0 + w, y0 + h)
                    else Geometria.rectangulo(x0, y0, x0 + w, y0 + h)
                }
            }
            Herramienta.POLIGONO -> Geometria.poligono(x0, y0, hypot(dx, dy), Geometria.angulo(x0, y0, x1, y1), opciones.lados, opciones.estrella, opciones.radioInterior)
            else -> null
        }
    }

    // =====================================================================================
    // Ajuste (snap)
    // =====================================================================================

    private fun ajustarValor(v: Double) = if (opciones.snapEnteros) Math.round(v).toDouble() else v

    /** Ajusta una posición a puntos, guías y métricas cercanas, y a enteros si está activo. */
    private fun ajustar(x: Double, y: Double, excluir: Set<Punto>): Pair<Double, Double> {
        val umbral = opciones.snapDistancia * densidad / escala
        var rx = x; var ry = y
        var listo = false
        if (opciones.snapAPuntos && opciones.snapDistancia > 0) {
            var mejor = umbral.toDouble()
            val fuentes = mutableListOf(activa)
            if (visible[Capa.GUIA] == true && capaActiva != Capa.GUIA) fuentes += capas.getValue(Capa.GUIA)
            for (g in fuentes) for (p in g.puntos()) {
                if (p in excluir) continue
                val d = distancia(p.x, p.y, x, y)
                if (d < mejor) { mejor = d; rx = p.x; ry = p.y; listo = true }
            }
            if (!listo) {
                // líneas: guías rectas y métricas
                val horizontales = mutableListOf(0.0, ascenso, -descenso)
                val verticales = mutableListOf(0.0, fore.ancho)
                if (visible[Capa.GUIA] == true) for (c in capas.getValue(Capa.GUIA).contornos) {
                    if (c.puntos.size != 2) continue
                    val a = c.puntos[0]; val b = c.puntos[1]
                    if (a.y == b.y) horizontales += a.y else if (a.x == b.x) verticales += a.x
                }
                horizontales.minByOrNull { abs(it - y) }?.let { if (abs(it - y) < umbral) ry = it }
                verticales.minByOrNull { abs(it - x) }?.let { if (abs(it - x) < umbral) rx = it }
            }
        }
        if (opciones.snapEnteros) { rx = Math.round(rx).toDouble(); ry = Math.round(ry).toDouble() }
        return Pair(rx, ry)
    }

    // =====================================================================================
    // Estado visible
    // =====================================================================================

    private fun aviso(t: String) = puente.informar(t)

    fun informar() {
        val partes = mutableListOf("Lápiz: ${herramientaLapiz.nombre}", "Botón: ${herramientaBoton.nombre}", "Capa: ${Capa.nombre(capaActiva)}")
        if (shift) partes += "Shift"
        if (ctrl) partes += "Ctrl"
        if (modo == Modo.LINEA || reglaVisible) {
            if (herramientaGesto == Herramienta.REGLA) partes += "distancia ${"%.1f".format(distancia(x0, y0, x1, y1))}, ángulo ${"%.1f".format(Geometria.angulo(x0, y0, x1, y1))}°, Δx ${"%.0f".format(x1 - x0)}, Δy ${"%.0f".format(y1 - y0)}"
        }
        val sel = activa.seleccionados()
        if (sel.size == 1) {
            val p = sel[0]
            val tipo = when (p.tipo) { TipoPunto.ESQUINA -> "esquina"; TipoPunto.TANGENTE -> "tangente"; TipoPunto.CURVA_HV -> "curva H/V"; else -> "curva" }
            partes += "x ${fmt(p.x)} y ${fmt(p.y)} ($tipo)"
        } else if (sel.size > 1) partes += "${sel.size} puntos seleccionados"
        partes += "ancho ${fore.ancho.roundToInt()}"
        partes += "izq ${Metricas.texto(margenIzquierdo)}"
        partes += "der ${Metricas.texto(margenDerecho)}"
        puente.informar(partes.joinToString("  ·  "))
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)
}
