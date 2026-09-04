#!/usr/bin/env bash
# Build B: real specs as in A, with spec.alpha replaced by a jar whose
# dynaload is stubbed, so no runtime require or resolve is reachable.
set -eo pipefail
W=/Users/borkdude/dev/babashka/.claude/worktrees/spec-exp
P=/Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs
rm -f "$W/feature-tools-deps/clojure/tools/deps/specs.clj"
rm -f "$W/feature-tools-deps/clojure/spec/gen/alpha.clj"
grep -q dynaload-stub "$W/project.clj"
bash "$P/spec-exp-build.sh" B
