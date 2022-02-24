(ns cli-matic.utils-convert-config-test
  (:require [clojure.test :refer [is are deftest testing]]
            [cli-matic.optionals :as OPT]

            [cli-matic.utils-convert-config
             :refer [unmangle-fn-name
                     unmangle-fn
                     fn->className]]))


;
; Some example fns
;


(defn add_numbers [x] x)
(defn add-numbers [y] (inc y))


;
;  Tests
;


(deftest ^:skip-bb unmangle-fn-name-test
  (are [i o]
       (= o (unmangle-fn-name i))

    ;; A moderately complex name
    "cli_matic.utils_v2$convert_config_v1__GT_v2"
    "cli-matic.utils-v2/convert-config-v1->v2"))

(deftest ^:skip-bb unmangle-fn-test
  (are [i o]
       (= o (unmangle-fn i))

    ;; A moderately complex name
    add-numbers
    'cli-matic.utils-convert-config-test/add-numbers

;    add-numbers
;    "cli-matic.utils-convert-config-test/add-numbers"
    ))

(OPT/orchestra-instrument)
