(ns babashka.impl.clojure.java.io-test
  (:require [babashka.test-utils :as test-utils]
            [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is]]))

(defn bb [& args]
  (edn/read-string
   {:readers *data-readers*
    :eof nil}
   (apply test-utils/bb nil (map str args))))

(deftest nio-coercion-test
  (is (true? (bb "
(require '[clojure.java.io :as io])

(extend-protocol io/Coercions
  java.nio.file.Path
  (as-file [this] (.toFile this))
  (as-url [this] (.. this (toFile) (toURL))))

(def path (.toPath (io/file \".\")))
;; ^ this is a java.nio.file.Path

(.exists (io/file path)) ;; true
"))))

(deftest string-as-file-test
  (is (true? (bb "
(require '[clojure.java.io :as io])

(instance? java.io.File (io/as-file \".\"))
"))))

(deftest string-as-url-test
  (is (true? (bb "
(require '[clojure.java.io :as io])

(let [url (io/as-url \"https://github.com/babashka/babashka\")]
  (and (= \"https\" (.getProtocol url))
       (= \"github.com\" (.getHost url))))
"))))
