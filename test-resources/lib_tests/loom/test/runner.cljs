(ns loom.test.runner
  (:require [cljs.test :as test]
            [doo.runner :refer-macros [doo-all-tests doo-tests]]
            loom.test.alg
            loom.test.alg-generic
            loom.test.attr
            loom.test.derived
            loom.test.flow
            loom.test.graph
            loom.test.label))

(doo-all-tests)
