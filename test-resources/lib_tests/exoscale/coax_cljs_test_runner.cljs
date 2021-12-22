(ns exoscale.coax.cljs-test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [exoscale.coax-test]))

(doo-tests 'exoscale.coax-test)
