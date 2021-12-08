(ns babashka.run-all-libtests
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]))

#_(require 'clojure.spec.alpha)

(def ns-args (set (map symbol *command-line-args*)))

(def status (atom {}))

(defn test-namespace? [ns]
  (or (empty? ns-args)
      (contains? ns-args ns)))

(defmacro test-namespaces [& namespaces]
  (let [namespaces (map second namespaces)
        namespaces (seq (filter test-namespace? namespaces))
        quoted-namespaces (map #(list 'quote %) namespaces)
        requires (map #(list 'require %) quoted-namespaces)]
    (when (seq requires)
      `(do
         ~@requires
         (let [m# (t/run-tests ~@quoted-namespaces)]
           (swap! status (fn [status#]
                           (merge-with + status# (dissoc m# :type)))))))))

(def windows? (-> (System/getProperty "os.name")
                (str/lower-case)
                (str/includes? "win")))

;;;; clj-http-lite

(test-namespaces 'clj-http.lite.client-test)

;; ;;;; clojure.spec

(test-namespaces 'clojure.test-clojure.spec
                 'clojure.test-clojure.instr)

;;;; regal

(test-namespaces 'babashka.lambdaisland.regal-test)

;;;; medley

(require '[medley.core :refer [index-by random-uuid]])
(prn (index-by :id [{:id 1} {:id 2}]))
(prn (random-uuid))

;;;; babashka.curl
; skip tests on Windows because of the :compressed thing
(when-not windows? (test-namespaces 'babashka.curl-test))

;;;; cprop

;; TODO: port to test-namespaces

(require '[cprop.core])
(require '[cprop.source :refer [from-env]])
(println (:cprop-env (from-env)))

;;;; comb

;; TODO: port to test-namespaces

(require '[comb.template :as template])
(prn (template/eval "<% (dotimes [x 3] %>foo<% ) %>"))
(prn (template/eval "Hello <%= name %>" {:name "Alice"}))
(def hello
  (template/fn [name] "Hello <%= name %>"))
(prn (hello "Alice"))

;;;; arrangement

;; TODO: port to test-namespaces

(require '[arrangement.core :as order])
(prn (sort order/rank ['a false 2 :b nil 3.14159
                       "c" true \d [3 2] #{:one :two}
                       [3 1 2] #{:three}]))

;;;; clj-yaml

(test-namespaces 'clj-yaml.core-test)

;;;; clojure-csv

;; TODO: port to test-namespaces

(require '[clojure-csv.core :as csv])
;; TODO: convert to test
(prn (csv/write-csv (csv/parse-csv "a,b,c\n1,2,3")))

;;;; clojure.data.zip

;; TODO: port to test-namespaces

(require '[clojure.data.xml :as xml])
(require '[clojure.zip :as zip])
(require '[clojure.data.zip.xml :refer [attr attr= xml1->]])

(def data (str "<root>"
               "  <character type=\"person\" name=\"alice\" />"
               "  <character type=\"animal\" name=\"march hare\" />"
               "</root>"))

;; TODO: convert to test
(let [xml   (zip/xml-zip (xml/parse (java.io.StringReader. data)))]
                                        ;(prn :xml xml)
  (prn :alice-is-a (xml1-> xml :character [(attr= :name "alice")] (attr :type)))
  (prn :animal-is-called (xml1-> xml :character [(attr= :type "animal")] (attr :name))))

;;;; clojure.data.csv

(test-namespaces 'clojure.data.csv-test)

;;;; clojure.math.combinatorics

(test-namespaces 'clojure.math.test-combinatorics)

;;;; deps.clj

;; TODO: port to test-namespaces

(require '[babashka.curl :as curl])
(spit "deps_test.clj"
      (:body (curl/get "https://raw.githubusercontent.com/borkdude/deps.clj/master/deps.clj"
               (if windows? {:compressed false} {}))))

(binding [*command-line-args* ["-Sdescribe"]]
  (load-file "deps_test.clj"))

(.delete (io/file "deps_test.clj"))

;;;; doric

(defn test-doric-cyclic-dep-problem
  []
  (require '[doric.core :as d])
  ((resolve 'doric.core/table) [:a :b] [{:a 1 :b 2}]))

(when (test-namespace? 'doric.test.core)
  (test-doric-cyclic-dep-problem)
  (test-namespaces 'doric.test.core))

;;;; cljc-java-time

(test-namespaces 'cljc.java-time-test)

;;;; camel-snake-kebab

(test-namespaces 'camel-snake-kebab.core-test)

;;;; aero

(test-namespaces 'aero.core-test)

;;;; clojure.data.generators

(test-namespaces 'clojure.data.generators-test)

;;;; honeysql

(test-namespaces 'honeysql.core-test 'honeysql.format-test)

;;;; minimallist

(test-namespaces 'minimallist.core-test)

;;;; bond
(test-namespaces 'bond.test.james)

;;;; version-clj
(test-namespaces 'version-clj.compare-test
                 'version-clj.core-test
                 'version-clj.split-test
                 'version-clj.via-use-test)

;;;; httpkit client

(test-namespaces 'httpkit.client-test)

;;;; babashka.process
(when-not windows?
  ;; test built-in babashka.process
  (test-namespaces 'babashka.process-test)

  ;; test babashka.process from source
  (require '[babashka.process] :reload)
  (test-namespaces 'babashka.process-test))

(test-namespaces 'core-match.core-tests)

(test-namespaces 'hiccup.core-test)
(test-namespaces 'hiccup2.core-test)

(test-namespaces 'test-check.smoke-test)

(test-namespaces 'gaka.core-test)

(test-namespaces 'failjure.test-core)

(test-namespaces 'rewrite-clj.parser-test
                 'rewrite-clj.node-test
                 'rewrite-clj.zip-test
                 'rewrite-clj.paredit-test
                 'rewrite-clj.zip.subedit-test
                 'rewrite-clj.node.coercer-test)

(test-namespaces 'helins.binf.test)

(test-namespaces 'selmer.core-test)
(test-namespaces 'selmer.our-test)

(test-namespaces 'jasentaa.position-test
                 'jasentaa.worked-example-1
                 'jasentaa.worked-example-2
                 'jasentaa.collections-test
                 'jasentaa.parser.basic-test
                 'jasentaa.parser.combinators-test)

(test-namespaces 'honey.sql-test
                 'honey.sql.helpers-test
                 'honey.sql.postgres-test)

(test-namespaces 'slingshot.slingshot-test
                 'slingshot.support-test
                 ;; TODO:
                 ;; 'slingshot.test-test
                 )

(test-namespaces 'hasch.test
                 )

(test-namespaces 'omniconf.core-test)

(test-namespaces 'crispin.core-test)

(test-namespaces 'clojure.data.json-test
                 'clojure.data.json-test-suite-test)

(test-namespaces 'multigrep.core-test)

(test-namespaces
 ;; TODO: env tests don't work because envoy lib isn't compatible with bb
 #_'vault.env-test
 'vault.lease-test
 'vault.client.http-test
 ;; TODO:
 ;; failing tests in the following namespaces:
 #_'vault.client.mock-test
 #_'vault.secrets.kvv1-test
 #_'vault.secrets.kvv2-test)

;; we don't really run any tests for java-http-clj yet, but we require the
;; namespaces to see if they at least load correctly
(test-namespaces 'java-http-clj.smoke-test)

(test-namespaces 'component.component-test)

(test-namespaces 'ruuter.core-test)

(test-namespaces 'clj-commons.digest-test)

(test-namespaces 'hato.client-test)

;;;; final exit code

(let [{:keys [:test :fail :error] :as m} @status]
  (prn m)
  (when-not (pos? test)
    (System/exit 1))
  (System/exit (+ fail error)))
