#!/usr/bin/env bash
# Build with the NamespaceFeature (pruned mapping tables), the real specs
# namespace, the upstream spec.alpha jar and a run-time resolve probe.
# Usage: spec-exp-feature.sh <label>
set -eo pipefail
LABEL="${1:?label}"
W=/Users/borkdude/dev/babashka/.claude/worktrees/spec-exp
P=/Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs
cd "$W"
git checkout -- project.clj script/compile src/babashka/main.clj
rm -f feature-tools-deps/clojure/tools/deps/specs.clj
rm -f feature-tools-deps/clojure/spec/gen/alpha.clj
rm -f src-java/babashka/impl/Target_clojure_lang_Namespace.java
mkdir -p src-java/babashka/impl
cp "$P/NamespaceFeature.java" src-java/babashka/impl/
sed -i '' 's|      "--no-fallback"|      "--no-fallback"\n      "--features=babashka.impl.NamespaceFeature"|' script/compile
grep -c "features=babashka.impl.NamespaceFeature" script/compile
bb "$P/add-probe.clj" src/babashka/main.clj
bash "$P/spec-exp-build.sh" "$LABEL"
