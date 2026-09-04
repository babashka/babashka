#!/usr/bin/env bash
# Build the experiment worktree. Usage: spec-exp-build.sh <label>
set -eo pipefail
LABEL="${1:?label}"
cd /Users/borkdude/dev/babashka/.claude/worktrees/spec-exp
export GRAALVM_HOME=/Users/borkdude/.sdkman/candidates/java/25.1.3-graal
export BABASHKA_FEATURE_TOOLS_DEPS=true
script/uberjar
script/compile 2>&1 | tee "/Users/borkdude/dev/babashka/.claude/worktrees/tools-deps-native/poc-logs/spec-exp-$LABEL.log" | grep "total image size\|reachable"
ls -l bb | awk '{printf "%s: %.2f MB\n", "'"$LABEL"'", $5/1048576}'
