(ns babashka.xml-test
  (:require [babashka.test-utils :as test-utils]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def simple-xml-str "<a><b>data</b></a>")

(deftest xml-edn-read-test
  (let [parsed-edn     (test-utils/bb nil (str "(xml/parse-str \"" simple-xml-str "\")"))
        emitted-xml    (test-utils/bb parsed-edn "(xml/emit-str *input*)")]
    (is (str/includes? emitted-xml simple-xml-str))))

(def round-trip-prog
  (str "(xml/emit-str (read-string (pr-str (xml/parse-str \"" simple-xml-str "\"))))"))

(deftest xml-data-readers-test
  (is (str/includes? (test-utils/bb nil round-trip-prog) simple-xml-str)))

(deftest virtual-threads-bug-test
  (is (str/starts-with? (test-utils/bb nil "(require '[clojure.core.async]
         '[clojure.data.xml])

(def go-blocks (atom []))

(dotimes [_ 100]
  (swap! go-blocks conj (clojure.core.async/go (clojure.data.xml/parse
                                                (java.io.ByteArrayInputStream.
                                                 (.getBytes \"<a></a>\" \"UTF-8\"))
                                                :namespace-aware false
                                                :skip-whitespace true))))

(doseq [block @go-blocks]
  (clojure.core.async/<!! block))

true")
                        "true")))
