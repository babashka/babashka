(ns environ.test.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [environ.core-test]))

(doo-tests 'environ.core-test)
