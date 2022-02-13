(ns failjure.runner
    (:require [doo.runner :refer-macros [doo-tests]]
              [failjure.test-core]))

(doo-tests 'failjure.test-core)
