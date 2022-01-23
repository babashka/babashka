(ns camel-snake-kebab.test-runner
  (:require [cljs.test :as test]
            [doo.runner :refer-macros [doo-all-tests doo-tests]]
            [camel-snake-kebab.core-test]
            [camel-snake-kebab.extras-test]
            [camel-snake-kebab.internals.string-separator-test]))

(doo-tests 'camel-snake-kebab.core-test
           'camel-snake-kebab.extras-test
           'camel-snake-kebab.internals.string-separator-test)
