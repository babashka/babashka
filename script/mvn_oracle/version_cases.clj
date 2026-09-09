#!/usr/bin/env bb
;; Extracts the assertions of maven-resolver's GenericVersionTest.java into
;; version-cases.edn: [:lt a b], [:eq a b], [:gt a b] and [:sequence v ...].
;; Run: bb script/mvn_oracle/version_cases.clj path/to/GenericVersionTest.java
(require '[clojure.string :as str])

(def src (slurp (first *command-line-args*)))

(def orders
  (for [[_ op a b] (re-seq #"assertOrder\(X_(LT|EQ|GT)_Y, \"([^\"]*)\", \"([^\"]*)\"\)" src)]
    [(keyword (str/lower-case op)) a b]))

(def sequences
  (for [[_ args] (re-seq #"(?s)assertSequence\(([^;]*?)\);" src)]
    (into [:sequence] (map second (re-seq #"\"([^\"]*)\"" args)))))

(let [cases (vec (concat orders sequences))]
  (spit "script/mvn_oracle/version-cases.edn"
        (str ";; From maven-resolver-util's GenericVersionTest, see version_cases.clj.\n"
             (pr-str cases) "\n"))
  (println (count orders) "orders," (count sequences) "sequences"))
