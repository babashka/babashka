(ns version-clj.core-test
  (:require [clojure.test :refer [deftest are]]
            [version-clj.core :refer [snapshot? qualified?]]))

(deftest t-snapshot
  (are [v r] (= (boolean (snapshot? v)) r)
       "1.0.0"                  false
       "SNAPSHOT"               true
       "1-SNAPSHOT"             true
       "1.0-SNAPSHOT"           true
       "1.0-SNAPSHOT.2"         true
       "1.0-NOSNAPSHOT"         false))

(deftest t-qualified
  (are [v r] (= (boolean (qualified? v)) r)
       "1.0.0"                  false
       "SNAPSHOT"               true
       "1-SNAPSHOT"             true
       "1.0-SNAPSHOT"           true
       "1.0-SNAPSHOT.2"         true
       "1.0-NOSNAPSHOT"         true
       "1.x.2"                  false
       "1.2y"                   true
       "1.y2"                   false
       "1.y"                    false))
