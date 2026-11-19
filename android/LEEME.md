# FF Glifos: FontForge para tablet Android con S Pen

Paquete para compilar en Linux Mint con Docker:

1. El **motor de FontForge**, sin su interfaz de escritorio, para Android arm64 (paso 2).
2. **FF Glifos** (paso 3), una app Android que usa ese motor para dibujar y editar glifos con el S Pen.

## Compilar (Linux Mint)

Descomprime este paquete en la misma carpeta `fontforge-android` del paso 2, para reutilizar lo ya descargado en `trabajo/`:

```bash
cd ~/ruta/donde/esta            # la carpeta que contiene fontforge-android/
tar xzf ffglifos-fondos.tar.gz  # reemplaza los scripts y app/
cd fontforge-android
chmod +x compilar.sh
./compilar.sh
```

La primera vez la imagen descarga además el SDK de Android, Java y Gradle (cerca de 1,5 GB) y Gradle baja sus complementos. Tarda de 15 a 30 minutos. Las siguientes compilaciones toman pocos minutos.

Resultado: `trabajo/salida/ffglifos.apk`.

## Instalar en la tablet

Copia `ffglifos.apk` a la tablet, ábrelo desde Mis archivos y acepta "instalar apps desconocidas" cuando te lo pida. Para actualizar, instala el .apk nuevo encima: la clave de firma se guarda en `trabajo/android-home`, no la borres.

## Cómo se usa

**Pantalla de glifos:** toca un glifo para editarlo. Botones: Nueva, Abrir… (.sfd, .ttf, .otf), Guardar .sfd, Exportar .ttf, Exportar .otf y Otro glifo… (cualquier carácter o código U+XXXX).

### Ventana del glifo

A la izquierda está la **caja de herramientas** en dos columnas, como en FontForge:

| Izquierda | Derecha |
| --- | --- |
| ↖ Flecha: seleccionar y mover | 🔍 Lupa: toca para acercar; con **Shift** aleja; arrastra un recuadro para ampliarlo |
| ✎ Lápiz a mano alzada | ✋ Mano: desplaza la vista |
| ✂ Cuchillo: traza una línea que corta los contornos | 📏 Regla: mide distancia, ángulo, Δx y Δy |
| ✒ Pluma: toca para poner puntos y arrastra para sacar las manijas | ⌫ Borrar contorno (en el lugar de Espiral) |
| ● Agregar punto curvo | ◆ Agregar punto curvo horizontal/vertical |
| ■ Agregar punto esquina | ▲ Agregar punto tangente |
| ⤢ Escalar | ⇋ Voltear (arrastra en horizontal o en vertical) |
| ↻ Rotar | ⧄ Inclinar |
| ⟲ Rotar en 3D | ⏢ Perspectiva |
| ▭ Rectángulo/elipse | ⬠ Polígono/estrella |

- **Toca** una herramienta para usarla con la punta del S Pen (Mse1). **Mantenla presionada** para asignarla al **botón lateral** (^Mse1, el Ctrl + clic de FontForge). Debajo de la caja se ven las dos.
- Toca otra vez Rectángulo/elipse o Polígono/estrella para ver sus opciones: elipse, desde el centro, número de lados, estrella y radio interior.
- Con Pluma o Agregar punto, se sigue dibujando desde el extremo seleccionado. Tocar el primer punto cierra el contorno. Para empezar otro contorno, toca la herramienta otra vez.
- En las transformaciones, el lugar donde apoyas el lápiz es el origen y la distancia que arrastras da la cantidad. Se aplican a los puntos seleccionados, o a toda la capa si no hay ninguno.
- El borrador del lápiz, si tu modelo lo tiene, borra contornos.

**Capas** (arriba a la derecha): la casilla muestra u oculta cada capa, y tocar el nombre elige dónde trabajas: Guía, Fore (la letra) o Atrás.

**Barra superior:**

- **Edición:** Deshacer, Rehacer, Seleccionar todo, Deseleccionar, Combinar (quita puntos sin abrir el contorno, Ctrl+M) y Suprimir (abre el contorno).
- **Punto:** Curva, Curva H/V, Esquina, Tangente y Hacer línea.
- **Elemento:** Añadir extremos, Corregir dirección y Quitar solapamientos.
- **Vista**, **Ajustes**, ↶ ↷.
- **Shift** y **Ctrl** quedan activados al tocarlos, como si mantuvieras la tecla.
- **← ↑ ↓ →** mueven la selección 1 unidad, o 10 con Shift.

### Lo básico

| Acción | Cómo |
| --- | --- |
| Seleccionar | Flecha y tocar un punto. Con Shift activo se suman o quitan puntos. Si arrastras en el vacío, seleccionas con un recuadro. Un doble toque selecciona el contorno completo. Tocar una curva selecciona sus dos extremos. |
| Mover | Arrastrar, o usar las flechas de la barra. |
| Ajustar una curva | Seleccionar el punto y arrastrar sus X (manijas). Los puntos curvos mantienen las manijas alineadas. |
| Agregar un punto | Agregar punto (curvo, H/V, esquina o tangente) y tocar la línea negra. |
| Quitar un punto | Seleccionar y luego Edición › Combinar. Suprimir deja el contorno abierto. |
| Tipo de punto | Punto › Curva / Esquina / Tangente / Curva H/V. Redondo = curva, cuadrado = esquina, triángulo = tangente, rombo = curva H/V. |
| Dejar recto un tramo | Seleccionar sus dos extremos y Punto › Hacer línea. Para toda la letra: Edición › Seleccionar todo y Punto › Hacer línea. |
| Ancho del glifo | Con la Flecha, arrastrar la línea roja vertical. |
| Al terminar una letra | Elemento › Añadir extremos y Elemento › Corregir dirección. |
| Mover y zoom | Con los dedos: uno mueve y dos hacen zoom. Los dedos y la palma nunca editan. |

### Ajuste (snap)

- **A puntos, guías y métricas:** al arrastrar, el punto se pega a otro punto, a una guía, a la línea base, al ascenso, al descenso o al ancho. La distancia se cambia en Ajustes › SnapDistance (por omisión 10 píxeles; súbela a 15 si quieres que se note más).
- **A enteros:** Ajustes › SnapToInt.
- **Horizontal, vertical o 45°:** con Shift activo al arrastrar un punto, una manija o la Regla.
- **Guías propias:** arrastra con el lápiz desde la regla superior (guía horizontal) o la lateral (guía vertical). Quedan en la capa Guía, común a todas las letras. Para moverlas o borrarlas, elige la capa Guía.

La fuente en curso se guarda sola dentro de la app, pero solo hay una a la vez. Usa **Guardar .sfd** para tener tu archivo.

### Imágenes de fondo

Las imágenes que una fuente trae en la capa **Atrás** o **Fore** (por ejemplo, los píxeles de referencia que se calcan) se ven debajo de los contornos, con píxeles nítidos al hacer zoom. Se muestran u ocultan con la casilla de su capa y se conservan al guardar el .sfd. Por ahora solo se ven: no se pueden mover ni importar imágenes nuevas desde la app.

Los glifos sin código Unicode (como `one.peq` o `dieresis.alt`) aparecen al final de la pantalla de glifos, con su nombre.

## Si algo falla

Envíame las últimas 40 líneas de `./compilar.sh`. Si la app se cierra sola en la tablet, dime qué estabas haciendo.

## Contenido

| Archivo | Para qué |
| --- | --- |
| `Dockerfile` | Ubuntu 24.04 con NDK r27c, SDK de Android 35, Java 17 y Gradle 8.11 |
| `build.sh` | Compila libiconv, freetype, libxml2, libpng y el motor de FontForge |
| `build-app.sh` | Compila el puente nativo y la app, y genera el .apk |
| `compilar.sh` | Lo que ejecutas tú |
| `app/nativo/` | Puente en C entre la app y FontForge: `motor.c` y `jni.c`, más el ajuste de trazos a curvas en `ajuste.c` |
| `app/android/` | La app en Kotlin: `EditorView.kt` (lienzo, herramientas y S Pen), `Geometria.kt` (cuchillo, transformaciones y figuras), `MainActivity.kt` (pantallas, menús y archivos) |
| `prueba.pe` | Prueba del motor por consola (paso 2) |

Licencia: GPLv3 o posterior, igual que FontForge.
