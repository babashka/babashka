#!/usr/bin/env bash
# Spike helper: uberjar + native-image + ffi test suite, logged.
set -eo pipefail
cd "$(dirname "$0")"
log="$1"
{ script/uberjar && script/compile; } > "$log" 2>&1
lein do clean, test babashka.ffi-test >> "$log" 2>&1
tail -3 "$log"
