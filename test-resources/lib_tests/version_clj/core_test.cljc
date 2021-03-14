(ns version-clj.core-test
  (:require #?(:clj [clojure.test :refer [deftest is are]]
               :cljs [cljs.test :refer-macros [deftest is are]])
            [version-clj.core :as v]))

(deftest t-snapshot?
  (are [v r] (= r (boolean (v/snapshot? v)))
    "1.0.0"                  false
    "SNAPSHOT"               true
    "1-SNAPSHOT"             true
    "1.0-SNAPSHOT"           true
    "1.0-SNAPSHOT.2"         true
    "1.0-NOSNAPSHOT"         false))

(deftest t-qualified?
  (are [v r] (= r (boolean (v/qualified? v)))
    "1.0.0"                  false
    "SNAPSHOT"               true
    "SNAPSHOT2"              true
    "1-SNAPSHOT"             true
    "1.0-SNAPSHOT"           true
    "1.0-SNAPSHOT.2"         true
    "1.0-NOSNAPSHOT"         true
    "1.0-NOSNAPSHOT.1"       true
    "1.0-NOSNAPSHOT.1.1"     true
    "1.0-NOSNAPSHOT1.1"      true
    "0.5.3-alpha.1.pre.0"    true
    "1.x.2"                  false
    "1.2y"                   false
    "1.y2"                   false
    "1.y"                    false))

(let [v0 "1.0.0-SNAPSHOT"
      v1 "1.0.0"
      v2 "1.0.1-RC"
      v3 "1.0.1"
      ordered [v0 v1 v2 v3]]
  (deftest t-version-sort
    (is (= ordered (v/version-sort (shuffle ordered))))
    (is (= (map v/version->seq ordered)
           (v/version-seq-sort (map v/version->seq ordered)))))

  (deftest t-version-compare
    (is (pos? (v/version-compare v1 v0)))
    (is (neg? (v/version-compare v0 v1)))
    (is (zero? (v/version-compare v0 v0)))
    (is (v/older? v0 v1))
    (is (v/newer? v1 v0))
    (is (v/older-or-equal? v0 v1))
    (is (v/older-or-equal? v0 v0))
    (is (v/newer-or-equal? v1 v0))
    (is (v/newer-or-equal? v0 v0)))

  (deftest t-version-seq-compare
    (is (pos? (v/version-seq-compare
                (v/version->seq v1)
                (v/version->seq v0))))
    (is (neg? (v/version-seq-compare
                (v/version->seq v0)
                (v/version->seq v1))))
    (is (zero? (v/version-seq-compare
                 (v/version->seq v0)
                 (v/version->seq v0))))))

(deftest t-parse
  (let [s "1.0.1", version (v/parse s)]
    (is (= [[1 0 1]] (:version version)))
    (is (= #{} (:qualifiers version)))
    (is (not (:snapshot? version)))
    (is (not (:qualified? version))))
  (let [s "1.0.1-RC1-SNAPSHOT", version (v/parse s)]
    (is (= [[1 0 1] [["rc" 1] "snapshot"]] (:version version)))
    (is (= #{"rc" "snapshot"} (:qualifiers version)))
    (is (:snapshot? version))
    (is (:qualified? version)))
  (let [s "1.0.1"]
    (is (= (v/parse s) (v/parse (v/version->seq s))))))

(deftest t-version-and-qualifier-data
  (is (= [1 0 1] (v/version-data "1.0.1-RC")))
  (is (= ["rc"] (v/qualifier-data "1.0.1-RC"))))
