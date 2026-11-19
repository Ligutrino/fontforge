#ifndef FFGLIFOS_MOTOR_H
#define FFGLIFOS_MOTOR_H
int motor_init(const char *home);
int motor_new_font(const char *familia);
int motor_open_font(const char *ruta);
int motor_save_sfd(const char *ruta);
int motor_generate(const char *ruta);
int motor_font_metrics(double *ascenso, double *descenso);
const char *motor_family_name(void);
int motor_glyph_list(int *salida, int max);
int motor_get_glyph(int unicode, double *salida, int max);
int motor_set_glyph(int unicode, const double *datos, int n);
int motor_fit_stroke(const double *xy, int npuntos, int cerrado, double tolerancia);
int motor_fit_result(double *salida, int max);
int motor_correct_direction(int unicode);
int motor_remove_overlap(int unicode);
int motor_get_layer(int unicode, int capa, double *salida, int max);
int motor_set_layer(int unicode, int capa, const double *datos, int n);
int motor_correct_direction_layer(int unicode, int capa);
int motor_remove_overlap_layer(int unicode, int capa);
int motor_merge_selected(int unicode, int capa);
int motor_set_point_type(int unicode, int capa, int tipo);
int motor_add_extrema(int unicode, int capa);
const char *motor_glyph_name(int clave);
int motor_image_info(int clave, int capa, double *salida, int max);
int motor_image_pixels(int clave, int capa, int indice, int *salida, int max);
#endif
