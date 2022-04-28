(ns babashka.impl.rrb-vector
  (:require [clojure.core.rrb-vector :as rrb]
            [sci.core :as sci]))

(def rrbns (sci/create-ns 'clojure.core.rrb-vector))

(def rrb-vector-namespace {'catvec (sci/copy-var rrb/catvec rrbns)})
