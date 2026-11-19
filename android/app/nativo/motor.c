/*
 * Puente entre la app Android y el motor de FontForge (libfontforge).
 * Mantiene una fuente abierta a la vez y expone operaciones simples
 * sobre glifos usando arreglos de doubles.
 *
 * Formato de un glifo (motor_get_glyph / motor_set_glyph):
 *   [ancho, nContornos,
 *     por contorno: nPuntos, cerrado(0/1),
 *       por punto: x, y, prevX, prevY, nextX, nextY, tipo, seleccionado ]
 * tipo: 0 curva, 1 esquina, 2 tangente, 3 curva horizontal/vertical (enum pointtype).
 * Capas: 0 = Atrás, 1 = Fore (la letra), -1 = Guía (común a toda la fuente).
 * prev/next son los manejadores Bézier cúbicos (iguales a x,y si no hay).
 *
 * Licencia: GPLv3 o posterior, igual que FontForge.
 */
#include <fontforge-config.h>

#include "motor.h"
#include "ajuste.h"

#include "encoding.h"
#include "fontforgevw.h"
#include "fvfonts.h"
#include "gfile.h"
#include "savefont.h"
#include "sfd.h"
#include "splinefont.h"
#include "splineorder2.h"
#include "splineutil.h"
#include "splineutil2.h"
#include "splineoverlap.h"
#include "start.h"
#include "uiinterface.h"
#include "ustring.h"
#include "gimage.h"

#include <stdlib.h>
#include <string.h>

static SplineChar *buscar(int unicode, int crear);

static FontViewBase *fv_actual = NULL;
static int iniciado = 0;

#define VALORES_POR_PUNTO 8

int motor_init(const char *home) {
    if (iniciado) return 1;
    if (home != NULL && *home) {
        setenv("HOME", home, 1);
        setenv("XDG_CONFIG_HOME", home, 1);
        setenv("XDG_CACHE_HOME", home, 1);
        setenv("TMPDIR", home, 1);
    }
    no_windowing_ui = 1;
    FindProgRoot("/system/bin/fontforge");
    InitSimpleStuff();
    if (GetDefaultEncoding() == NULL)
        SetDefaultEncoding(FindOrMakeEncoding("ISO8859-1"));
    if (GetDefaultEncoding() == NULL)
        SetDefaultEncoding(GetCustomEncoding());
    iniciado = 1;
    return 1;
}

static void cerrar_actual(void) {
    if (fv_actual != NULL) {
        FontViewClose(fv_actual);
        fv_actual = NULL;
    }
}

static int tiene_fuente(void) {
    return fv_actual != NULL && fv_actual->sf != NULL;
}

/* Deja la fuente en codificación Unicode para poder crear cualquier glifo por su código. */
static void preparar(SplineFont *sf) {
    fv_actual = FVAppend(_FontViewCreate(sf));
    if (sf->layers[ly_fore].order2)
        SFConvertToOrder3(sf);
    if (fv_actual->map != NULL && fv_actual->map->enc != NULL &&
        !fv_actual->map->enc->is_unicodebmp && !fv_actual->map->enc->is_unicodefull)
        SFReencode(sf, "UnicodeBmp", 0);
}

int motor_new_font(const char *familia) {
    cerrar_actual();
    SplineFont *sf = SplineFontNew();
    if (sf == NULL) return 0;
    if (familia != NULL && *familia) {
        free(sf->familyname); sf->familyname = copy(familia);
        free(sf->fullname);   sf->fullname = copy(familia);
        char *nombre = copy(familia);
        for (char *p = nombre; *p; ++p) if (*p == ' ') *p = '-';
        free(sf->fontname);   sf->fontname = nombre;
    }
    preparar(sf);
    return 1;
}

int motor_open_font(const char *ruta) {
    cerrar_actual();
    SplineFont *sf = LoadSplineFont(ruta, 0);
    if (sf == NULL) return 0;
    if (sf->fv != NULL) {          /* ya tenía vista (no debería pasar) */
        fv_actual = sf->fv;
        return 1;
    }
    preparar(sf);
    return 1;
}

int motor_save_sfd(const char *ruta) {
    if (!tiene_fuente()) return 0;
    char *r = copy(ruta);
    int ok = SFDWrite(r, fv_actual->sf, fv_actual->map, fv_actual->normal, 0);
    free(r);
    return ok;
}

int motor_generate(const char *ruta) {
    if (!tiene_fuente()) return 0;
    char *r = copy(ruta);
    EncMap *map = fv_actual->normal == NULL ? fv_actual->map : fv_actual->normal;
    int ok = GenerateScript(fv_actual->sf, r, "", -1, -1, NULL, NULL, map, NULL, ly_fore);
    free(r);
    return ok;
}

int motor_font_metrics(double *ascenso, double *descenso) {
    if (!tiene_fuente()) return 0;
    *ascenso = fv_actual->sf->ascent;
    *descenso = fv_actual->sf->descent;
    return 1;
}

const char *motor_family_name(void) {
    if (!tiene_fuente()) return NULL;
    return fv_actual->sf->familyname;
}

static int tiene_contenido(SplineChar *sc) {
    if (sc->widthset) return 1;
    for (int ly = 0; ly < sc->layer_cnt; ++ly)
        if (sc->layers[ly].splines != NULL || sc->layers[ly].refs != NULL || sc->layers[ly].images != NULL)
            return 1;
    return 0;
}

/* Claves de los glifos que tienen contenido (ver buscar). */
int motor_glyph_list(int *salida, int max) {
    if (!tiene_fuente()) return 0;
    SplineFont *sf = fv_actual->sf;
    int n = 0;
    for (int gid = 0; gid < sf->glyphcnt; ++gid) {
        SplineChar *sc = sf->glyphs[gid];
        if (sc == NULL || !tiene_contenido(sc)) continue;
        if (n < max) salida[n] = sc->unicodeenc >= 0 ? sc->unicodeenc : -(gid + 2);
        ++n;
    }
    return n;
}

const char *motor_glyph_name(int clave) {
    SplineChar *sc = buscar(clave, 0);
    return sc == NULL ? NULL : sc->name;
}

static int serializar(SplineSet *lista, double *salida, int max, double ancho);

/*
 * Clave de un glifo: su código Unicode, o -(gid + 2) si no tiene código
 * (por ejemplo one.peq o dieresis.alt). -1 queda libre para la capa Guía.
 */
static SplineChar *buscar(int unicode, int crear) {
    if (!tiene_fuente()) return NULL;
    SplineFont *sf = fv_actual->sf;
    if (unicode <= -2) {
        int gid = -unicode - 2;
        return gid < sf->glyphcnt ? sf->glyphs[gid] : NULL;
    }
    SplineChar *sc = SFGetChar(sf, unicode, NULL);
    if (sc != NULL || !crear) return sc;
    int enc = SFFindSlot(sf, fv_actual->map, unicode, NULL);
    if (enc == -1) return NULL;
    return SFMakeChar(sf, fv_actual->map, enc);
}

static int contar_puntos(SplineSet *ss, int *cerrado) {
    int n = 0;
    SplinePoint *sp = ss->first;
    *cerrado = 0;
    while (sp != NULL) {
        ++n;
        if (sp->next == NULL) break;
        sp = sp->next->to;
        if (sp == ss->first) { *cerrado = 1; break; }
    }
    return n;
}

/* Devuelve la cantidad de doubles necesaria. Si cabe en salida, la llena. -1 si no hay glifo. */
static SplineSet **capa_de(SplineChar *sc, int capa) {
    if (capa == ly_grid) return &fv_actual->sf->grid.splines;
    if (sc == NULL || capa < 0 || capa >= sc->layer_cnt) return NULL;
    return &sc->layers[capa].splines;
}

int motor_get_layer(int unicode, int capa, double *salida, int max) {
    if (!tiene_fuente()) return -1;
    if (capa == ly_grid) return serializar(fv_actual->sf->grid.splines, salida, max, 0);
    SplineChar *sc = buscar(unicode, 0);
    if (sc == NULL) {
        /* glifo vacío: solo el ancho por defecto */
        if (max >= 2) {
            SplineFont *sf = fv_actual->sf;
            salida[0] = sf->ascent + sf->descent;
            salida[1] = 0;
        }
        return 2;
    }
    SplineSet **cabeza = capa_de(sc, capa);
    return serializar(cabeza ? *cabeza : NULL, salida, max, sc->width);
}

int motor_get_glyph(int unicode, double *salida, int max) {
    return motor_get_layer(unicode, ly_fore, salida, max);
}

int motor_set_layer(int unicode, int capa, const double *datos, int n) {
    if (n < 2 || !tiene_fuente()) return 0;
    SplineChar *sc = capa == ly_grid ? NULL : buscar(unicode, 1);
    if (capa != ly_grid && sc == NULL) return 0;
    SplineSet **destino = capa_de(sc, capa);
    if (destino == NULL) return 0;

    /* Validar el formato antes de tocar nada */
    int ncont = (int) datos[1], i = 2;
    for (int c = 0; c < ncont; ++c) {
        if (i + 2 > n) return 0;
        int np = (int) datos[i];
        if (np < 1) return 0;
        i += 2 + VALORES_POR_PUNTO * np;
        if (i > n) return 0;
    }

    SplineSet *cabeza = NULL, *ultimo = NULL;
    i = 2;
    for (int c = 0; c < ncont; ++c) {
        int np = (int) datos[i++];
        int cerrado = datos[i++] != 0;
        SplineSet *ss = calloc(1, sizeof(SplineSet));
        SplinePoint *previo = NULL;
        for (int k = 0; k < np; ++k, i += VALORES_POR_PUNTO) {
            const double *v = datos + i;
            SplinePoint *sp = SplinePointCreate(v[0], v[1]);
            sp->prevcp.x = v[2]; sp->prevcp.y = v[3];
            sp->nextcp.x = v[4]; sp->nextcp.y = v[5];
            sp->noprevcp = (v[2] == v[0] && v[3] == v[1]);
            sp->nonextcp = (v[4] == v[0] && v[5] == v[1]);
            int tipo = (int) v[6];
            sp->pointtype = (tipo >= pt_curve && tipo <= pt_hvcurve) ? tipo : pt_corner;
            sp->selected = v[7] != 0;
            if (previo == NULL) ss->first = sp;
            else SplineMake3(previo, sp);
            previo = sp;
        }
        if (cerrado && np > 1) {
            SplineMake3(previo, ss->first);
            ss->last = ss->first;
        } else {
            ss->last = previo;
        }
        if (ultimo == NULL) cabeza = ss; else ultimo->next = ss;
        ultimo = ss;
    }

    SplinePointListsFree(*destino);
    *destino = cabeza;
    if (sc != NULL) {
        if (capa == ly_fore) { sc->width = (int16_t) datos[0]; sc->widthset = 1; }
        SCCharChangedUpdate(sc, capa);
    } else {
        fv_actual->sf->changed = 1;
    }
    return 1;
}

int motor_set_glyph(int unicode, const double *datos, int n) {
    return motor_set_layer(unicode, ly_fore, datos, n);
}

/* Serializa una lista de contornos (sin el ancho) al formato del puente. */
static int serializar(SplineSet *lista, double *salida, int max, double ancho) {
    int total = 2, ncont = 0;
    for (SplineSet *ss = lista; ss != NULL; ss = ss->next) {
        int cerrado;
        total += 2 + VALORES_POR_PUNTO * contar_puntos(ss, &cerrado);
        ++ncont;
    }
    if (total > max) return total;
    int i = 0;
    salida[i++] = ancho;
    salida[i++] = ncont;
    for (SplineSet *ss = lista; ss != NULL; ss = ss->next) {
        int cerrado;
        int n = contar_puntos(ss, &cerrado);
        salida[i++] = n;
        salida[i++] = cerrado;
        SplinePoint *sp = ss->first;
        for (int k = 0; k < n; ++k) {
            salida[i++] = sp->me.x;
            salida[i++] = sp->me.y;
            salida[i++] = sp->noprevcp ? sp->me.x : sp->prevcp.x;
            salida[i++] = sp->noprevcp ? sp->me.y : sp->prevcp.y;
            salida[i++] = sp->nonextcp ? sp->me.x : sp->nextcp.x;
            salida[i++] = sp->nonextcp ? sp->me.y : sp->nextcp.y;
            salida[i++] = sp->pointtype;
            salida[i++] = sp->selected;
            if (sp->next != NULL) sp = sp->next->to;
        }
    }
    return total;
}

/*
 * Convierte un trazo a mano alzada (polilínea x0,y0,x1,y1,...) en curvas Bézier
 * (ver ajuste.c). 'tolerancia' en unidades de la fuente. El resultado se lee con
 * motor_fit_result en el formato del puente: [0, 1, nPuntos, cerrado, puntos...].
 */
static double *trazo_datos = NULL;
static int trazo_n = 0;

int motor_fit_stroke(const double *xy, int npuntos, int cerrado, double tolerancia) {
    free(trazo_datos); trazo_datos = NULL; trazo_n = 0;
    AjustePunto *p;
    int n = ajustar_trazo(xy, npuntos, cerrado, tolerancia, &p);
    if (n < 1) return 0;
    if (n < 3) cerrado = 0;
    trazo_n = 4 + VALORES_POR_PUNTO * n;
    trazo_datos = malloc(sizeof(double) * trazo_n);
    double *o = trazo_datos;
    *o++ = 0; *o++ = 1; *o++ = n; *o++ = cerrado;
    for (int k = 0; k < n; ++k) {
        *o++ = p[k].x; *o++ = p[k].y;
        if (!cerrado && k == 0) { *o++ = p[k].x; *o++ = p[k].y; } else { *o++ = p[k].px; *o++ = p[k].py; }
        if (!cerrado && k == n - 1) { *o++ = p[k].x; *o++ = p[k].y; } else { *o++ = p[k].nx; *o++ = p[k].ny; }
        *o++ = p[k].esquina ? pt_corner : pt_curve;
        *o++ = 0;
    }
    free(p);
    return 1;
}

int motor_fit_result(double *salida, int max) {
    if (trazo_datos == NULL) return -1;
    if (trazo_n <= max) memcpy(salida, trazo_datos, sizeof(double) * trazo_n);
    return trazo_n;
}

/* Las operaciones siguientes trabajan sobre una capa (0 Atrás, 1 Fore, -1 Guía). */
static SplineSet **capa_para_operar(int unicode, int capa, SplineChar **sc_out) {
    if (!tiene_fuente()) return NULL;
    SplineChar *sc = capa == ly_grid ? NULL : buscar(unicode, 0);
    *sc_out = sc;
    if (capa != ly_grid && sc == NULL) return NULL;
    return capa_de(sc, capa);
}

static void avisar_cambio(SplineChar *sc, int capa) {
    if (sc != NULL) SCCharChangedUpdate(sc, capa);
    else fv_actual->sf->changed = 1;
}

/* Corrige la dirección de los contornos (exteriores horarios, huecos antihorarios). */
int motor_correct_direction_layer(int unicode, int capa) {
    SplineChar *sc; SplineSet **h = capa_para_operar(unicode, capa, &sc);
    if (h == NULL) return 0;
    int cambiado = 0;
    *h = SplineSetsCorrect(*h, &cambiado);
    avisar_cambio(sc, capa);
    return 1;
}
int motor_correct_direction(int unicode) { return motor_correct_direction_layer(unicode, ly_fore); }

/* Une contornos que se superponen (Elemento > Quitar solapamientos). */
int motor_remove_overlap_layer(int unicode, int capa) {
    SplineChar *sc; SplineSet **h = capa_para_operar(unicode, capa, &sc);
    if (h == NULL || *h == NULL || sc == NULL) return 0;
    *h = SplineSetRemoveOverlap(sc, *h, over_remove);
    avisar_cambio(sc, capa);
    return 1;
}
int motor_remove_overlap(int unicode) { return motor_remove_overlap_layer(unicode, ly_fore); }

/* Edición > Combinar: quita los puntos seleccionados sin abrir el contorno, conservando la forma. */
int motor_merge_selected(int unicode, int capa) {
    SplineChar *sc; SplineSet **h = capa_para_operar(unicode, capa, &sc);
    if (h == NULL || sc == NULL) return 0;
    SplineCharMerge(sc, h, 1);
    avisar_cambio(sc, capa);
    return 1;
}

/* Punto > Curva / Esquina / Tangente / Curva H-V sobre los puntos seleccionados. */
int motor_set_point_type(int unicode, int capa, int tipo) {
    SplineChar *sc; SplineSet **h = capa_para_operar(unicode, capa, &sc);
    if (h == NULL || tipo < pt_curve || tipo > pt_hvcurve) return 0;
    for (SplineSet *ss = *h; ss != NULL; ss = ss->next) {
        SplinePoint *sp = ss->first;
        while (sp != NULL) {
            if (sp->selected) SPChangePointType(sp, tipo);
            if (sp->next == NULL) break;
            sp = sp->next->to;
            if (sp == ss->first) break;
        }
    }
    avisar_cambio(sc, capa);
    return 1;
}

/* Elemento > Añadir extremos. */
int motor_add_extrema(int unicode, int capa) {
    SplineChar *sc; SplineSet **h = capa_para_operar(unicode, capa, &sc);
    if (h == NULL || *h == NULL || sc == NULL) return 0;
    int em = fv_actual->sf->ascent + fv_actual->sf->descent;
    SplineCharAddExtrema(sc, *h, ae_only_good, em);
    avisar_cambio(sc, capa);
    return 1;
}

/* ---- Imágenes de fondo (p. ej. los píxeles originales en la capa Atrás) ---- */

static ImageList *imagenes_de(int clave, int capa) {
    SplineChar *sc = buscar(clave, 0);
    if (sc == NULL || capa < 0 || capa >= sc->layer_cnt) return NULL;
    return sc->layers[capa].images;
}

static struct _GImage *base_de(GImage *img) {
    if (img == NULL) return NULL;
    return img->list_len == 0 ? img->u.image : img->u.images[0];
}

/* Por imagen: xoff, yoff (esquina superior izquierda), xscale, yscale, ancho, alto. Devuelve la cantidad. */
int motor_image_info(int clave, int capa, double *salida, int max) {
    int n = 0;
    for (ImageList *il = imagenes_de(clave, capa); il != NULL; il = il->next) {
        struct _GImage *b = base_de(il->image);
        if (b == NULL) continue;
        if (6 * (n + 1) <= max) {
            double *o = salida + 6 * n;
            o[0] = il->xoff; o[1] = il->yoff; o[2] = il->xscale; o[3] = il->yscale;
            o[4] = b->width; o[5] = b->height;
        }
        ++n;
    }
    return n;
}

/* Píxeles ARGB de la imagen 'indice'. Devuelve ancho*alto, o -1. */
int motor_image_pixels(int clave, int capa, int indice, int *salida, int max) {
    int k = 0;
    for (ImageList *il = imagenes_de(clave, capa); il != NULL; il = il->next) {
        struct _GImage *b = base_de(il->image);
        if (b == NULL) continue;
        if (k++ != indice) continue;
        int w = b->width, h = b->height;
        if (w * h > max) return w * h;
        for (int y = 0; y < h; ++y) {
            const uint8_t *fila = b->data + (size_t) y * b->bytes_per_line;
            for (int x = 0; x < w; ++x) {
                uint32_t c;
                switch (b->image_type) {
                case it_rgba:
                    c = ((const uint32_t *) fila)[x];
                    break;
                case it_true:
                    c = ((const uint32_t *) fila)[x] & 0xffffff;
                    c |= (b->trans != COLOR_UNKNOWN && c == (b->trans & 0xffffff)) ? 0 : 0xff000000u;
                    break;
                case it_index: {
                    int i = fila[x];
                    c = b->clut != NULL ? (b->clut->clut[i] & 0xffffff) : (uint32_t) (i * 0x010101);
                    c |= (b->trans != COLOR_UNKNOWN && (uint32_t) i == b->trans) ? 0 : 0xff000000u;
                    break;
                }
                default: { /* it_mono: 1 bit por píxel, el más significativo primero */
                    int bit = (fila[x >> 3] >> (7 - (x & 7))) & 1;
                    if (b->clut != NULL) c = b->clut->clut[bit] & 0xffffff;
                    else c = bit ? 0xffffff : 0;
                    c |= (b->trans != COLOR_UNKNOWN && (uint32_t) bit == b->trans) ? 0 : 0xff000000u;
                }
                }
                salida[y * w + x] = (int) c;
            }
        }
        return w * h;
    }
    return -1;
}
