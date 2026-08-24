#include <stdint.h>

typedef struct { int32_t x, y; } P2;         /* 8 bytes: one integer register */
typedef struct { double x, y, z; } V3;       /* 24 bytes: HFA on arm64, memory on SysV */
typedef struct { int64_t a, b, c, d; } Big;  /* 32 bytes: hidden pointer, x8 or rdi */
typedef struct { P2 lo; P2 hi; } Rect;       /* nested, 16 bytes: two registers */

P2 p2_add(P2 a, P2 b) { P2 r = { a.x + b.x, a.y + b.y }; return r; }

V3 v3_scale(V3 v, double k) { V3 r = { v.x * k, v.y * k, v.z * k }; return r; }

Big big_make(int64_t s) { Big r = { s, s + 1, s + 2, s + 3 }; return r; }

int64_t big_sum(Big b) { return b.a + b.b + b.c + b.d; }

Rect rect_grow(Rect r, int32_t d) {
  Rect o = { { r.lo.x - d, r.lo.y - d }, { r.hi.x + d, r.hi.y + d } };
  return o;
}
