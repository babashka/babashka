(ns java-http-clj.smoke-test
  (:require [clojure.test :refer [deftest is] :as t]
            [java-http-clj.core :as client]
            [java-http-clj.websocket :as ws-client]
            [org.httpkit.server :as httpkit.server]))

(deftest general-smoke-test
  (is (= 200 (:status (client/get "https://www.clojure.org"))))
  (is (instance? java.net.http.WebSocket$Builder (ws-client/websocket-builder))))

(deftest async-smoke-test
  (is (= 200 (:status @(client/send-async {:uri "https://www.clojure.org" :method :get})))))

(defn ws-handler [{:keys [init] :as opts} req]
  (when init (init req))
  (httpkit.server/as-channel
   req
   (select-keys opts [:on-close :on-ping :on-receive])))

(def ^:dynamic *ws-port* 1234)

(defmacro with-ws-server
  [opts & body]
  `(let [s# (httpkit.server/run-server (partial ws-handler ~opts) {:port ~*ws-port*})]
     (try ~@body (finally (s# :timeout 100)))))

(deftest websockets-smoke-test
  (with-ws-server {:on-receive #(httpkit.server/send! %1 %2)}
    (is (= "zomg websockets!"
           (let [p (promise)
                 ws (ws-client/build-websocket "ws://localhost:1234"
                                               {:on-binary (fn [_ data last?] (deliver p data))
                                                :on-text (fn [ws data last?] (deliver p data))
                                                :on-error (fn [ws throwable] (deliver p throwable))
                                                :on-ping (fn [ws data] (deliver p data))
                                                :on-pong (fn [ws data] (deliver p data))
                                                :on-open (fn [ws] nil)
                                                :on-close (fn [ws status-code reason] nil)})]
             (-> ws
                 (ws-client/send "zomg websockets!"))
             (try (deref p 5000 ::timeout)
                  (finally
                    (ws-client/close ws))))))))
