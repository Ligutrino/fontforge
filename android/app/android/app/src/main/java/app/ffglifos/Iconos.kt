package app.ffglifos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Íconos de la caja de herramientas: los mismos de FontForge de escritorio (tema "tango",
 * fontforgeexe/pixmaps/tango/palette*.png), ampliados sin suavizar para que se vean nítidos.
 * "Borrar contorno" (en el lugar de Espiral) tiene un ícono propio con el mismo estilo.
 */
object Iconos {
    private val cache = HashMap<String, Bitmap?>()

    /** Nombre base del ícono de cada herramienta (ff_<nombre>.png, ff_<nombre>_sel.png, ff_mini_<nombre>.png). */
    fun nombre(h: Herramienta, o: Opciones): String = when (h) {
        Herramienta.FLECHA -> "pointer"
        Herramienta.LUPA -> "magnify"
        Herramienta.LAPIZ -> "freehand"
        Herramienta.MANO -> "hand"
        Herramienta.CUCHILLO -> "knife"
        Herramienta.REGLA -> "ruler"
        Herramienta.PLUMA -> "pen"
        Herramienta.BORRAR -> "borrar"
        Herramienta.PUNTO_CURVO -> "curve"
        Herramienta.PUNTO_HV -> "hvcurve"
        Herramienta.PUNTO_ESQUINA -> "corner"
        Herramienta.PUNTO_TANGENTE -> "tangent"
        Herramienta.ESCALAR -> "scale"
        Herramienta.VOLTEAR -> "flip"
        Herramienta.ROTAR -> "rotate"
        Herramienta.INCLINAR -> "skew"
        Herramienta.ROTAR_3D -> "3drotate"
        Herramienta.PERSPECTIVA -> "perspective"
        Herramienta.RECTANGULO -> if (o.elipse) "elipse" else "rect"
        Herramienta.POLIGONO -> if (o.estrella) "star" else "poly"
    }

    /** Los íconos pequeños de FontForge usan otros nombres en dos casos. */
    fun nombreMini(h: Herramienta, o: Opciones): String = when (h) {
        Herramienta.LUPA -> "mag"
        Herramienta.LAPIZ -> "pencil"
        else -> nombre(h, o)
    }

    fun cargar(c: Context, archivo: String): Bitmap? = cache.getOrPut(archivo) {
        @Suppress("DiscouragedApi")
        val id = c.resources.getIdentifier(archivo, "drawable", c.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(c.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }

    /** Factor entero de ampliación: los íconos de 24 px quedan de unos 36 dp. */
    fun factor(c: Context) = max(2, (c.resources.displayMetrics.density * 1.5f).roundToInt())
}

/** Dibuja un ícono ampliado con píxeles nítidos. */
private val pIcono = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

/** Botón de la caja de herramientas, como en FontForge: solo el ícono; el elegido va sobre fondo gris. */
class BotonHerramienta(c: Context, val herramienta: Herramienta, private val opciones: Opciones) : View(c) {
    var delLapiz = false
        set(v) { field = v; invalidate() }
    var delBoton = false
        set(v) { field = v; invalidate() }

    private val k = Iconos.factor(c)
    private val d = c.resources.displayMetrics.density
    private val pBoton = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Color.rgb(245, 121, 0) }
    private val origen = Rect()
    private val destino = Rect()
    private val marco = RectF()

    init {
        contentDescription = herramienta.nombre
        if (android.os.Build.VERSION.SDK_INT >= 26) tooltipText = herramienta.nombre   // se ve al pasar el S Pen por encima
    }

    override fun onMeasure(w: Int, h: Int) {
        val lado = 26 * k + (4 * d).toInt()
        setMeasuredDimension(lado, lado)
    }

    override fun onDraw(canvas: Canvas) {
        val base = Iconos.nombre(herramienta, opciones)
        val bmp = (if (delLapiz) Iconos.cargar(context, "ff_${base}_sel") else null) ?: Iconos.cargar(context, "ff_$base")
        if (bmp != null) {
            val w = bmp.width * k; val h = bmp.height * k
            val x = (width - w) / 2; val y = (height - h) / 2
            origen.set(0, 0, bmp.width, bmp.height)
            destino.set(x, y, x + w, y + h)
            canvas.drawBitmap(bmp, origen, destino, pIcono)
        }
        if (delBoton) {
            val m = 2f * d
            marco.set(m, m, width - m, height - m)
            canvas.drawRoundRect(marco, 4 * d, 4 * d, pBoton)
        }
    }
}

/** Ícono pequeño (los de Mse1 y ^Mse1 bajo la caja). */
class IconoMini(c: Context) : View(c) {
    var archivo: String = ""
        set(v) { field = v; invalidate() }
    private val k = max(1, Iconos.factor(c) - 1)
    private val origen = Rect()
    private val destino = Rect()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(22 * k, 22 * k)

    override fun onDraw(canvas: Canvas) {
        val bmp = Iconos.cargar(context, archivo) ?: return
        origen.set(0, 0, bmp.width, bmp.height)
        destino.set(0, 0, bmp.width * k, bmp.height * k)
        canvas.drawBitmap(bmp, origen, destino, pIcono)
    }
}
