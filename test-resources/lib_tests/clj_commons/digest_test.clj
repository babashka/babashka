(ns clj-commons.digest-test
  (:require [clj-commons.digest] 
            [clojure.java.io :as io] 
            [clojure.test :refer [deftest is]]))

(def examples
  {"clojure" {'sha-256 "4f3ea34e0a3a6196a18ec24b51c02b41d5f15bd04b4a94aa29e4f6badba0f5b0" 
              'md5 "32c0d97f82a20e67c6d184620f6bd322"
              'sha-1 "49c91cf925f70570a72cf406e9b112ce9e32250c"} 
   nil {'sha-256 nil 'md5 nil 'sha-1 nil} 
   (io/file "test-resources/babashka/empty.clj") {'sha-256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                                                  'md5 "d41d8cd98f00b204e9800998ecf8427e"
                                                  'sha-1 "da39a3ee5e6b4b0d3255bfef95601890afd80709"}})

(deftest digest-examples-test
  (doseq [[input algo-result] examples
          [algo expected] algo-result]
    (is (= ((ns-resolve 'clj-commons.digest algo) input) expected))))
