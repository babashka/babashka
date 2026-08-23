#!/usr/bin/env bash

# Pre-build guard: link a probe on this machine and check that it stays
# within the glibc floor. Catches a bumped build image in seconds. The
# probe only sees its own symbols, so the authoritative post-build check
# is script/verify_link.

set -euo pipefail

. "$(dirname "$0")/glibc_floor.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/probe.c" <<'EOF'
#include <string.h>
#include <fcntl.h>
#include <pthread.h>
#include <dlfcn.h>

/* symbols with known glibc version bumps: memcpy 2.14, fcntl 2.28,
   pthread_create and dlopen 2.34; __libc_start_main (2.34) comes free */
static void *run(void *a) { return a; }

int main(int argc, char **argv) {
    char buf[8];
    memcpy(buf, argv[0], 1);
    fcntl(0, F_GETFL);
    pthread_t t;
    pthread_create(&t, 0, run, 0);
    dlopen(0, RTLD_LAZY);
    return buf[0] + argc;
}
EOF
cc "$tmp/probe.c" -o "$tmp/probe" -lpthread -ldl

max_found="$(max_glibc_symbol "$tmp/probe")"
echo "probe max glibc symbol version: ${max_found:-none} (floor $glibc_floor)"
if ! printf '%s\n%s' "$max_found" "$glibc_floor" | sort -C -V; then
    echo "check_glibc: linking on this machine exceeds the glibc floor $glibc_floor" >&2
    exit 1
fi
