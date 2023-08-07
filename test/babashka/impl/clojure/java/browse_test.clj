(ns babashka.impl.clojure.java.browse-test
  (:require
   [babashka.test-utils :refer [bb]]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [org.httpkit.server :as http]))

(def ^:dynamic *http-port* 1234)

(deftest browse-url-test
  (let [p (promise)
        stop-server (http/run-server (fn [{:keys [query-string]}]
                                  (let [params (apply hash-map (mapcat #(.split % "=") (.split query-string "&")))]
                                    (deliver p params)
                                    {:status 200
                                     :content-type "application/json"
                                     :body (json/encode params)}))
                                {:port *http-port*})]
    (try
      (bb nil
       (str "(clojure.java.browse/browse-url \"http://localhost:" *http-port* "?arg1=v1&arg2=v2\")"))
      (is (= {"arg1" "v1"
              "arg2" "v2"}
             (deref p 5000 ::timeout)))
      (finally (stop-server :timeout 1000)))))