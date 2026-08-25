#include <stdint.h>

/* A Windows DLL exports nothing unless it says so. */
#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

typedef struct { int32_t x, y; } P2;         /* 8 bytes: one integer register */
typedef struct { double x, y, z; } V3;       /* 24 bytes: HFA on arm64, memory on SysV */
typedef struct { int64_t a, b, c, d; } Big;  /* 32 bytes: hidden pointer, x8 or rdi */
typedef struct { P2 lo; P2 hi; } Rect;       /* nested, 16 bytes: two registers */

EXPORT P2 p2_add(P2 a, P2 b) { P2 r = { a.x + b.x, a.y + b.y }; return r; }

EXPORT V3 v3_scale(V3 v, double k) { V3 r = { v.x * k, v.y * k, v.z * k }; return r; }

EXPORT Big big_make(int64_t s) { Big r = { s, s + 1, s + 2, s + 3 }; return r; }

EXPORT int64_t big_sum(Big b) { return b.a + b.b + b.c + b.d; }

EXPORT Rect rect_grow(Rect r, int32_t d) {
  Rect o = { { r.lo.x - d, r.lo.y - d }, { r.hi.x + d, r.hi.y + d } };
  return o;
}
