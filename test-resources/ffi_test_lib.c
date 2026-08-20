/* Test library for babashka.ffi. Compiled on demand by babashka.ffi-test.
 *
 * Every integer is int64_t so the C side matches the jlong carrier exactly
 * on every platform (C long is 32-bit on Windows).
 *
 * The mix_* echo functions multiply each argument by 10^position, so a
 * wrong argument permutation after class-sorting produces a wrong sum. */

#include <stdint.h>
#include <stdarg.h>
#include <string.h>

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

/* -- argument order over mixed register classes -- */

EXPORT double mix_dj(double a, int64_t b) { return a + b * 10; }
EXPORT double mix_jd(int64_t a, double b) { return a + b * 10; }
EXPORT double mix_djdj(double a, int64_t b, double c, int64_t d) {
    return a + b * 10 + c * 100 + d * 1000;
}
EXPORT double mix_jdjd(int64_t a, double b, int64_t c, double d) {
    return a + b * 10 + c * 100 + d * 1000;
}
EXPORT double mix_fjf(float a, int64_t b, float c) { return a + b * 10 + c * 100; }
EXPORT double mix_jfd(int64_t a, float b, double c) { return a + b * 10 + c * 100; }

/* -- arity edges (pure integer) -- */

EXPORT int64_t arity7(int64_t a, int64_t b, int64_t c, int64_t d, int64_t e,
                      int64_t f, int64_t g) {
    return a + b * 10 + c * 100 + d * 1000 + e * 10000 + f * 100000 + g * 1000000;
}
EXPORT int64_t arity10(int64_t a, int64_t b, int64_t c, int64_t d, int64_t e,
                       int64_t f, int64_t g, int64_t h, int64_t i, int64_t j) {
    return a + b * 10 + c * 100 + d * 1000 + e * 10000 + f * 100000 +
           g * 1000000 + h * 10000000 + i * 100000000 + j * 1000000000;
}

/* -- return narrowing: high register bits must be masked by the caller -- */

EXPORT int32_t ret_int_neg(void) { return -1; }
EXPORT uint32_t ret_uint_max(void) { return 0xFFFFFFFFu; }
EXPORT int8_t ret_int8_neg(void) { return -1; }
EXPORT uint8_t ret_uint8_max(void) { return 255; }
EXPORT int16_t ret_int16_neg(void) { return -2; }
EXPORT uint16_t ret_uint16_max(void) { return 65535; }
EXPORT float ret_float(void) { return 1.5f; }

/* -- varargs: sum the n variadic int64 args by 10^position -- */

EXPORT int64_t va_sum(int64_t n, ...) {
    va_list ap;
    va_start(ap, n);
    int64_t s = 0, m = 1;
    for (int64_t i = 0; i < n; i++) {
        s += va_arg(ap, int64_t) * m;
        m *= 10;
    }
    va_end(ap);
    return s;
}

/* one variadic int64 and one variadic double */
EXPORT int64_t va_ld(int64_t fixed, ...) {
    va_list ap;
    va_start(ap, fixed);
    int64_t l = va_arg(ap, int64_t);
    double d = va_arg(ap, double);
    va_end(ap);
    return fixed + l * 10 + (int64_t)(d * 4) * 100;
}

/* -- strings -- */

EXPORT int64_t utf8_bytes(const char *s) { return (int64_t)strlen(s); }

/* -- callbacks -- */

EXPORT int64_t cb_apply_jj(int64_t (*f)(int64_t, int64_t), int64_t a, int64_t b) {
    return f(a, b);
}
EXPORT double cb_apply_jd(double (*f)(int64_t, double), int64_t a, double b) {
    return f(a, b);
}
/* callback with DECLARED order double-then-int: exercises upcall
   un-permutation after class sorting */
EXPORT double cb_apply_dj(double (*f)(double, int64_t), double a, int64_t b) {
    return f(a, b);
}
