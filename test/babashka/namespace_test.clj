(ns babashka.namespace-test
  (:require [babashka.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]))

(defn bb [input & args]
  (edn/read-string
      {:readers *data-readers*
       :eof nil}
      (apply tu/bb (when (some? input) (str input)) (map str args))))

(deftest publics-namespace-test
  (testing "all namespace publics (except for those in clojure.lang and user namespaces)
            have ns metadata that matches the namespace it's in"
    (comment "results seq contains vars whose ns meta doesn't match the ns they're in")
    (is (empty? (bb nil "
(let [excluded-namespaces #{'user}]
  (for [nspace              (remove #(excluded-namespaces (ns-name %)) (all-ns))
        [var-symbol ns-var] (ns-publics nspace)
        :let                [ns-ns-name  (ns-name nspace)
                             var-ns-name (some-> ns-var meta :ns ns-name)]
        :when               (not= ns-ns-name var-ns-name)]
    {:containing-ns ns-ns-name
     :ns-on-var     var-ns-name
     :var-name      var-symbol}))")))))
