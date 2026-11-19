package app.ffglifos

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Ventana de métricas (Ventana › Nueva ventana de métricas en FontForge): una línea de texto
 * para ver los glifos juntos y ajustar anchos, márgenes y kerning.
 */
class PantallaMetricas(
    context: Context,
    private val acciones: Acciones,
) : LinearLayout(context) {

    interface Acciones {
        fun volver()
        fun editar(clave: Int)
        fun cambiado()
        fun etiqueta(clave: Int): String
        fun dedoEdita(): Boolean
    }

    private enum class Campo(val nombre: String) { ANCHO("Ancho"), IZQ("Izq"), DER("Der"), KERN("Kern") }

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val prefs = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
    private val texto = EditText(context)
    private val vista: MetricasView
    private val nombreSel = TextView(context)
    private val botonesCampo = HashMap<Campo, Button>()
    private var campoActivo = Campo.KERN

    /** Cada cambio guarda cómo deshacerse. */
    private val historial = ArrayDeque<() -> Unit>()

    private var ascenso = 800.0
    private var descenso = 200.0

    private val datos = object : MetricasView.Datos {
        override fun glifo(clave: Int) = Glifo.desdeDatos(Motor.leerCapa(clave, Capa.FORE), ascenso + descenso)
        override fun kern(izq: Int, der: Int) = Motor.kern(izq, der)[0]
        override fun cambiar(indice: Int, que: MetricasView.Arrastre, valor: Double) {
            val clave = vista.claves[indice]
            when (que) {
                MetricasView.Arrastre.KERN -> fijarKern(indice, valor.toInt())
                MetricasView.Arrastre.ANCHO -> fijarMetrica(clave, Metrica.ANCHO, valor)
                MetricasView.Arrastre.IZQUIERDO -> fijarMetrica(clave, Metrica.IZQUIERDO, valor)
            }
        }
        override fun seleccionado(indice: Int) = actualizarPanel()
    }

    init {
        orientation = VERTICAL
        vista = MetricasView(context, datos)

        // Barra superior
        val barra = fila()
        barra.addView(boton("‹ Glifos") { acciones.volver() })
        barra.addView(TextView(context).apply { text = "Métricas"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(30, 30, 40)); setPadding(dp(8), 0, dp(12), 0) })
        barra.addView(boton("Editar glifo") { val c = claveSel(); if (c != null) acciones.editar(c) else aviso("Toca un glifo primero") })
        barra.addView(boton("Pares de kerning…") { mostrarPares() })
        barra.addView(boton("↶ Deshacer") { deshacer() })
        barra.addView(boton("Ajustar a la ventana") { vista.ajustarVista(); vista.invalidate() })
        addView(desplazable(barra))

        // Texto
        texto.apply {
            setText(prefs.getString("textoMetricas", "AVATAR Tobias LTA") ?: "")
            textSize = 20f
            setSingleLine(true)
            hint = "Texto. Glifos sin código: /nombre (p. ej. /one.peq)"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("textoMetricas", s.toString()).apply()
                    vista.mostrar(claves(s.toString()), true)
                    actualizarPanel()
                }
            })
        }
        addView(texto, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(dp(10), 0, dp(10), 0) })

        addView(vista, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Panel del glifo seleccionado
        val panel = fila()
        panel.setBackgroundColor(Color.rgb(232, 234, 240))
        nombreSel.apply { textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.rgb(30, 30, 40)); setPadding(dp(4), 0, dp(10), 0) }
        panel.addView(nombreSel)
        for (c in Campo.values()) {
            val b = boton(c.nombre) {
                if (campoActivo == c) pedirValor(c) else { campoActivo = c; actualizarPanel() }
            }
            botonesCampo[c] = b
            panel.addView(b)
        }
        for (paso in listOf(-10, -1, 1, 10)) panel.addView(boton(if (paso > 0) "+$paso" else "$paso") { sumar(paso) })
        panel.addView(boton("Centrar") { val c = claveSel(); if (c != null) fijarMetrica(c, Metrica.CENTRAR, 0.0) })
        addView(desplazable(panel))
        addView(TextView(context).apply {
            text = "Lápiz: toca un glifo para elegirlo, arrástralo para el kerning con el anterior; arrastra la línea roja para el ancho y la verde para el margen izquierdo. " +
                "Toca Ancho, Izq, Der o Kern para elegir qué cambian los botones ±; tócalo otra vez para escribir el valor."
            textSize = 11f; setTextColor(Color.rgb(90, 95, 110)); setPadding(dp(10), dp(4), dp(10), dp(6))
        })
    }

    /** Se llama cada vez que se muestra la pantalla (la fuente o sus glifos pueden haber cambiado). */
    fun mostrar(asc: Double, desc: Double) {
        ascenso = asc; descenso = desc
        vista.ascenso = asc; vista.descenso = desc
        vista.dedoEdita = acciones.dedoEdita()
        historial.clear()
        vista.mostrar(claves(texto.text.toString()), false)
        if (vista.sel < 0 && vista.claves.size > 1) vista.seleccionar(1)
        actualizarPanel()
    }

    /** Convierte el texto en claves de glifos: cada carácter, o /nombre para glifos sin código. */
    private fun claves(t: String): List<Int> {
        val r = ArrayList<Int>()
        var i = 0
        while (i < t.length) {
            if (t[i] == '/' && i + 1 < t.length && t[i + 1] != '/' && t[i + 1] != ' ') {
                var j = i + 1
                while (j < t.length && t[j] != ' ' && t[j] != '/') j++
                val c = Motor.glifoPorNombre(t.substring(i + 1, j))
                if (c != -1) { r += c; i = if (j < t.length && t[j] == ' ') j + 1 else j; continue }
            }
            if (t[i] == '/' && i + 1 < t.length && t[i + 1] == '/') i++   // "//" escribe una barra
            val cp = t.codePointAt(i)
            r += cp
            i += Character.charCount(cp)
        }
        return r
    }

    private fun claveSel(): Int? = vista.claves.getOrNull(vista.sel)

    private fun valores(): Map<Campo, Double?> {
        val c = claveSel() ?: return emptyMap()
        val g = datos.glifo(c)
        return mapOf(
            Campo.ANCHO to g.ancho,
            Campo.IZQ to Metricas.izquierdo(g),
            Campo.DER to Metricas.derecho(g),
            Campo.KERN to if (vista.sel > 0) vista.kernAntes(vista.sel) else null,
        )
    }

    private fun actualizarPanel() {
        val c = claveSel()
        val v = valores()
        nombreSel.text = if (c == null) "—" else acciones.etiqueta(c)
        for ((campo, b) in botonesCampo) {
            b.text = "${campo.nombre} ${Metricas.texto(v[campo])}"
            val activo = campo == campoActivo
            b.setBackgroundColor(if (activo) Color.rgb(40, 90, 200) else Color.rgb(250, 250, 252))
            b.setTextColor(if (activo) Color.WHITE else Color.rgb(30, 30, 40))
        }
        if (vista.sel > 0 && c != null) {
            val origen = Motor.kern(vista.claves[vista.sel - 1], c)[1]
            botonesCampo[Campo.KERN]?.text = "Kern ${Metricas.texto(v[Campo.KERN])}${if (origen == 2) " (clase)" else ""}"
        }
    }

    private fun sumar(paso: Int) {
        val c = claveSel() ?: run { aviso("Toca un glifo primero"); return }
        val actual = valores()[campoActivo]
        if (actual == null) { aviso(if (campoActivo == Campo.KERN) "El primer glifo no tiene uno anterior" else "El glifo no tiene dibujo"); return }
        aplicar(c, campoActivo, actual + paso)
    }

    private fun pedirValor(campo: Campo) {
        val c = claveSel() ?: return
        val actual = valores()[campo]
        if (actual == null) { aviso(if (campo == Campo.KERN) "El primer glifo no tiene uno anterior" else "El glifo no tiene dibujo"); return }
        val campoTexto = EditText(context).apply {
            setText(Math.round(actual).toString()); selectAll()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val titulo = when (campo) {
            Campo.ANCHO -> "Ancho de ${acciones.etiqueta(c)}"
            Campo.IZQ -> "Margen izquierdo de ${acciones.etiqueta(c)}"
            Campo.DER -> "Margen derecho de ${acciones.etiqueta(c)}"
            Campo.KERN -> "Kerning ${acciones.etiqueta(vista.claves[vista.sel - 1])}${acciones.etiqueta(c)}"
        }
        AlertDialog.Builder(context).setTitle(titulo).setView(campoTexto)
            .setPositiveButton("Aceptar") { _, _ ->
                val v = campoTexto.text.toString().toIntOrNull()
                if (v != null) aplicar(c, campo, v.toDouble()) else aviso("Escribe un número entero")
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun aplicar(clave: Int, campo: Campo, valor: Double) {
        when (campo) {
            Campo.KERN -> fijarKern(vista.sel, valor.toInt())
            Campo.ANCHO -> fijarMetrica(clave, Metrica.ANCHO, valor)
            Campo.IZQ -> fijarMetrica(clave, Metrica.IZQUIERDO, valor)
            Campo.DER -> fijarMetrica(clave, Metrica.DERECHO, valor)
        }
    }

    private fun fijarKern(indice: Int, valor: Int) {
        if (indice <= 0) return
        val izq = vista.claves[indice - 1]; val der = vista.claves[indice]
        val antes = Motor.kern(izq, der)[0]
        if (antes == valor) return
        if (!Motor.fijarKern(izq, der, valor)) { aviso("FontForge no pudo cambiar el kerning"); return }
        historial.addLast { Motor.fijarKern(izq, der, antes) }
        despuesDeCambio()
    }

    private fun fijarMetrica(clave: Int, m: Metrica, valor: Double) {
        val foreAntes = Motor.leerCapa(clave, Capa.FORE)
        val atrasAntes = Motor.leerCapa(clave, Capa.ATRAS)
        val fore = Glifo.desdeDatos(foreAntes, ascenso + descenso)
        val atras = Glifo.desdeDatos(atrasAntes, ascenso + descenso)
        val dx = Metricas.aplicar(fore, atras, m, valor)
        if (dx == null) { aviso("El glifo no tiene dibujo en Fore: no hay márgenes que cambiar"); return }
        Motor.escribirCapa(clave, Capa.FORE, fore.aDatos())
        if (dx != 0.0) {
            Motor.escribirCapa(clave, Capa.ATRAS, atras.aDatos())
            Motor.moverImagenes(clave, dx)
        }
        historial.addLast {
            if (foreAntes != null) Motor.escribirCapa(clave, Capa.FORE, foreAntes)
            if (dx != 0.0) {
                if (atrasAntes != null) Motor.escribirCapa(clave, Capa.ATRAS, atrasAntes)
                Motor.moverImagenes(clave, -dx)
            }
        }
        despuesDeCambio()
    }

    private fun deshacer() {
        val h = historial.removeLastOrNull() ?: run { aviso("Nada que deshacer"); return }
        h()
        despuesDeCambio()
    }

    private fun despuesDeCambio() {
        if (historial.size > 200) historial.removeFirst()
        acciones.cambiado()
        vista.recargar()
        actualizarPanel()
    }

    private fun mostrarPares() {
        val p = Motor.paresKern()
        val n = p.size / 3
        val clases = Motor.clasesKern()
        if (n == 0) {
            aviso(if (clases > 0) "Esta fuente usa $clases clases de kerning y no tiene pares sueltos" else "La fuente aún no tiene kerning")
            return
        }
        val orden = (0 until n).sortedWith(compareBy({ acciones.etiqueta(p[3 * it]) }, { acciones.etiqueta(p[3 * it + 1]) }))
        val nombres = orden.map { "${acciones.etiqueta(p[3 * it])} ${acciones.etiqueta(p[3 * it + 1])}    ${p[3 * it + 2]}" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Pares de kerning ($n)" + if (clases > 0) " y $clases clases" else "")
            .setItems(nombres) { _, k ->
                val i = orden[k]
                // muestra el par en la línea de texto
                val t = textoDe(p[3 * i]) + textoDe(p[3 * i + 1])
                texto.setText(t)
                texto.setSelection(texto.text.length)
                vista.seleccionar(1)
                actualizarPanel()
            }
            .setNegativeButton("Cerrar", null).show()
    }

    /** Cómo se escribe un glifo en la línea de texto. */
    private fun textoDe(clave: Int): String =
        if (clave >= 0) { val s = String(Character.toChars(clave)); if (s == "/") "//" else s }
        else "/${Motor.nombreGlifo(clave) ?: ""} "

    // ------------------------------------------------------------------ Utilidades

    private fun aviso(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    private fun fila() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun desplazable(v: LinearLayout) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(v)
    }

    private fun boton(t: String, accion: () -> Unit) = Button(context).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.rgb(30, 30, 40))
        setBackgroundColor(Color.rgb(225, 228, 235))
        minHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
        setOnClickListener { accion() }
    }
}
