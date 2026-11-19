package app.ffglifos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Línea de texto de la ventana de métricas de FontForge: los glifos uno tras otro, con su
 * ancho y su kerning. Con el S Pen:
 *  - tocar un glifo lo selecciona;
 *  - arrastrar el glifo seleccionado hacia los lados cambia el kerning con el glifo anterior;
 *  - arrastrar su línea derecha (roja) cambia el ancho; la izquierda cambia el margen izquierdo.
 * Los dedos mueven la vista (uno) y hacen zoom (dos).
 */
class MetricasView(context: Context, private val datos: Datos) : View(context) {

    interface Datos {
        fun glifo(clave: Int): Glifo
        fun kern(izq: Int, der: Int): Int
        /** Fin de un arrastre: aplica el cambio en el motor. */
        fun cambiar(indice: Int, que: Arrastre, valor: Double)
        fun seleccionado(indice: Int)
    }

    enum class Arrastre { KERN, ANCHO, IZQUIERDO }

    var claves: List<Int> = emptyList()
        private set
    var sel = -1
        private set
    var dedoEdita = false
    var ascenso = 800.0
    var descenso = 200.0

    private val cache = HashMap<Int, Glifo>()

    private val densidad = resources.displayMetrics.density
    private var escala = 0.2f
    private var ox = 0f
    private var oy = 0f
    private var vistaLista = false

    // gesto del lápiz
    private var arrastre: Arrastre? = null
    private var lapizId = -1
    private var x0 = 0f
    private var movido = false
    private var valorTemporal = 0.0       // valor en vivo del arrastre
    private var valorInicial = 0.0

    // dedos
    private var panId = -1
    private var panX = 0f
    private var panY = 0f
    private val zoom = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nueva = (escala * d.scaleFactor).coerceIn(0.01f, 5f)
            val r = nueva / escala
            ox = d.focusX - (d.focusX - ox) * r; oy = d.focusY - (d.focusY - oy) * r
            escala = nueva; invalidate(); return true
        }
    })

    private fun pintura(c: Int, ancho: Float = 1f, estilo: Paint.Style = Paint.Style.STROKE) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; strokeWidth = ancho * densidad; style = estilo }
    private val pGlifo = pintura(Color.rgb(20, 20, 28), estilo = Paint.Style.FILL)
    private val pGlifoSel = pintura(Color.rgb(30, 80, 200), estilo = Paint.Style.FILL)
    private val pCaja = pintura(Color.rgb(205, 210, 222), 1f)
    private val pCajaSel = pintura(Color.argb(40, 60, 120, 230), estilo = Paint.Style.FILL)
    private val pBase = pintura(Color.rgb(90, 130, 200), 1.2f)
    private val pMetrica = pintura(Color.rgb(190, 200, 222), 1f).apply { pathEffect = DashPathEffect(floatArrayOf(6f * densidad, 4f * densidad), 0f) }
    private val pAncho = pintura(Color.rgb(200, 80, 60), 1.6f)
    private val pIzq = pintura(Color.rgb(40, 150, 90), 1.6f)
    private val pKern = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 40, 140); textSize = 12f * densidad; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 115, 135); textSize = 11f * densidad; textAlign = Paint.Align.CENTER }
    private val pVacio = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 145, 160); textSize = 15f * densidad; textAlign = Paint.Align.CENTER }

    init { setBackgroundColor(Color.WHITE) }

    fun mostrar(nuevas: List<Int>, conservarVista: Boolean) {
        claves = nuevas
        cache.clear()
        if (sel >= claves.size) sel = claves.size - 1
        if (!conservarVista) vistaLista = false
        invalidate()
    }

    /** Vuelve a leer del motor (después de un cambio). */
    fun recargar() { cache.clear(); invalidate() }

    fun seleccionar(i: Int) { sel = i; invalidate(); datos.seleccionado(i) }

    private fun glifo(clave: Int) = cache.getOrPut(clave) { datos.glifo(clave) }

    /** Ancho de cada glifo y kerning antes de cada uno, con el arrastre en curso aplicado. */
    private fun posiciones(): DoubleArray {
        val pos = DoubleArray(claves.size + 1)
        var x = 0.0
        for (i in claves.indices) {
            if (i > 0) x += kernAntes(i)
            pos[i] = x
            x += anchoDe(i)
        }
        pos[claves.size] = x
        return pos
    }

    fun kernAntes(i: Int): Double {
        if (i <= 0) return 0.0
        if (i == sel && arrastre == Arrastre.KERN) return valorTemporal
        return datos.kern(claves[i - 1], claves[i]).toDouble()
    }

    private fun anchoDe(i: Int): Double {
        val g = glifo(claves[i])
        if (i == sel && arrastre == Arrastre.ANCHO) return valorTemporal
        if (i == sel && arrastre == Arrastre.IZQUIERDO) {
            val izq = Metricas.izquierdo(g) ?: return g.ancho
            return g.ancho + (valorTemporal - izq)
        }
        return g.ancho
    }

    /** Desplazamiento del dibujo dentro de su caja durante un arrastre del margen izquierdo. */
    private fun dxDibujo(i: Int): Double {
        if (i != sel || arrastre != Arrastre.IZQUIERDO) return 0.0
        val izq = Metricas.izquierdo(glifo(claves[i])) ?: return 0.0
        return valorTemporal - izq
    }

    fun ajustarVista() {
        if (width == 0 || height == 0) return
        val em = ascenso + descenso
        val total = max(posiciones().last(), em)
        escala = min((height * 0.7f) / em.toFloat(), (width - 40 * densidad) / total.toFloat()).coerceAtLeast(0.02f)
        ox = 20 * densidad
        oy = height / 2f + ((ascenso - descenso) / 2).toFloat() * escala
        vistaLista = true
    }

    private fun sx(x: Double) = (x * escala + ox).toFloat()
    private fun sy(y: Double) = (-y * escala + oy).toFloat()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { vistaLista = false }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!vistaLista) ajustarVista()
        val w = width.toFloat()
        if (claves.isEmpty()) {
            canvas.drawText("Escribe arriba el texto que quieres ver", w / 2, height / 2f, pVacio)
            return
        }
        canvas.drawLine(0f, sy(0.0), w, sy(0.0), pBase)
        canvas.drawLine(0f, sy(ascenso), w, sy(ascenso), pMetrica)
        canvas.drawLine(0f, sy(-descenso), w, sy(-descenso), pMetrica)

        val pos = posiciones()
        val arriba = sy(ascenso); val abajo = sy(-descenso)
        for (i in claves.indices) {
            val x = pos[i]; val a = anchoDe(i)
            if (sx(x + a) < 0 || sx(x) > w) continue
            if (i == sel) canvas.drawRect(sx(x), arriba, sx(x + a), abajo, pCajaSel)
            canvas.drawLine(sx(x), arriba, sx(x), abajo, pCaja)
            canvas.drawLine(sx(x + a), arriba, sx(x + a), abajo, pCaja)
            canvas.drawPath(camino(glifo(claves[i]), x + dxDibujo(i)), if (i == sel) pGlifoSel else pGlifo)
            val k = kernAntes(i)
            if (k != 0.0) canvas.drawText(k.roundToInt().toString(), sx(x), arriba - 6 * densidad, pKern)
        }
        if (sel in claves.indices) {
            val x = pos[sel]; val a = anchoDe(sel)
            canvas.drawLine(sx(x), arriba - 14 * densidad, sx(x), abajo + 14 * densidad, pIzq)
            canvas.drawLine(sx(x + a), arriba - 14 * densidad, sx(x + a), abajo + 14 * densidad, pAncho)
            canvas.drawText("ancho ${a.roundToInt()}", sx(x + a / 2), abajo + 28 * densidad, pTexto)
        }
    }

    private fun camino(g: Glifo, dx: Double): Path {
        val p = Path()
        for (c in g.contornos) {
            if (c.puntos.isEmpty()) continue
            p.moveTo(sx(c.puntos[0].x + dx), sy(c.puntos[0].y))
            for (i in 0 until c.tramos) {
                val t = c.tramo(i)
                p.cubicTo(sx(t[2] + dx), sy(t[3]), sx(t[4] + dx), sy(t[5]), sx(t[6] + dx), sy(t[7]))
            }
            if (c.cerrado) p.close()
        }
        p.fillType = Path.FillType.WINDING
        return p
    }

    // ------------------------------------------------------------------ Entrada

    private fun esLapiz(e: MotionEvent, i: Int): Boolean {
        val t = e.getToolType(i)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_MOUSE ||
            (dedoEdita && t == MotionEvent.TOOL_TYPE_FINGER && e.pointerCount == 1)
    }

    private fun indiceEn(x: Float): Int {
        val pos = posiciones()
        for (i in claves.indices) if (x >= sx(pos[i]) && x < sx(pos[i] + anchoDe(i))) return i
        // fuera de toda caja (por ejemplo, glifos de ancho 0): el más cercano
        return claves.indices.minByOrNull { abs(sx(pos[it]) - x) } ?: -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val m = e.actionMasked
        val i = e.actionIndex
        if (lapizId == -1) {
            if ((m == MotionEvent.ACTION_DOWN || m == MotionEvent.ACTION_POINTER_DOWN) && esLapiz(e, i)) {
                panId = -1
                lapizId = e.getPointerId(i)
                lapizAbajo(e.getX(i))
            } else entradaDedos(e)
            return true
        }
        val idx = e.findPointerIndex(lapizId)
        if (idx < 0) { lapizId = -1; arrastre = null; invalidate(); return true }
        when (m) {
            MotionEvent.ACTION_MOVE -> lapizMovido(e.getX(idx))
            MotionEvent.ACTION_UP -> lapizArriba(false)
            MotionEvent.ACTION_POINTER_UP -> if (i == idx) lapizArriba(false)
            MotionEvent.ACTION_CANCEL -> lapizArriba(true)
        }
        return true
    }

    private fun lapizAbajo(x: Float) {
        x0 = x; movido = false; arrastre = null
        if (claves.isEmpty()) return
        val radio = 18 * densidad
        val pos = posiciones()
        if (sel in claves.indices) {
            val g = glifo(claves[sel])
            when {
                abs(x - sx(pos[sel] + g.ancho)) < radio -> { arrastre = Arrastre.ANCHO; valorInicial = g.ancho }
                abs(x - sx(pos[sel])) < radio && Metricas.izquierdo(g) != null -> { arrastre = Arrastre.IZQUIERDO; valorInicial = Metricas.izquierdo(g)!! }
            }
            if (arrastre != null) { valorTemporal = valorInicial; invalidate(); return }
        }
        val k = indiceEn(x)
        if (k != sel) seleccionar(k)
        if (k > 0) { arrastre = Arrastre.KERN; valorInicial = datos.kern(claves[k - 1], claves[k]).toDouble(); valorTemporal = valorInicial }
    }

    private fun lapizMovido(x: Float) {
        val a = arrastre ?: return
        if (abs(x - x0) > 10 * densidad) movido = true
        if (!movido) return
        val d = ((x - x0) / escala).toDouble()
        valorTemporal = Math.round(when (a) {
            Arrastre.KERN -> valorInicial + d
            Arrastre.ANCHO -> max(0.0, valorInicial + d)
            Arrastre.IZQUIERDO -> valorInicial - d      // mover la línea a la derecha acerca el dibujo al borde
        }).toDouble()
        invalidate()
    }

    private fun lapizArriba(cancelado: Boolean) {
        val a = arrastre
        lapizId = -1
        arrastre = null
        if (a != null && movido && !cancelado && valorTemporal != valorInicial) {
            datos.cambiar(sel, a, valorTemporal)
            cache.remove(claves[sel])
        }
        invalidate()
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
}
