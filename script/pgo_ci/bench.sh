#!/usr/bin/env bash
# Compares the PGO binary with the -O1 binary. Run from the repo root.
cd script/pgo_ci
for round in 1 2 3; do
  for b in bb bb-o1; do
    h=$(../../$b --config bb.edn hp.clj 2>&1 | tr '\n' ';')
    l=$(../../$b --config bb.edn loadt.clj 2>&1 | tail -1)
    echo "pgo-bench $b: $h $l"
  done
done
ls -l ../../bb* | awk '{print "pgo-size", $5, $9}'
