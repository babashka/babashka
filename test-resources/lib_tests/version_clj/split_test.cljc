(ns version-clj.split-test
  (:require #?(:clj [clojure.test :refer [deftest testing are is]]
               :cljs [cljs.test :refer-macros [deftest testing are is]])
            [version-clj.split :refer [version->seq]]))

(deftest t-split-once-sanity-check
  (let [split-once @#'version-clj.split/split-once
        rx #"(^|(?<=\d)|-)(?=alpha)"]
    (are [in out] (= out (split-once rx in))
         "1-alpha2.2"             ["1" "alpha2.2"]
         "alpha"                  ["" "alpha"]
         "1alpha"                 ["1" "alpha"]
         "0.0.3-alpha.8+oryOS.15" ["0.0.3" "alpha.8+oryOS.15"])))

(deftest t-split
  (are [version v] (= v (version->seq version))
       "1.0.0"                  [[1 0 0]]
       "1.0"                    [[1 0]]
       "1"                      [[1]]
       "1a"                     [[1] ["a"]]
       "1-a"                    [[1] ["a"]]
       "1.0.1-SNAPSHOT"         [[1 0 1] ["snapshot"]]
       "1.0.1-alpha2"           [[1 0 1] ["alpha" 2]]
       "11.2.0.3.0"             [[11 2 0 3 0]]
       "1.0-1-0.2-RC"           [[1 [0 1 0] 2] ["rc"]]
       "1.0-612"                [[1 0] [612]]
       "alpha"                  [[] ["alpha"]]
       "alpha-2"                [[] ["alpha" 2]]
       "1.alpha"                [[1] ["alpha"]]
       "1.alpha.2"              [[1] ["alpha" 2]]
       "1-alpha.2"              [[1] ["alpha" 2]]
       "1-alpha.2.2"            [[1] ["alpha" 2 2]]
       "1-alpha2.2"             [[1] [["alpha" 2] 2]]
       "1.alpha-1.0"            [[1] ["alpha" [1 0]]]
       "0.5.0-alpha.1"          [[0 5 0] ["alpha" 1]]
       "0.5.0-alpha.1"          [[0 5 0] ["alpha" 1]]
       "0.0.3-alpha.8+oryOS.15" [[0 0 3] ["alpha" [8 "+oryos"] 15]]
       "v1"                     [["v" 1]]
       "v1.1"                   [["v" [1 1]]]
       "ver1"                   [["ver" 1]]
       "ver1.1"                 [["ver" [1 1]]]
       ))

(deftest t-split-without-qualifiers
  (testing "well-behaving."
    (are [version] (= (version->seq version)
                        (version->seq version {:qualifiers {}}))
         "1.0.0"
         "1.0"
         "1"
         "1-a"
         "1.0.1-SNAPSHOT"
         "1.0.1-alpha2"
         "11.2.0.3.0"
         "1.0-1-0.2-RC"
         "1-alpha.2"
         "1-alpha.2.2"
         "1-alpha2.2"
         "0.5.0-alpha.1"
         "0.5.0-alpha.1"
         "0.0.3-alpha.8+oryOS.15"))
  (testing "deviants."
    (are [version v] (= v (version->seq version {:qualifiers {}}))
         "alpha"                  [["alpha"]]
         "alpha-2"                [["alpha"] [2]]
         "1a"                     [[1 "a"]]
         "1.alpha"                [[1 "alpha"]]
         "1.alpha.2"              [[1 "alpha" 2]]
         "1.alpha-1.0"            [[1 ["alpha" 1] 0]])))

(deftest t-split-with-large-number
  (is (= [[0 0 1] [20141002100138]]
         (version->seq "0.0.1-20141002100138")))
  #?(:clj
     (let [v (str Long/MAX_VALUE "12345")]
       (is (= (version->seq v) [[(bigint v)]])))))
