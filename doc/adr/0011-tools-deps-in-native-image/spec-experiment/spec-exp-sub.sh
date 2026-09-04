#!/usr/bin/env bash
# Build with the Namespace substitution, the real specs namespace and the
# upstream spec.alpha jar. Usage: spec-exp-sub.sh <label>
set -eo pipefail
LABEL="${1:?label}"
W=/Users/borkdude/dev/babashka/.claude/worktrees/spec-exp
P=/Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs
cd "$W"
git checkout -- project.clj
grep -c dynaload-stub project.clj || true
rm -f feature-tools-deps/clojure/tools/deps/specs.clj
rm -f feature-tools-deps/clojure/spec/gen/alpha.clj
mkdir -p src-java/babashka/impl
cp "$P/Target_clojure_lang_Namespace.java" src-java/babashka/impl/
bash "$P/spec-exp-build.sh" "$LABEL"
