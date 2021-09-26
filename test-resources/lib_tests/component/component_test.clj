(ns component.component-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.stuartsierra.component :as component]))


(def syslog (atom []))

(defn log [msg]
  (swap! syslog conj msg))

(defrecord FakeDB [state]
  component/Lifecycle
  (start [_]
    (log "start DB"))
  (stop [_]
    (log "stop DB")))

(defrecord FakeApp [db]
  component/Lifecycle
  (start [_]
    (log "start app"))
  (stop [_]
    (log "stop app")))

(defn base-app []
  (map->FakeApp {}))

(def sm
  (component/system-map
    :db (->FakeDB :foo)
    :app (component/using (base-app) [:db])))

(component/start sm)

;; do useful stuff here

(component/stop sm)

(deftest ordering-test
  (testing "components are started and stopped in expected order"
    (is (= ["start DB" "start app" "stop app" "stop DB"] @syslog))))
