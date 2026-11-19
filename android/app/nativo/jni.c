/* Envoltura JNI de motor.c para la clase Kotlin app.ffglifos.Motor. GPLv3+. */
#include <jni.h>
#include <stdlib.h>
#include "motor.h"

#define F(nombre) Java_app_ffglifos_Motor_##nombre

static int con_texto(JNIEnv *env, jstring s, int (*fn)(const char *)) {
    if (s == NULL) return fn(NULL);
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    int r = fn(c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

JNIEXPORT jboolean JNICALL F(init)(JNIEnv *env, jclass cls, jstring home) {
    return con_texto(env, home, motor_init) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(nuevaFuente)(JNIEnv *env, jclass cls, jstring familia) {
    return con_texto(env, familia, motor_new_font) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(abrir)(JNIEnv *env, jclass cls, jstring ruta) {
    return con_texto(env, ruta, motor_open_font) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(guardarSfd)(JNIEnv *env, jclass cls, jstring ruta) {
    return con_texto(env, ruta, motor_save_sfd) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(exportar)(JNIEnv *env, jclass cls, jstring ruta) {
    return con_texto(env, ruta, motor_generate) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jdoubleArray JNICALL F(metricas)(JNIEnv *env, jclass cls) {
    double v[2];
    if (!motor_font_metrics(&v[0], &v[1])) return NULL;
    jdoubleArray a = (*env)->NewDoubleArray(env, 2);
    (*env)->SetDoubleArrayRegion(env, a, 0, 2, v);
    return a;
}
JNIEXPORT jstring JNICALL F(familia)(JNIEnv *env, jclass cls) {
    const char *f = motor_family_name();
    return f == NULL ? NULL : (*env)->NewStringUTF(env, f);
}
JNIEXPORT jintArray JNICALL F(listaGlifos)(JNIEnv *env, jclass cls) {
    int n = motor_glyph_list(NULL, 0);
    int *buf = malloc(sizeof(int) * (n > 0 ? n : 1));
    motor_glyph_list(buf, n);
    jintArray a = (*env)->NewIntArray(env, n);
    (*env)->SetIntArrayRegion(env, a, 0, n, buf);
    free(buf);
    return a;
}
JNIEXPORT jdoubleArray JNICALL F(leerGlifo)(JNIEnv *env, jclass cls, jint unicode) {
    int n = motor_get_glyph(unicode, NULL, 0);
    if (n < 0) return NULL;
    double *buf = malloc(sizeof(double) * n);
    motor_get_glyph(unicode, buf, n);
    jdoubleArray a = (*env)->NewDoubleArray(env, n);
    (*env)->SetDoubleArrayRegion(env, a, 0, n, buf);
    free(buf);
    return a;
}
JNIEXPORT jboolean JNICALL F(escribirGlifo)(JNIEnv *env, jclass cls, jint unicode, jdoubleArray datos) {
    if (datos == NULL) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, datos);
    jdouble *d = (*env)->GetDoubleArrayElements(env, datos, NULL);
    int ok = motor_set_glyph(unicode, d, n);
    (*env)->ReleaseDoubleArrayElements(env, datos, d, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL F(ajustarTrazo)(JNIEnv *env, jclass cls, jdoubleArray xy, jboolean cerrado, jdouble tol) {
    if (xy == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, xy);
    jdouble *d = (*env)->GetDoubleArrayElements(env, xy, NULL);
    int ok = motor_fit_stroke(d, n / 2, cerrado == JNI_TRUE, tol);
    (*env)->ReleaseDoubleArrayElements(env, xy, d, JNI_ABORT);
    if (!ok) return NULL;
    int m = motor_fit_result(NULL, 0);
    double *buf = malloc(sizeof(double) * m);
    motor_fit_result(buf, m);
    jdoubleArray a = (*env)->NewDoubleArray(env, m);
    (*env)->SetDoubleArrayRegion(env, a, 0, m, buf);
    free(buf);
    return a;
}
JNIEXPORT jboolean JNICALL F(corregirDireccion)(JNIEnv *env, jclass cls, jint unicode) {
    return motor_correct_direction(unicode) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(quitarSolapamientos)(JNIEnv *env, jclass cls, jint unicode) {
    return motor_remove_overlap(unicode) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL F(leerCapa)(JNIEnv *env, jclass cls, jint unicode, jint capa) {
    int n = motor_get_layer(unicode, capa, NULL, 0);
    if (n < 0) return NULL;
    double *buf = malloc(sizeof(double) * n);
    motor_get_layer(unicode, capa, buf, n);
    jdoubleArray a = (*env)->NewDoubleArray(env, n);
    (*env)->SetDoubleArrayRegion(env, a, 0, n, buf);
    free(buf);
    return a;
}
JNIEXPORT jboolean JNICALL F(escribirCapa)(JNIEnv *env, jclass cls, jint unicode, jint capa, jdoubleArray datos) {
    if (datos == NULL) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, datos);
    jdouble *d = (*env)->GetDoubleArrayElements(env, datos, NULL);
    int ok = motor_set_layer(unicode, capa, d, n);
    (*env)->ReleaseDoubleArrayElements(env, datos, d, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(corregirDireccionCapa)(JNIEnv *env, jclass cls, jint u, jint capa) {
    return motor_correct_direction_layer(u, capa) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(quitarSolapamientosCapa)(JNIEnv *env, jclass cls, jint u, jint capa) {
    return motor_remove_overlap_layer(u, capa) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(combinar)(JNIEnv *env, jclass cls, jint u, jint capa) {
    return motor_merge_selected(u, capa) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(cambiarTipo)(JNIEnv *env, jclass cls, jint u, jint capa, jint tipo) {
    return motor_set_point_type(u, capa, tipo) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL F(anadirExtremos)(JNIEnv *env, jclass cls, jint u, jint capa) {
    return motor_add_extrema(u, capa) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL F(nombreGlifo)(JNIEnv *env, jclass cls, jint clave) {
    const char *n = motor_glyph_name(clave);
    return n == NULL ? NULL : (*env)->NewStringUTF(env, n);
}
JNIEXPORT jdoubleArray JNICALL F(imagenes)(JNIEnv *env, jclass cls, jint clave, jint capa) {
    int n = motor_image_info(clave, capa, NULL, 0);
    double *buf = malloc(sizeof(double) * 6 * (n > 0 ? n : 1));
    motor_image_info(clave, capa, buf, 6 * n);
    jdoubleArray a = (*env)->NewDoubleArray(env, 6 * n);
    (*env)->SetDoubleArrayRegion(env, a, 0, 6 * n, buf);
    free(buf);
    return a;
}
JNIEXPORT jintArray JNICALL F(pixelesImagen)(JNIEnv *env, jclass cls, jint clave, jint capa, jint indice) {
    int n = motor_image_pixels(clave, capa, indice, NULL, 0);
    if (n < 0) return NULL;
    int *buf = malloc(sizeof(int) * (n > 0 ? n : 1));
    motor_image_pixels(clave, capa, indice, buf, n);
    jintArray a = (*env)->NewIntArray(env, n);
    (*env)->SetIntArrayRegion(env, a, 0, n, buf);
    free(buf);
    return a;
}

/* ---- Métricas y kerning ---- */
JNIEXPORT jint JNICALL F(glifoPorNombre)(JNIEnv *env, jclass cls, jstring nombre) {
    if (nombre == NULL) return 0;
    const char *c = (*env)->GetStringUTFChars(env, nombre, NULL);
    int clave = 0;
    int ok = motor_glyph_by_name(c, &clave);
    (*env)->ReleaseStringUTFChars(env, nombre, c);
    return ok ? clave : -1;
}
JNIEXPORT jboolean JNICALL F(moverImagenes)(JNIEnv *env, jclass cls, jint clave, jdouble dx) {
    return motor_shift_images(clave, dx) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jintArray JNICALL F(kern)(JNIEnv *env, jclass cls, jint izq, jint der) {
    int v[2];
    v[0] = motor_get_kern(izq, der, &v[1]);
    jintArray a = (*env)->NewIntArray(env, 2);
    (*env)->SetIntArrayRegion(env, a, 0, 2, v);
    return a;
}
JNIEXPORT jboolean JNICALL F(fijarKern)(JNIEnv *env, jclass cls, jint izq, jint der, jint valor) {
    return motor_set_kern(izq, der, valor) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jintArray JNICALL F(paresKern)(JNIEnv *env, jclass cls) {
    int n = motor_kern_pairs(NULL, 0);
    int *buf = malloc(sizeof(int) * 3 * (n > 0 ? n : 1));
    motor_kern_pairs(buf, 3 * n);
    jintArray a = (*env)->NewIntArray(env, 3 * n);
    (*env)->SetIntArrayRegion(env, a, 0, 3 * n, buf);
    free(buf);
    return a;
}
JNIEXPORT jint JNICALL F(clasesKern)(JNIEnv *env, jclass cls) {
    return motor_kern_class_count();
}
