#!/usr/bin/env bash
# Build bb with the real specs namespace and a patched spec.alpha jar.
# Usage: spec-exp-run.sh <label> <variant-dir>
set -eo pipefail
LABEL="${1:?label}"
VARIANT="${2:?variant dir}"
W=/Users/borkdude/dev/babashka/.claude/worktrees/spec-exp
P=/Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs
bash "$P/spec-exp-jar.sh" 1.12.5 "$VARIANT" | tail -3
rm -f "$W/feature-tools-deps/clojure/tools/deps/specs.clj"
rm -f "$W/feature-tools-deps/clojure/spec/gen/alpha.clj"
grep -q dynaload-stub "$W/project.clj" || sed -i '' 's#\[org.clojure/clojure "1.12.5"\]#[org.clojure/clojure "1.12.5" :exclusions [org.clojure/spec.alpha]]\n                 [org.clojure/spec.alpha "0.5.238-dynaload-stub"]#' "$W/project.clj"
grep -q dynaload-stub "$W/project.clj"
bash "$P/spec-exp-build.sh" "$LABEL"
