(ns ruuter.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [org.httpkit.client :as client]
            [org.httpkit.server :as server]
            [ruuter.core :as ruuter]))

(def routes [{:path     "/"
              :method   :get
              :response {:status 200
                         :body   "Hi there!"}}
             {:path     "/hello/:who"
              :method   :get
              :response (fn [req]
                          {:status 200
                           :body   (str "Hello, " (:who (:params req)))})}])

(def port 8080)

(deftest ruuter-with-httpkit-test
  (let [stop-server  (server/run-server #(ruuter/route routes %) {:port port})
        fetch        #(:body (deref (client/get (str "http://localhost:" port %) {:as :text}) 500 nil))
        root-result  (fetch "/")
        hello-result (fetch "/hello/babashka")]
    (stop-server)
    (is (= "Hi there!" root-result))
    (is (= "Hello, babashka" hello-result))))
