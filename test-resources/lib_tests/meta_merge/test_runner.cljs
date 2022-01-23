(ns meta-merge.test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [meta-merge.core-test]))

(doo-tests 'meta-merge.core-test)
