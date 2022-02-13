(ns medley.test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [medley.core-test]))

(doo-tests 'medley.core-test)
