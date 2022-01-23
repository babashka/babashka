(ns orchestra.many-fns
  (:require #?@(:clj [[clojure.test :refer :all]
                      [clojure.spec.alpha :as s]
                      [orchestra.spec.test :as st]
                      [orchestra.core :refer [defn-spec]]
                      [orchestra.make-fns :refer [make-fns]]]

              :cljs [[cljs.test
                      :refer-macros [deftest testing is use-fixtures]]
                     [cljs.spec.alpha :as s]
                     [orchestra-cljs.spec.test :as st]
                     [orchestra.core :refer-macros [defn-spec]]
                     [orchestra.make-fns :refer-macros [make-fns]]])))

(make-fns 2000)

(deftest many-fns
  (st/instrument))
