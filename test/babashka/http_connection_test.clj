(ns babashka.http-connection-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]))

(defn bb [& args]
  (apply tu/bb nil (map str args)))

(deftest ^:flaky open-connection-test
  (is (try (= "\"1\"" (str/trim (bb "-e" "
(require '[cheshire.core :as json])
(let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. \"https://postman-echo.com/get?foo=1\"))]
  (.setConnectTimeout conn 1000)
  (.setRequestProperty conn \"Content-Type\" \"application/json\") ;; nonsensical, but to test if this method exists
  (.connect conn)
  (let [is (.getInputStream conn)
        err (.getErrorStream conn)
        response (json/decode (slurp is) true)]
    (-> response :args :foo)))
")))
           (catch Exception e
             (str/includes? (str e) "timed out")))))
