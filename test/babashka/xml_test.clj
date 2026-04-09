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

(deftest xml-tree-and-event-test
  (is (str/includes?
       (test-utils/bb nil
         "(require '[clojure.data.xml :as xml])
          (require '[clojure.data.xml.tree :as xml-tree])
          (require '[clojure.zip :as zip])

          (defn hiccup->sax-event [h]
            (-> h
                (xml/sexp-as-element)
                (vector)
                (xml-tree/flatten-elements)))

          (defn sax-events->xml-zip [events]
            (->> events
                 (xml-tree/event-tree)
                 (zip/xml-zip)))

          (let [events (hiccup->sax-event [:root [:child \"hello\"]])
                z (sax-events->xml-zip events)]
            (:tag (zip/node z)))")
       ":root"))
  (is (str/includes?
       (test-utils/bb nil
         "(require '[clojure.data.xml :as xml])
          (require '[clojure.data.xml.event :as event])
          (let [events (vec (xml/event-seq (java.io.StringReader. \"<root>text</root>\") {}))]
            ;; StartElementEvent, CharsEvent, EndElementEvent
            [(instance? clojure.data.xml.event.StartElementEvent (nth events 0))
             (instance? clojure.data.xml.event.CharsEvent (nth events 1))
             (instance? clojure.data.xml.event.EndElementEvent (nth events 2))])")
       "[true true true]"))
  (is (str/includes?
       (test-utils/bb nil
         "(require '[clojure.data.xml.event :as event])
          ;; Test constructors
          [(instance? clojure.data.xml.event.StartElementEvent (event/->StartElementEvent :t {} {} nil))
           (instance? clojure.data.xml.event.EmptyElementEvent (event/->EmptyElementEvent :t {} {} nil))
           (instance? clojure.data.xml.event.EndElementEvent (event/->EndElementEvent :t {} nil))
           (instance? clojure.data.xml.event.CharsEvent (event/->CharsEvent \"x\"))
           (instance? clojure.data.xml.event.CDataEvent (event/->CDataEvent \"x\"))
           (instance? clojure.data.xml.event.CommentEvent (event/->CommentEvent \"x\"))
           (instance? clojure.data.xml.event.QNameEvent (event/->QNameEvent :q))]")
       "[true true true true true true true]"))
  (is (str/includes?
       (test-utils/bb nil
         "(require '[clojure.data.xml.event :as event])
          ;; Test map-> constructors
          [(instance? clojure.data.xml.event.StartElementEvent (event/map->StartElementEvent {:tag :t}))
           (instance? clojure.data.xml.event.CharsEvent (event/map->CharsEvent {:str \"x\"}))]")
       "[true true]"))
  (is (str/includes?
       (test-utils/bb nil
         "(require '[clojure.data.xml.jvm.parse :as jvm-parse])
          (type (jvm-parse/string-source \"<x/>\"))")
       "java.io.StringReader")))

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
