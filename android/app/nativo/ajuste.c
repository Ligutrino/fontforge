/*
 * Ajuste de un trazo a mano alzada a curvas Bézier cúbicas
 * (algoritmo de Philip J. Schneider, "An Algorithm for Automatically
 * Fitting Digitized Curves", Graphics Gems, 1990), con detección de esquinas.
 * GPLv3+.
 */
#include "ajuste.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct { double x, y; } V;

static V v(double x, double y) { V r = { x, y }; return r; }
static V suma(V a, V b) { return v(a.x + b.x, a.y + b.y); }
static V resta(V a, V b) { return v(a.x - b.x, a.y - b.y); }
static V escala(V a, double s) { return v(a.x * s, a.y * s); }
static double punto(V a, V b) { return a.x * b.x + a.y * b.y; }
static double largo(V a) { return sqrt(punto(a, a)); }
static double dist(V a, V b) { return largo(resta(a, b)); }
static V unit(V a) { double l = largo(a); return l > 1e-12 ? escala(a, 1 / l) : v(0, 0); }

/* Salida: lista dinámica de puntos con manejadores */
typedef struct { AjustePunto *p; int n, cap; } Salida;
static void agregar(Salida *s, AjustePunto q) {
    if (s->n == s->cap) { s->cap = s->cap ? 2 * s->cap : 64; s->p = realloc(s->p, s->cap * sizeof *s->p); }
    s->p[s->n++] = q;
}

static V bezier(const V *c, double t) {
    double u = 1 - t;
    return suma(suma(escala(c[0], u * u * u), escala(c[1], 3 * u * u * t)),
                suma(escala(c[2], 3 * u * t * t), escala(c[3], t * t * t)));
}

static void parametrizar(const V *d, int a, int b, double *u) {
    u[0] = 0;
    for (int i = a + 1; i <= b; ++i) u[i - a] = u[i - a - 1] + dist(d[i], d[i - 1]);
    double tot = u[b - a];
    for (int i = a + 1; i <= b; ++i) u[i - a] = tot > 0 ? u[i - a] / tot : 0;
}

static void generar(const V *d, int a, int b, const double *u, V t1, V t2, V *c) {
    int n = b - a + 1;
    double C[2][2] = { { 0, 0 }, { 0, 0 } }, X[2] = { 0, 0 };
    V p0 = d[a], p3 = d[b];
    for (int i = 0; i < n; ++i) {
        double t = u[i], s = 1 - t;
        double b0 = s * s * s, b1 = 3 * t * s * s, b2 = 3 * t * t * s, b3 = t * t * t;
        V A1 = escala(t1, b1), A2 = escala(t2, b2);
        C[0][0] += punto(A1, A1); C[0][1] += punto(A1, A2); C[1][1] += punto(A2, A2);
        V tmp = resta(d[a + i], suma(suma(escala(p0, b0 + b1), escala(p3, b2 + b3)), v(0, 0)));
        X[0] += punto(A1, tmp); X[1] += punto(A2, tmp);
    }
    C[1][0] = C[0][1];
    double det = C[0][0] * C[1][1] - C[1][0] * C[0][1];
    double a1 = 0, a2 = 0;
    if (fabs(det) > 1e-12) {
        a1 = (X[0] * C[1][1] - X[1] * C[0][1]) / det;
        a2 = (C[0][0] * X[1] - C[1][0] * X[0]) / det;
    }
    double seg = dist(p0, p3), eps = 1e-6 * seg;
    if (a1 < eps || a2 < eps) { a1 = a2 = seg / 3; }   /* heurística de Wu/Barsky */
    c[0] = p0; c[3] = p3;
    c[1] = suma(p0, escala(t1, a1));
    c[2] = suma(p3, escala(t2, a2));
}

static double error_max(const V *d, int a, int b, const V *c, const double *u, int *idx) {
    double m = 0; *idx = (a + b) / 2;
    for (int i = a + 1; i < b; ++i) {
        double e = dist(bezier(c, u[i - a]), d[i]);
        if (e > m) { m = e; *idx = i; }
    }
    return m;
}

static double newton(const V *c, V p, double t) {
    V q = bezier(c, t);
    V d1[3], d2[2];
    for (int i = 0; i < 3; ++i) d1[i] = escala(resta(c[i + 1], c[i]), 3);
    for (int i = 0; i < 2; ++i) d2[i] = escala(resta(d1[i + 1], d1[i]), 2);
    double s = 1 - t;
    V q1 = suma(suma(escala(d1[0], s * s), escala(d1[1], 2 * s * t)), escala(d1[2], t * t));
    V q2 = suma(escala(d2[0], s), escala(d2[1], t));
    double num = punto(resta(q, p), q1);
    double den = punto(q1, q1) + punto(resta(q, p), q2);
    if (fabs(den) < 1e-12) return t;
    double r = t - num / den;
    return r < 0 ? 0 : r > 1 ? 1 : r;
}

static V tangente_en(const V *d, int a, int b, int i, int hacia_adelante, double radio) {
    /* promedio de dirección hacia puntos dentro de 'radio' */
    V acc = v(0, 0);
    if (hacia_adelante) {
        for (int k = i + 1; k <= b; ++k) { acc = suma(acc, unit(resta(d[k], d[i]))); if (dist(d[k], d[i]) > radio) break; }
    } else {
        for (int k = i - 1; k >= a; --k) { acc = suma(acc, unit(resta(d[k], d[i]))); if (dist(d[k], d[i]) > radio) break; }
    }
    return unit(acc);
}

static void ajustar(const V *d, int a, int b, V t1, V t2, double tol, double radio, Salida *s, int esquina_fin) {
    int n = b - a + 1;
    if (n == 2) {
        double l = dist(d[a], d[b]) / 3;
        V c1 = suma(d[a], escala(t1, l)), c2 = suma(d[b], escala(t2, l));
        s->p[s->n - 1].nx = c1.x; s->p[s->n - 1].ny = c1.y;
        AjustePunto q = { d[b].x, d[b].y, c2.x, c2.y, d[b].x, d[b].y, esquina_fin };
        agregar(s, q);
        return;
    }
    double *u = malloc(n * sizeof *u);
    parametrizar(d, a, b, u);
    V c[4]; int idx;
    generar(d, a, b, u, t1, t2, c);
    double err = error_max(d, a, b, c, u, &idx);
    for (int it = 0; err > tol && err < tol * 4 && it < 6; ++it) {
        for (int i = 0; i < n; ++i) u[i] = newton(c, d[a + i], u[i]);
        generar(d, a, b, u, t1, t2, c);
        err = error_max(d, a, b, c, u, &idx);
    }
    free(u);
    if (err <= tol) {
        s->p[s->n - 1].nx = c[1].x; s->p[s->n - 1].ny = c[1].y;
        AjustePunto q = { d[b].x, d[b].y, c[2].x, c[2].y, d[b].x, d[b].y, esquina_fin };
        agregar(s, q);
        return;
    }
    if (idx <= a) idx = a + 1;
    if (idx >= b) idx = b - 1;
    V tc = unit(resta(tangente_en(d, a, b, idx, 0, radio), tangente_en(d, a, b, idx, 1, radio)));
    if (largo(tc) == 0) tc = unit(resta(d[idx - 1], d[idx + 1]));
    ajustar(d, a, idx, t1, tc, tol, radio, s, 0);
    ajustar(d, idx, b, escala(tc, -1), t2, tol, radio, s, esquina_fin);
}

int ajustar_trazo(const double *xy, int n, int cerrado, double tol, AjustePunto **resultado) {
    *resultado = NULL;
    if (n < 2 || tol <= 0) return 0;
    /* 1. quitar puntos muy cercanos */
    V *d = malloc((n + 1) * sizeof *d);
    int m = 0;
    for (int i = 0; i < n; ++i) {
        V p = v(xy[2 * i], xy[2 * i + 1]);
        if (m == 0 || dist(p, d[m - 1]) >= tol * 0.5) d[m++] = p;
    }
    if (m > 1 && dist(v(xy[2 * n - 2], xy[2 * n - 1]), d[m - 1]) > 0 && !cerrado) d[m - 1] = v(xy[2 * n - 2], xy[2 * n - 1]);
    if (cerrado) {
        while (m > 2 && dist(d[m - 1], d[0]) < tol) --m;
        d[m++] = d[0];            /* repetir el inicio para cerrar */
    }
    if (m < 2) { free(d); return 0; }

    /* suavizado ligero (dos pasadas de promedio 1-2-1) para quitar el temblor de la mano */
    for (int pasada = 0; pasada < 2 && m > 4; ++pasada) {
        V *e = malloc(m * sizeof *e);
        for (int i = 0; i < m; ++i) {
            int ia = i - 1, ib = i + 1;
            if (cerrado) { if (ia < 0) ia = m - 2; if (ib > m - 1) ib = 1; }
            if (!cerrado && (i == 0 || i == m - 1)) { e[i] = d[i]; continue; }
            e[i] = escala(suma(suma(d[ia], d[ib]), escala(d[i], 2)), 0.25);
        }
        if (cerrado) e[m - 1] = e[0];
        memcpy(d, e, m * sizeof *e);
        free(e);
    }

    /* 2. detectar esquinas: giro fuerte medido con vecinos a distancia 'radio' */
    double radio = tol * 4;
    char *esq = calloc(m, 1);
    double *giro = calloc(m, sizeof *giro);
    for (int i = 1; i < m - 1; ++i) {
        V atras = tangente_en(d, 0, m - 1, i, 0, radio), adel = tangente_en(d, 0, m - 1, i, 1, radio);
        double cosang = -punto(atras, adel);          /* 1 = recto, -1 = vuelta atrás */
        giro[i] = acos(fmax(-1, fmin(1, cosang)));
    }
    const double UMBRAL = 55.0 * M_PI / 180.0;
    for (int i = 1; i < m - 1; ++i) {
        if (giro[i] < UMBRAL) continue;
        int maximo = 1;
        for (int k = i - 1; k >= 1 && dist(d[k], d[i]) < radio; --k) if (giro[k] > giro[i]) maximo = 0;
        for (int k = i + 1; k < m - 1 && dist(d[k], d[i]) < radio; ++k) if (giro[k] >= giro[i]) maximo = 0;
        if (maximo) esq[i] = 1;
    }
    if (!cerrado) { esq[0] = esq[m - 1] = 1; }

    /* 3. ajustar cada tramo entre esquinas */
    Salida s = { 0 };
    int inicio_esquina = cerrado ? 0 : 1;
    V t_inicio_cerrado = v(0, 0);
    if (cerrado) {
        /* ¿el inicio es esquina? Comprobar con el final del trazo */
        V atras = tangente_en(d, 0, m - 1, m - 1, 0, radio), adel = tangente_en(d, 0, m - 1, 0, 1, radio);
        double g = acos(fmax(-1, fmin(1, -punto(atras, adel))));
        inicio_esquina = g >= UMBRAL;
        t_inicio_cerrado = unit(resta(adel, atras));
    }
    AjustePunto q0 = { d[0].x, d[0].y, d[0].x, d[0].y, d[0].x, d[0].y, inicio_esquina };
    agregar(&s, q0);
    int a = 0;
    for (int b = 1; b < m; ++b) {
        if (!esq[b] && b != m - 1) continue;
        V t1, t2;
        if (a == 0 && cerrado && !inicio_esquina) t1 = t_inicio_cerrado;
        else t1 = tangente_en(d, a, b, a, 1, radio);
        if (b == m - 1 && cerrado && !inicio_esquina) t2 = escala(t_inicio_cerrado, -1);
        else t2 = tangente_en(d, a, b, b, 0, radio);
        ajustar(d, a, b, t1, t2, tol, radio, &s, b == m - 1 ? inicio_esquina : 1);
        a = b;
    }
    if (cerrado && s.n > 1) {
        /* el último punto es el mismo que el primero: pasar su manejador previo al primero */
        s.p[0].px = s.p[s.n - 1].px; s.p[0].py = s.p[s.n - 1].py;
        s.n--;
    }
    free(d); free(esq); free(giro);
    *resultado = s.p;
    return s.n;
}
