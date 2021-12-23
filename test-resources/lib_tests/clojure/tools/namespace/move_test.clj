(ns clojure.tools.namespace.move-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.namespace.move :refer [move-ns]]
            [clojure.tools.namespace.test-helpers :as help])
  (:import (java.io File)))

(defn- create-file-one [dir]
  (help/create-source dir 'example.one :clj
                      '[example.two example.three]
                      '[(defn foo []
                           (example.a.four/foo))]))

(defn- create-file-two [dir]
  (help/create-source dir 'example.two :clj
                    '[example.three example.a.four]))

(defn- create-file-three [dir]
  (help/create-source dir 'example.three :clj
                    '[example.five]))

(defn- create-file-four [dir]
  (help/create-source dir 'example.a.four :clj))

(deftest t-move-ns
  (let [temp-dir (help/create-temp-dir "tools-namespace-t-move-ns")
        src-dir (io/file temp-dir "src")
        example-dir (io/file temp-dir "src" "example")
        file-one (create-file-one src-dir)
        file-two (create-file-two src-dir)
        file-three (create-file-three src-dir)
        old-file-four (create-file-four src-dir)
        new-file-four (io/file example-dir "b" "four.clj")]

    (let [file-three-last-modified (.lastModified file-three)]

      (Thread/sleep 1500) ;; ensure file timestamps are different

      (move-ns 'example.a.four 'example.b.four src-dir [src-dir])

      (is (.exists new-file-four)
          "new file should exist")
      (is (not (.exists old-file-four))
          "old file should not exist")
      (is (not (.exists (.getParentFile old-file-four)))
          "old empty directory should not exist")
      (is (= file-three-last-modified (.lastModified file-three))
          "unaffected file should not have been modified")
      (is (not-any? #(.contains (slurp %) "example.a.four")
                    [file-one file-two file-three new-file-four]))
      (is (every? #(.contains (slurp %) "example.b.four")
                  [file-one file-two new-file-four])))))
