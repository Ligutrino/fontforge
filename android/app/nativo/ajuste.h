#ifndef FFGLIFOS_AJUSTE_H
#define FFGLIFOS_AJUSTE_H
typedef struct { double x, y, px, py, nx, ny; int esquina; } AjustePunto;
/* Devuelve la cantidad de puntos; *resultado se libera con free(). */
int ajustar_trazo(const double *xy, int n, int cerrado, double tol, AjustePunto **resultado);
#endif
