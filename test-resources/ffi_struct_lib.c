#include <stdint.h>

/* Export the test functions from a Windows DLL. */
#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT
#endif

typedef struct { int32_t x, y; } P2;         /* 8 bytes: one integer register */
typedef struct { double x, y, z; } V3;       /* 24-byte HFA on Arm64 */
typedef struct { int64_t a, b, c, d; } Big;  /* 32 bytes: hidden return pointer */
typedef struct { P2 lo; P2 hi; } Rect;       /* 16-byte nested struct */

EXPORT P2 p2_add(P2 a, P2 b) { P2 r = { a.x + b.x, a.y + b.y }; return r; }

EXPORT V3 v3_scale(V3 v, double k) { V3 r = { v.x * k, v.y * k, v.z * k }; return r; }

EXPORT Big big_make(int64_t s) { Big r = { s, s + 1, s + 2, s + 3 }; return r; }

EXPORT int64_t big_sum(Big b) { return b.a + b.b + b.c + b.d; }

/* Four mixed float arguments: outside the trampoline family, so a native
 * image calls it through libffi. */
EXPORT double mix4(float a, double b, float c, double d) {
  return a + b + c + d;
}

EXPORT Rect rect_grow(Rect r, int32_t d) {
  Rect o = { { r.lo.x - d, r.lo.y - d }, { r.hi.x + d, r.hi.y + d } };
  return o;
}

/* Fixed arrays inside structs. libffi has no array kind; babashka.ffi
 * describes one as a struct of repeated elements, and these functions check
 * that this agrees with the compiler for each ABI class. */

typedef struct { int32_t v[4]; } Quad;                   /* 16 bytes: two integer registers */
typedef struct { char name[32]; int32_t parent; } Bone;  /* 36 bytes: passed in memory */
typedef struct { double m[2][2]; } Mat2;                 /* four doubles: an HFA on Arm64 */
typedef struct { P2 pts[2]; } Pair;                      /* an array of structs */

EXPORT int32_t quad_sum(Quad q) { return q.v[0] + q.v[1] + q.v[2] + q.v[3]; }
EXPORT Quad quad_make(int32_t a) { Quad q = { { a, a + 1, a + 2, a + 3 } }; return q; }
EXPORT int32_t bone_len(Bone b) {
  int32_t i = 0;
  while (b.name[i]) i++;
  return i + b.parent;
}
EXPORT Bone bone_make(int32_t parent) { Bone b = { "spine", parent }; return b; }
EXPORT double mat2_trace(Mat2 m) { return m.m[0][0] + m.m[1][1]; }
EXPORT int32_t pair_sum(Pair p) { return p.pts[0].x + p.pts[0].y + p.pts[1].x + p.pts[1].y; }
