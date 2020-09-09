(ns babashka.run-all-libtests
  (:require [clojure.java.io :as io]
            [clojure.test :as t]))

(def status (atom {}))

(defn test-namespaces [& namespaces]
  (doseq [ns namespaces]
    (require ns))
  (let [m (apply t/run-tests namespaces)]
    (swap! status (fn [status]
                    (merge-with + status (dissoc m :type))))))

;;;; clj-http-lite

(require '[clj-http.lite.client :as client])
(require '[cheshire.core :as json])

(prn (:status (client/get "https://www.clojure.org" {:throw-exceptions false})))

(prn (:status (client/get "https://postman-echo.com/get?foo1=bar1&foo2=bar2" {:throw-exceptions false})))

(prn (:status (client/post "https://postman-echo.com/post" {:throw-exceptions false})))

(prn (:status (client/post "https://postman-echo.com/post"
                           {:body (json/generate-string {:a 1})
                            :headers {"X-Hasura-Role" "admin"}
                            :content-type :json
                            :accept :json
                            :throw-exceptions false})))

(prn (:status (client/put "https://postman-echo.com/put"
                          {:body (json/generate-string {:a 1})
                           :headers {"X-Hasura-Role" "admin"}
                           :content-type :json
                           :accept :json
                           :throw-exceptions false})))

;;;; spartan.spec

(time (require '[spartan.spec :as s]))
(require '[spartan.spec :as s])
(time (s/explain (s/cat :i int? :s string?) [1 :foo]))
(time (s/conform (s/cat :i int? :s string?) [1 "foo"]))

;;;; regal

(require '[lambdaisland.regal :as regal])
(def r [:cat
        [:+ [:class [\a \z]]]
        "="
        [:+ [:not \=]]])

(prn (regal/regex r))
(prn (re-matches (regal/regex r) "foo=bar"))

;;;; medley

(require '[medley.core :refer [index-by random-uuid]])
(prn (index-by :id [{:id 1} {:id 2}]))
(prn (random-uuid))

;;;; babashka.curl

(require '[babashka.curl :as curl] :reload-all)

(prn (:status (curl/get "https://www.clojure.org")))

(prn (:status (curl/get "https://postman-echo.com/get?foo1=bar1&foo2=bar2")))

(prn (:status (curl/post "https://postman-echo.com/post")))

(prn (:status (curl/post "https://postman-echo.com/post"
                         {:body (json/generate-string {:a 1})
                          :headers {"X-Hasura-Role" "admin"}
                          :content-type :json
                          :accept :json})))

(prn (:status (curl/put "https://postman-echo.com/put"
                        {:body (json/generate-string {:a 1})
                         :headers {"X-Hasura-Role" "admin"}
                         :content-type :json
                         :accept :json})))


;;;; cprop

(require '[cprop.core])
(require '[cprop.source :refer [from-env]])
(println (:cprop-env (from-env)))

;;;; comb

(require '[comb.template :as template])
(prn (template/eval "<% (dotimes [x 3] %>foo<% ) %>"))
(prn (template/eval "Hello <%= name %>" {:name "Alice"}))
(def hello
  (template/fn [name] "Hello <%= name %>"))
(prn (hello "Alice"))

;;;; arrangement

(require '[arrangement.core :as order])
(prn (sort order/rank ['a false 2 :b nil 3.14159
                       "c" true \d [3 2] #{:one :two}
                       [3 1 2] #{:three}]))

;;;; clj-yaml

(test-namespaces 'clj-yaml.core-test)

;;;; clojure-csv

(require '[clojure-csv.core :as csv])
;; TODO: convert to test
(prn (csv/write-csv (csv/parse-csv "a,b,c\n1,2,3")))

;;;; clojure.data.zip

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

(spit "deps_test.clj"
      (:body (curl/get "https://raw.githubusercontent.com/borkdude/deps.clj/master/deps.clj")))

(binding [*command-line-args* ["-Sdescribe"]]
  (load-file "deps_test.clj"))

(.delete (io/file "deps_test.clj"))

;;;; doric

(test-namespaces 'doric.test.core)

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

;;;; final exit code

(let [{:keys [:test :fail :error] :as m} @status]
  (prn m)
  (when-not (pos? test)
    (System/exit 1))
  (System/exit (+ fail error)))
