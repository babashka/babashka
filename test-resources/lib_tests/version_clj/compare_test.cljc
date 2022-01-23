(ns version-clj.compare-test
  (:require #?(:clj [clojure.test :refer [deftest are]]
               :cljs [cljs.test :refer-macros [deftest are]])
            [version-clj.compare :refer [version-compare]]))

(deftest t-version-compare
  (are [v0 v1 r] (= r (version-compare v0 v1))
       ;; Numeric Comparison
       "1.0.0"          "1.0.0"           0
       "1.0.0"          "1.0"             0
       "1.0.1"          "1.0"             1
       "1.0.0"          "1.0.1"          -1
       "1.0.0"          "0.9.2"           1
       "0.9.2"          "0.9.3"          -1
       "0.9.2"          "0.9.1"           1
       "0.9.5"          "0.9.13"         -1
       "10.2.0.3.0"     "11.2.0.3.0"     -1
       "10.2.0.3.0"     "5.2.0.3.0"       1
       "1.0.0-SNAPSHOT" "1.0.1-SNAPSHOT" -1
       "1.0.0-alpha"    "1.0.1-beta"     -1
       "1.1-dolphin"    "1.1.1-cobra"    -1

       ;; Lexical Comparison
       "1.0-alpaca"     "1.0-bermuda"    -1
       "1.0-alpaca"     "1.0-alpaci"     -1
       "1.0-dolphin"    "1.0-cobra"       1

       ;; Qualifier Comparison
       "1.0.0-alpha"    "1.0.0-beta"     -1
       "1.0.0-beta"     "1.0.0-alpha"     1
       "1.0.0-alpaca"   "1.0.0-beta"     -1
       "1.0.0-final"    "1.0.0-milestone" 1

       ;; Qualifier/Numeric Comparison
       "1.0.0-alpha1"   "1.0.0-alpha2"   -1
       "1.0.0-alpha5"   "1.0.0-alpha23"  -1
       "1.0-RC5"        "1.0-RC20"       -1
       "1.0-RC11"       "1.0-RC6"         1

       ;; Comparison nested vs. value
       "1.0.0"          "1.0-1.0"        -1
       "1.0-1.0"        "1.0.0"           1
       "1.0-0.0"        "1.0.0"           0
       "1.0.0"          "1.0-0.0"         0
       "1.x.0"          "1.x-1.0"        -1
       "1.x-1.0"        "1.x.0"           1
       "1.0-612"        "1.0.613"        -1

       ;; Numbers are newer than strings
       "1.x.1"          "1.0.1"          -1
       "1.0.1"          "1.x.1"           1

       ;; Releases are newer than SNAPSHOTs
       "1.0.0"          "1.0.0-SNAPSHOT"  1
       "1.0.0-SNAPSHOT" "1.0.0-SNAPSHOT"  0
       "1.0.0-SNAPSHOT" "1.0.0"          -1

       ;; Releases are newer than qualified versions
       "1.0.0"          "1.0.0-alpha5"    1
       "1.0.0-alpha5"   "1.0.0"          -1

       ;; SNAPSHOTS are newer than qualified versions
       "1.0.0-SNAPSHOT" "1.0.0-RC1"       1
       "1.0.0-SNAPSHOT" "1.0.1-RC1"      -1

       ;; Some other Formats
       "9.1-901.jdbc4"   "9.1-901.jdbc3"   1
       "9.1-901-1.jdbc4" "9.1-901.jdbc4"   1

       ;; Some more zero-extension Tests
       "1-SNAPSHOT"      "1.0-SNAPSHOT"    0
       "1-alpha"         "1-alpha0"        0

       ;; Prefixed versions
       "v1"              "v1"              0
       "v1"              "v2"             -1
       "v1"              "v1.1"           -1
       "v1.1"            "v1.2"           -1
       "v1.1"            "v2"             -1
       "alpaca1.0"       "bermuda1.0"     -1
       ))

