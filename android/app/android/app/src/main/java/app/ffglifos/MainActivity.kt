package app.ffglifos

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Pantalla principal: la cuadrícula de glifos de la fuente y el editor de un glifo.
 * La fuente en curso se guarda sola en la memoria de la app al salir y se recupera al volver.
 */
class MainActivity : Activity() {

    private val autoguardado by lazy { File(filesDir, "fuente-en-curso.sfd") }

    private lateinit var raiz: LinearLayout
    private lateinit var titulo: TextView
    private lateinit var cuadricula: GridView
    private lateinit var adaptador: AdaptadorGlifos

    private lateinit var editor: EditorView
    private lateinit var barraEditor: LinearLayout
    private lateinit var estado: TextView
    private lateinit var etiquetaGlifo: TextView
    private var enEditor = false
    private var glifoActual = 'A'.code

    private var ascenso = 800.0
    private var descenso = 200.0
    private var hayCambiosSinExportar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Motor.init(filesDir.absolutePath)
        if (!(autoguardado.exists() && Motor.abrir(autoguardado.absolutePath))) Motor.nuevaFuente("Mi fuente")
        leerMetricas()

        raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(245, 246, 248)) }
        setContentView(raiz)
        // Android 15 dibuja la app bajo las barras del sistema: dejar margen para ellas.
        raiz.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        construirCuadricula()
        construirEditor()
        cargarAjustes()
        mostrarCuadricula()
    }

    override fun onPause() {
        super.onPause()
        Motor.guardarSfd(autoguardado.absolutePath)
    }

    @Deprecated("Activity sin AndroidX")
    override fun onBackPressed() {
        if (enEditor) mostrarCuadricula() else super.onBackPressed()
    }

    private fun leerMetricas() {
        val m = Motor.metricas()
        if (m != null) { ascenso = m[0]; descenso = m[1] }
    }

    // ------------------------------------------------------------------ Cuadrícula

    private val juegoBasico: List<Int> =
        (('A'..'Z') + ('a'..'z') + ('0'..'9')).map { it.code } +
            "ÁÉÍÓÚÑÜáéíóúñü.,;:¡!¿?-–—'\"()[]{}/@#$%&*+=<>«»".map { it.code }

    private fun listaParaMostrar(): List<Int> {
        val todos = Motor.listaGlifos()
        val extra = todos.filter { it !in juegoBasico && it > 32 }.sorted()
        // Glifos sin código Unicode (p. ej. "one.peq"): clave negativa, en el orden de la fuente
        val sinCodigo = todos.filter { it < 0 }.sortedDescending()
        return juegoBasico + extra + sinCodigo
    }

    /** Texto que identifica un glifo: el carácter, o su nombre si no tiene código Unicode. */
    private fun etiqueta(clave: Int): String =
        if (clave >= 0) String(Character.toChars(clave)) else Motor.nombreGlifo(clave) ?: "?"

    private fun construirCuadricula() {
        titulo = TextView(this).apply {
            textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(30, 30, 40))
            setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        adaptador = AdaptadorGlifos()
        cuadricula = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(84)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(6); verticalSpacing = dp(6)
            setPadding(dp(10), dp(6), dp(10), dp(10))
            clipToPadding = false
            adapter = adaptador
            setOnItemClickListener { _, _, pos, _ -> abrirEditor(adaptador.codigos[pos]) }
        }
    }

    private fun barraCuadricula(): View {
        val fila = fila()
        fila.addView(boton("Nueva") { preguntarNueva() })
        fila.addView(boton("Abrir…") { pedirArchivoParaAbrir() })
        fila.addView(boton("Guardar .sfd") { pedirDestino("sfd") })
        fila.addView(boton("Exportar .ttf") { pedirDestino("ttf") })
        fila.addView(boton("Exportar .otf") { pedirDestino("otf") })
        fila.addView(boton("Otro glifo…") { pedirOtroGlifo() })
        return desplazable(fila)
    }

    private fun mostrarCuadricula() {
        enEditor = false
        raiz.removeAllViews()
        titulo.text = Motor.familia() ?: "Fuente"
        raiz.addView(titulo)
        raiz.addView(barraCuadricula())
        raiz.addView(cuadricula, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        adaptador.actualizar(listaParaMostrar())
    }

    inner class AdaptadorGlifos : BaseAdapter() {
        var codigos: List<Int> = emptyList()
        fun actualizar(c: List<Int>) { codigos = c; notifyDataSetChanged() }
        override fun getCount() = codigos.size
        override fun getItem(p: Int) = codigos[p]
        override fun getItemId(p: Int) = codigos[p].toLong()
        override fun getView(p: Int, reciclada: View?, padre: ViewGroup?): View {
            val v = (reciclada as? CeldaGlifo) ?: CeldaGlifo(this@MainActivity)
            v.mostrar(etiqueta(codigos[p]), Glifo.desdeDatos(Motor.leerGlifo(codigos[p]), ascenso + descenso), ascenso, descenso)
            return v
        }
    }

    /** Vista previa pequeña de un glifo. */
    class CeldaGlifo(c: Context) : View(c) {
        private var nombre = ""
        private var glifo: Glifo? = null
        private var asc = 800.0
        private var desc = 200.0
        private val d = c.resources.displayMetrics.density
        private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 30) }
        private val texto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 125, 140); textSize = 11f * d }
        private val vacio = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 208, 215); textSize = 34f * d; textAlign = Paint.Align.CENTER }
        private val fondo = Paint().apply { color = Color.WHITE }

        fun mostrar(n: String, g: Glifo, a: Double, ds: Double) { nombre = n; glifo = g; asc = a; desc = ds; invalidate() }

        override fun onMeasure(w: Int, h: Int) {
            val ancho = MeasureSpec.getSize(w)
            setMeasuredDimension(ancho, (ancho * 1.15).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8 * d, 8 * d, fondo)
            canvas.drawText(nombre, 6 * d, 14 * d, texto)
            val g = glifo
            val area = height - 20 * d
            if (g == null || g.contornos.isEmpty()) {
                if (nombre.codePointCount(0, nombre.length) == 1) canvas.drawText(nombre, width / 2f, 20 * d + area * 0.65f, vacio)
                return
            }
            val em = asc + desc
            val esc = (area / em * 0.85).toFloat()
            val ox = (width - g.ancho.toFloat() * esc) / 2f
            val oy = 20 * d + area * 0.08f + asc.toFloat() * esc
            val camino = Path()
            for (c in g.contornos) {
                if (c.puntos.isEmpty()) continue
                camino.moveTo(ox + c.puntos[0].x.toFloat() * esc, oy - c.puntos[0].y.toFloat() * esc)
                for (i in 0 until c.tramos) {
                    val t = c.tramo(i)
                    camino.cubicTo(ox + t[2].toFloat() * esc, oy - t[3].toFloat() * esc, ox + t[4].toFloat() * esc, oy - t[5].toFloat() * esc, ox + t[6].toFloat() * esc, oy - t[7].toFloat() * esc)
                }
                if (c.cerrado) camino.close()
            }
            canvas.drawPath(camino, relleno)
        }
    }

    // ------------------------------------------------------------------ Editor

    private val puente = object : EditorView.Puente {
        override fun leer(capa: Int) = Glifo.desdeDatos(Motor.leerCapa(glifoActual, capa), ascenso + descenso)
        override fun guardar(capa: Int, g: Glifo) {
            Motor.escribirCapa(glifoActual, capa, g.aDatos())
            hayCambiosSinExportar = true
        }
        override fun nativo(capa: Int, op: OpNativa, arg: Int): Boolean = when (op) {
            OpNativa.COMBINAR -> Motor.combinar(glifoActual, capa)
            OpNativa.TIPO -> Motor.cambiarTipo(glifoActual, capa, arg)
            OpNativa.EXTREMOS -> Motor.anadirExtremos(glifoActual, capa)
            OpNativa.DIRECCION -> Motor.corregirDireccionCapa(glifoActual, capa)
            OpNativa.SOLAPAMIENTOS -> Motor.quitarSolapamientosCapa(glifoActual, capa)
        }
        override fun informar(texto: String) { estado.text = texto; actualizarCaja() }
        override fun imagenes(capa: Int): List<EditorView.ImagenFondo> {
            val info = Motor.imagenes(glifoActual, capa)
            return (0 until info.size / 6).mapNotNull { i ->
                val w = info[6 * i + 4].toInt(); val h = info[6 * i + 5].toInt()
                val px = Motor.pixelesImagen(glifoActual, capa, i) ?: return@mapNotNull null
                if (w <= 0 || h <= 0 || px.size != w * h) return@mapNotNull null
                EditorView.ImagenFondo(Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888),
                    info[6 * i], info[6 * i + 1], info[6 * i + 2], info[6 * i + 3])
            }
        }
    }

    private lateinit var caja: GridLayout
    private lateinit var etiquetaMse: TextView
    private lateinit var panelCapas: LinearLayout
    private lateinit var botonShift: Button
    private lateinit var botonCtrl: Button
    private val botonesCaja = HashMap<Herramienta, Button>()
    private lateinit var zonaEditor: LinearLayout

    private fun construirEditor() {
        estado = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(70, 75, 90)); setPadding(dp(12), dp(5), dp(12), dp(7)); setSingleLine(false) }
        etiquetaGlifo = TextView(this).apply { textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(30, 30, 40)); setPadding(dp(8), 0, dp(12), 0); gravity = Gravity.CENTER_VERTICAL }
        editor = EditorView(this, puente)

        // Barra superior con los menús
        barraEditor = fila()
        barraEditor.addView(boton("‹ Glifos") { mostrarCuadricula() })
        barraEditor.addView(etiquetaGlifo)
        barraEditor.addView(botonMenu("Edición", listOf(
            "Deshacer" to { editor.deshacer() }, "Rehacer" to { editor.rehacer() },
            "Seleccionar todo" to { editor.seleccionarTodo() }, "Deseleccionar" to { editor.deseleccionar() },
            "Combinar (quitar puntos sin abrir)" to { editor.combinar() },
            "Suprimir (abre el contorno)" to { editor.suprimir() })))
        barraEditor.addView(botonMenu("Punto", listOf(
            "Curva" to { editor.cambiarTipo(TipoPunto.CURVA) },
            "Curva horizontal/vertical" to { editor.cambiarTipo(TipoPunto.CURVA_HV) },
            "Esquina" to { editor.cambiarTipo(TipoPunto.ESQUINA) },
            "Tangente" to { editor.cambiarTipo(TipoPunto.TANGENTE) },
            "Hacer línea" to { editor.hacerLinea() })))
        barraEditor.addView(botonMenu("Elemento", listOf(
            "Añadir extremos" to { editor.operacion(OpNativa.EXTREMOS) },
            "Corregir dirección" to { editor.operacion(OpNativa.DIRECCION) },
            "Quitar solapamientos" to { editor.operacion(OpNativa.SOLAPAMIENTOS) })))
        barraEditor.addView(botonMenu("Vista", listOf(
            "Ajustar a la ventana" to { editor.ajustarVista() },
            "Mostrar u ocultar manijas de todos los puntos" to { editor.opciones.manijasDeTodos = !editor.opciones.manijasDeTodos; editor.invalidate() })))
        barraEditor.addView(boton("Ajustes") { mostrarAjustes() })
        barraEditor.addView(boton("↶") { editor.deshacer() })
        barraEditor.addView(boton("↷") { editor.rehacer() })
        botonShift = boton("Shift") { editor.shift = !editor.shift; marcar(botonShift, editor.shift) }
        botonCtrl = boton("Ctrl") { editor.ctrl = !editor.ctrl; marcar(botonCtrl, editor.ctrl) }
        barraEditor.addView(botonShift)
        barraEditor.addView(botonCtrl)
        for ((t, d) in listOf("←" to (-1 to 0), "↑" to (0 to 1), "↓" to (0 to -1), "→" to (1 to 0)))
            barraEditor.addView(boton(t) { editor.empujar(d.first, d.second) })

        // Caja de herramientas: dos columnas, como en FontForge
        caja = GridLayout(this).apply { columnCount = 2; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for (h in Herramienta.values()) {
            val b = Button(this).apply {
                text = "${h.simbolo}\n${h.nombre}"
                isAllCaps = false
                textSize = 10f
                maxLines = 3
                setPadding(dp(2), dp(2), dp(2), dp(2))
                layoutParams = GridLayout.LayoutParams().apply { width = dp(62); height = dp(56); setMargins(dp(2), dp(2), dp(2), dp(2)) }
                setOnClickListener {
                    if (editor.herramientaLapiz == h && h.tieneOpciones) mostrarOpciones(h)
                    else if (editor.herramientaLapiz == h && (h == Herramienta.PLUMA || h.tipoPunto != null)) editor.deseleccionar()   // empezar otro contorno
                    editor.herramientaLapiz = h
                    actualizarCaja()
                }
                setOnLongClickListener { editor.herramientaBoton = h; actualizarCaja(); aviso("Botón lateral del S Pen: ${h.nombre}"); true }
            }
            botonesCaja[h] = b
            caja.addView(b)
        }
        etiquetaMse = TextView(this).apply { textSize = 11f; setTextColor(Color.rgb(60, 65, 80)); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        val columnaCaja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(232, 234, 240))
            addView(caja)
            addView(etiquetaMse)
            addView(TextView(context).apply { text = "Toca: herramienta del lápiz.\nMantén: herramienta del botón lateral."; textSize = 10f; setTextColor(Color.rgb(110, 115, 130)); setPadding(dp(6), 0, dp(6), dp(8)) })
        }
        val desplazaCaja = ScrollView(this).apply { addView(columnaCaja) }

        // Panel de capas, arriba a la derecha del lienzo
        panelCapas = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 246, 247, 250))
            setPadding(dp(8), dp(4), dp(10), dp(6))
        }
        val marco = FrameLayout(this)
        marco.addView(editor, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        marco.addView(panelCapas, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(28), dp(8), 0) })

        zonaEditor = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zonaEditor.addView(desplazaCaja, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        zonaEditor.addView(marco, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun construirPanelCapas() {
        panelCapas.removeAllViews()
        panelCapas.addView(TextView(this).apply { text = "Capas"; textSize = 11f; setTextColor(Color.rgb(110, 115, 130)) })
        for (c in listOf(Capa.GUIA, Capa.FORE, Capa.ATRAS)) {
            val filaCapa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            filaCapa.addView(CheckBox(this).apply {
                isChecked = editor.visible[c] == true
                setOnCheckedChangeListener { _, v -> editor.visible[c] = v; editor.invalidate() }
            })
            filaCapa.addView(TextView(this).apply {
                text = Capa.nombre(c)
                textSize = 15f
                setPadding(dp(4), dp(6), dp(12), dp(6))
                val activa = editor.capaActiva == c
                setTypeface(typeface, if (activa) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (activa) Color.rgb(40, 90, 200) else Color.rgb(40, 40, 50))
                setOnClickListener { editor.capaActiva = c; construirPanelCapas() }
            })
            panelCapas.addView(filaCapa)
        }
    }

    private fun actualizarCaja() {
        if (!::etiquetaMse.isInitialized) return
        for ((h, b) in botonesCaja) {
            val lapiz = editor.herramientaLapiz == h
            val lateral = editor.herramientaBoton == h
            b.setBackgroundColor(when { lapiz -> Color.rgb(40, 90, 200); lateral -> Color.rgb(255, 210, 150); else -> Color.rgb(250, 250, 252) })
            b.setTextColor(if (lapiz) Color.WHITE else Color.rgb(30, 30, 40))
        }
        etiquetaMse.text = "Mse1 (lápiz): ${editor.herramientaLapiz.nombre}\n^Mse1 (botón lateral): ${editor.herramientaBoton.nombre}"
    }

    private fun marcar(b: Button, activo: Boolean) {
        b.setBackgroundColor(if (activo) Color.rgb(40, 90, 200) else Color.rgb(225, 228, 235))
        b.setTextColor(if (activo) Color.WHITE else Color.rgb(30, 30, 40))
    }

    private fun abrirEditor(codigo: Int) {
        glifoActual = codigo
        enEditor = true
        etiquetaGlifo.text = etiqueta(codigo)
        editor.cargar(ascenso, descenso)
        raiz.removeAllViews()
        raiz.addView(desplazable(barraEditor))
        raiz.addView(zonaEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        raiz.addView(estado)
        construirPanelCapas()
        actualizarCaja()
        editor.informar()
    }

    private fun mostrarOpciones(h: Herramienta) {
        val o = editor.opciones
        val cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        if (h == Herramienta.RECTANGULO) {
            val elipse = CheckBox(this).apply { text = "Elipse (en vez de rectángulo)"; isChecked = o.elipse }
            val centro = CheckBox(this).apply { text = "Arrastrar desde el centro"; isChecked = o.desdeCentro }
            cuerpo.addView(elipse); cuerpo.addView(centro)
            AlertDialog.Builder(this).setTitle("Rectángulo/elipse").setView(cuerpo)
                .setPositiveButton("Aceptar") { _, _ -> o.elipse = elipse.isChecked; o.desdeCentro = centro.isChecked }
                .setNegativeButton("Cancelar", null).show()
        } else {
            val lados = EditText(this).apply { setText(o.lados.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            val estrella = CheckBox(this).apply { text = "Estrella"; isChecked = o.estrella }
            val interior = EditText(this).apply { setText((o.radioInterior * 100).toInt().toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
            cuerpo.addView(TextView(this).apply { text = "Lados o puntas" }); cuerpo.addView(lados)
            cuerpo.addView(estrella)
            cuerpo.addView(TextView(this).apply { text = "Radio interior de la estrella (%)" }); cuerpo.addView(interior)
            AlertDialog.Builder(this).setTitle("Polígono/estrella").setView(cuerpo)
                .setPositiveButton("Aceptar") { _, _ ->
                    o.lados = (lados.text.toString().toIntOrNull() ?: 5).coerceIn(3, 60)
                    o.estrella = estrella.isChecked
                    o.radioInterior = ((interior.text.toString().toIntOrNull() ?: 50).coerceIn(5, 95)) / 100.0
                }
                .setNegativeButton("Cancelar", null).show()
        }
    }

    private fun mostrarAjustes() {
        val o = editor.opciones
        val cuerpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        val dist = EditText(this).apply { setText(o.snapDistancia.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val puntos = CheckBox(this).apply { text = "Ajustar a puntos, guías y métricas"; isChecked = o.snapAPuntos }
        val enteros = CheckBox(this).apply { text = "SnapToInt: coordenadas enteras"; isChecked = o.snapEnteros }
        val manijas = CheckBox(this).apply { text = "Mostrar manijas de todos los puntos"; isChecked = o.manijasDeTodos }
        val dedo = CheckBox(this).apply { text = "Usar el dedo como lápiz (sin S Pen)"; isChecked = editor.dedoEdita }
        cuerpo.addView(TextView(this).apply { text = "SnapDistance (píxeles)" }); cuerpo.addView(dist)
        cuerpo.addView(puntos); cuerpo.addView(enteros); cuerpo.addView(manijas); cuerpo.addView(dedo)
        AlertDialog.Builder(this).setTitle("Ajustes del editor").setView(cuerpo)
            .setPositiveButton("Aceptar") { _, _ ->
                o.snapDistancia = (dist.text.toString().toIntOrNull() ?: 10).coerceIn(0, 100)
                o.snapAPuntos = puntos.isChecked
                o.snapEnteros = enteros.isChecked
                o.manijasDeTodos = manijas.isChecked
                editor.dedoEdita = dedo.isChecked
                guardarAjustes()
                editor.invalidate()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun guardarAjustes() {
        val o = editor.opciones
        getSharedPreferences("ajustes", MODE_PRIVATE).edit()
            .putInt("snapDistancia", o.snapDistancia).putBoolean("snapEnteros", o.snapEnteros)
            .putBoolean("snapAPuntos", o.snapAPuntos).putBoolean("manijasDeTodos", o.manijasDeTodos)
            .putBoolean("dedoEdita", editor.dedoEdita).apply()
    }

    private fun cargarAjustes() {
        val o = editor.opciones
        val sp = getSharedPreferences("ajustes", MODE_PRIVATE)
        o.snapDistancia = sp.getInt("snapDistancia", 10)
        o.snapEnteros = sp.getBoolean("snapEnteros", false)
        o.snapAPuntos = sp.getBoolean("snapAPuntos", true)
        o.manijasDeTodos = sp.getBoolean("manijasDeTodos", false)
        editor.dedoEdita = sp.getBoolean("dedoEdita", false)
    }

    private fun botonMenu(titulo: String, items: List<Pair<String, () -> Unit>>): Button {
        val b = boton("$titulo ▾") {}
        b.setOnClickListener {
            val m = PopupMenu(this, b)
            items.forEachIndexed { i, (t, _) -> m.menu.add(0, i, i, t) }
            m.setOnMenuItemClickListener { item -> items[item.itemId].second(); true }
            m.show()
        }
        return b
    }

    // ------------------------------------------------------------------ Archivos

    private val pedirAbrir = 1
    private val pedirGuardar = 2
    private var formatoPendiente = "sfd"

    private fun preguntarNueva() {
        val campo = EditText(this).apply { setText("Mi fuente"); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("Nueva fuente")
            .setMessage("Nombre de la familia. La fuente actual se reemplaza; guárdala antes si la quieres.")
            .setView(campo)
            .setPositiveButton("Crear") { _, _ ->
                Motor.nuevaFuente(campo.text.toString().ifBlank { "Mi fuente" })
                leerMetricas()
                mostrarCuadricula()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirOtroGlifo() {
        val campo = EditText(this).apply { hint = "Un carácter (ñ) o un código (U+00F1)" }
        AlertDialog.Builder(this)
            .setTitle("Editar otro glifo")
            .setView(campo)
            .setPositiveButton("Abrir") { _, _ ->
                val t = campo.text.toString().trim()
                val cod = when {
                    t.startsWith("U+", true) -> t.substring(2).toIntOrNull(16)
                    t.isNotEmpty() -> t.codePointAt(0)
                    else -> null
                }
                if (cod != null && cod > 32) abrirEditor(cod) else aviso("No entendí ese carácter")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirArchivoParaAbrir() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(i, pedirAbrir)
    }

    private fun pedirDestino(formato: String) {
        formatoPendiente = formato
        val familia = (Motor.familia() ?: "fuente").replace(' ', '-')
        val tipo = when (formato) { "ttf" -> "font/ttf"; "otf" -> "font/otf"; else -> "application/octet-stream" }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = tipo
            putExtra(Intent.EXTRA_TITLE, "$familia.$formato")
        }
        startActivityForResult(i, pedirGuardar)
    }

    @Deprecated("Activity sin AndroidX")
    override fun onActivityResult(codigo: Int, resultado: Int, datos: Intent?) {
        super.onActivityResult(codigo, resultado, datos)
        val uri = datos?.data ?: return
        if (resultado != RESULT_OK) return
        when (codigo) {
            pedirAbrir -> abrirDesde(uri)
            pedirGuardar -> guardarEn(uri, formatoPendiente)
        }
    }

    private fun nombreDe(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "fuente.sfd"
        }
        return "fuente.sfd"
    }

    private fun abrirDesde(uri: Uri) {
        try {
            val nombre = nombreDe(uri).replace('/', '_')
            val tmp = File(cacheDir, nombre)
            contentResolver.openInputStream(uri)!!.use { ent -> tmp.outputStream().use { ent.copyTo(it) } }
            if (Motor.abrir(tmp.absolutePath)) {
                leerMetricas()
                mostrarCuadricula()
                aviso("Abierta: $nombre")
            } else aviso("FontForge no pudo abrir $nombre")
            tmp.delete()
        } catch (e: Exception) {
            aviso("Error al abrir: ${e.message}")
        }
    }

    private fun guardarEn(uri: Uri, formato: String) {
        try {
            val tmp = File(cacheDir, "salida.$formato")
            tmp.delete()
            val ok = if (formato == "sfd") Motor.guardarSfd(tmp.absolutePath) else Motor.exportar(tmp.absolutePath)
            if (!ok || !tmp.exists()) { aviso("FontForge no pudo generar el .$formato"); return }
            contentResolver.openOutputStream(uri, "wt")!!.use { sal -> tmp.inputStream().use { it.copyTo(sal) } }
            tmp.delete()
            if (formato != "sfd") hayCambiosSinExportar = false
            aviso("Guardado .$formato")
        } catch (e: Exception) {
            aviso("Error al guardar: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ Utilidades

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun fila() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun desplazable(v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        (v.parent as? ViewGroup)?.removeView(v)
        addView(v)
    }

    private fun boton(texto: String, accion: () -> Unit) = Button(this).apply {
        text = texto
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.rgb(30, 30, 40))
        setBackgroundColor(Color.rgb(225, 228, 235))
        minHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { accion() }
    }
}
