(ns babashka.statecharts-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :refer [statechart]]
   [com.fulcrologic.statecharts.elements :refer [state transition]]
   [com.fulcrologic.statecharts.events :refer [new-event]]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [taoensso.timbre :as timbre]))

(timbre/set-ns-min-level! "com.fulcrologic.statecharts.algorithms.v20150901-impl" :warn)

(def light-switch
  (statechart {:initial :off}
    (state {:id :on}
      (transition {:event :toggle, :target :off}))
    (state {:id :off}
      (transition {:event :toggle, :target :on}))))

(deftest light-switch-state-transitions
  (testing "The light switch toggles between on and off states correctly."
    (let [;; 1. Set up the environment and processor
          env       (simple/simple-env)
          _         (simple/register! env ::switch light-switch)
          processor (::sc/processor env)

          ;; 2. Start the machine, which returns the initial "working memory" (s0)
          s0 (sp/start! processor env ::switch {::sc/session-id 1})]

      (testing "Initial state"
        ;; The active state configuration is a set.
        (is (= #{:off} (::sc/configuration s0)) "starts in the :off state."))

      (let [;; 3. Send the first :toggle event to get the next state (s1)
            s1 (sp/process-event! processor env s0 (new-event :toggle))]

        (testing "After one toggle"
          (is (= #{:on} (::sc/configuration s1)) "moves to the :on state.")))

      (let [;; Same as above, but chained for the next step.
            s1 (sp/process-event! processor env s0 (new-event :toggle))
            s2 (sp/process-event! processor env s1 (new-event :toggle))]

        (testing "After a second toggle"
          (is (= #{:off} (::sc/configuration s2)) "moves back to the :off state."))))))
