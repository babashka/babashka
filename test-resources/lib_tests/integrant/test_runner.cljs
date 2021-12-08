(ns integrant.test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [integrant.core-test]))

(doo-tests 'integrant.core-test)
