#!/usr/bin/env bash
# Build with the Namespace substitution, the real specs namespace and the
# upstream spec.alpha jar. Usage: spec-exp-sub.sh <label>
set -eo pipefail
LABEL="${1:?label}"
VARIANT="${2:-}"
WITH_RT="${3:-}"
W=/Users/borkdude/dev/babashka/.claude/worktrees/spec-exp
P=/Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs
cd "$W"
git checkout -- project.clj script/compile src/babashka/main.clj
rm -f src-java/babashka/impl/NamespaceFeature.java
sed -i '' '/features=babashka.impl.NamespaceFeature/d' script/compile
if [ -n "$VARIANT" ]; then
  bash "$P/spec-exp-jar.sh" 1.12.5 "$VARIANT" | tail -1
  sed -i '' 's#\[org.clojure/clojure "1.12.5"\]#[org.clojure/clojure "1.12.5" :exclusions [org.clojure/spec.alpha]]\n                 [org.clojure/spec.alpha "0.5.238-dynaload-stub"]#' project.clj
fi
grep -c dynaload-stub project.clj || true
rm -f feature-tools-deps/clojure/tools/deps/specs.clj
rm -f feature-tools-deps/clojure/spec/gen/alpha.clj
mkdir -p src-java/babashka/impl
cp "$P/Target_clojure_lang_Namespace.java" src-java/babashka/impl/
rm -f src-java/babashka/impl/Target_clojure_lang_RT.java src-java/babashka/impl/Target_clojure_core_require.java
if [ -n "${WITH_RT:-}" ]; then
  cp "$P/Target_clojure_lang_RT.java" src-java/babashka/impl/
fi
if [ "${WITH_RT:-}" = "require" ]; then
  cp "$P/Target_clojure_core_require.java" src-java/babashka/impl/
fi
bash "$P/spec-exp-build.sh" "$LABEL"
