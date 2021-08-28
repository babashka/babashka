(ns babashka.java-http-client-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [expr]
  (edn/read-string (apply test-utils/bb nil [(str expr)])))

(deftest java-http-client-test
  (is (= [200 true]
         (bb
          '(do (ns net
                 (:import [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
                          [java.net URI]))

               (def req
                 (-> (HttpRequest/newBuilder (URI. "https://www.clojure.org"))
                     (.GET)
                     (.build)))

               (def client
                 (-> (HttpClient/newBuilder)
                     (.build)))

               (def resp (.send client req (HttpResponse$BodyHandlers/ofString)))
               [(.statusCode resp) (string? (.body resp))])))))
