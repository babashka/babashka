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
        fetch        #(client/get (str "http://localhost:" port %) {:as :text})
        root-result  (deref (fetch "/") 500 nil)
        hello-result (deref (fetch "/hello/babashka") 500 nil)]
    (stop-server)
    (is (= "Hi there!" (:body root-result)))
    (is (= "Hello, babashka" (:body hello-result)))))
