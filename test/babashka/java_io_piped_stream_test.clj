(ns babashka.java-io-piped-stream-test
  (:require [clojure.test :as test :refer [deftest is]])
  (:import [java.io PipedInputStream PipedOutputStream]))

(deftest piped-stream-test
  (let [pis (PipedInputStream.)
        pos (PipedOutputStream.)
        char-seq [66 97 98 97 115 104 107 97]
        _ (.connect pis pos)
        _ (doseq [c char-seq]
            (.write pos c))]
    (is (= "Babashka" (apply str (for [_ char-seq] (char (.read pis))))))))
