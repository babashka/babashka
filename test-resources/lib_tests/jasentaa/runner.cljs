(ns jasentaa.runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [jasentaa.parser.basic-test]
            [jasentaa.parser.combinators-test]
            [jasentaa.collections-test]
            [jasentaa.position-test]
            [jasentaa.worked-example-1]
            [jasentaa.worked-example-2]))

(doo-tests 'jasentaa.parser.basic-test
           'jasentaa.parser.combinators-test
           'jasentaa.collections-test
           'jasentaa.position-test
           'jasentaa.worked-example-1
           'jasentaa.worked-example-2)
