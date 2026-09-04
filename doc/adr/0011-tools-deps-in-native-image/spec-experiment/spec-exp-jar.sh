#!/usr/bin/env bash
# Build a spec.alpha 0.5.238 jar with dynaload stubbed, AOT-compiled the way
# spec.alpha's own build does it (clojure.lang.Compile, no core.specs.alpha on
# the classpath), and install it in ~/.m2 as version 0.5.238-dynaload-stub.
set -eo pipefail
CLJ_VERSION="${1:?clojure version}"
P=/Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs
S=/private/tmp/claude-501/-Users-borkdude-dev-babashka/c243b13f-6132-4416-881f-356589f6723c/scratchpad/specalpha
M2=$HOME/.m2/repository/org/clojure
UP=$M2/spec.alpha/0.5.238/spec.alpha-0.5.238.jar
CLJ=$M2/clojure/$CLJ_VERSION/clojure-$CLJ_VERSION.jar
V=0.5.238-dynaload-stub

rm -rf "$S"; mkdir -p "$S/src" "$S/classes"
unzip -q "$UP" -d "$S/src" 'clojure/*.clj'
cp "$P/spec-gen-alpha-stub.clj" "$S/src/clojure/spec/gen/alpha.clj"
grep -c "dynaload stubbed" "$S/src/clojure/spec/gen/alpha.clj"

CORE_SPECS=$(ls "$M2"/core.specs.alpha/*/*.jar | sort | tail -1)
java -cp "$CLJ:$S/src:$CORE_SPECS" -Dclojure.spec.skip-macros=true \
  -Dclojure.compile.path="$S/classes" clojure.lang.Compile \
  clojure.spec.gen.alpha clojure.spec.alpha clojure.spec.test.alpha
find "$S/classes" -name '*.class' | wc -l

# Clojure loads the .clj when it is newer than the __init.class, so the
# classes must carry the later timestamp.
cd "$S/classes" && cp -Rp "$S/src/clojure" . && find . -name '*.class' -exec touch {} + \
  && jar cf "$S/spec.alpha-$V.jar" clojure
mkdir -p "$M2/spec.alpha/$V"
cp "$S/spec.alpha-$V.jar" "$M2/spec.alpha/$V/"
sed "s#<version>0.5.238</version>#<version>$V</version>#" \
  "$M2/spec.alpha/0.5.238/spec.alpha-0.5.238.pom" > "$M2/spec.alpha/$V/spec.alpha-$V.pom"
grep -c "$V" "$M2/spec.alpha/$V/spec.alpha-$V.pom"
ls -l "$M2/spec.alpha/$V/"
