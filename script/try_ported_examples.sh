#!/usr/bin/env bash
# Runs b12n-raylib-clj examples against the ported babashka.ffi bindings.
#
#   script/try_ported_examples.sh                  # a sample
#   script/try_ported_examples.sh all [seconds]    # every example
#
# Needs the port generated first: bb script/gen_ffi_metadata.clj is unrelated,
# this one is: bb script/port_raylib_clj.clj
set -uo pipefail
cd "$(dirname "$0")/.."

THEIRS="$HOME/dev/b12n-raylib-clj/src"   # only used to list example names
SECS="${2:-2}"
RUNNER=/tmp/run-example.clj

if [ "${1:-sample}" = "all" ]; then
  EXAMPLES=$(ls "$THEIRS/examples"/*.clj | xargs -n1 basename | sed 's/\.clj$//' | tr '_' '-')
else
  EXAMPLES="random-values basic-shapes bouncing-ball colors-palette dashed-line
            easings-rectangles input-mouse pong window-should-close"
fi

pass=0; fail=0
for ex in $EXAMPLES; do
  printf '%-28s ' "$ex"
  out=$(timeout 40 ./bb -cp port "$RUNNER" "examples.$ex" "$SECS" 2>&1 | grep -v '^INFO')
  if echo "$out" | grep -q "finished"; then
    echo "ok"; pass=$((pass+1))
  else
    reason=$(echo "$out" | grep -E "Message:|needs struct-by-value|Could not locate" | head -1 | cut -c1-90)
    echo "FAIL ${reason:-unknown}"; fail=$((fail+1))
  fi
done
echo
echo "passed: $pass   failed: $fail"
