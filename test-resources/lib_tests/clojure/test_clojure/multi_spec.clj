(ns clojure.test-clojure.multi-spec
  (:require [clojure.spec.alpha :as s]
            [clojure.test :as test :refer [deftest is testing]]))

(defn submap?
  "Is m1 a subset of m2?"
  [m1 m2]
  (if (and (map? m1) (map? m2))
    (every? (fn [[k v]] (and (contains? m2 k)
                             (submap? v (get m2 k))))
            m1)
    (= m1 m2)))

(s/def :event/type keyword?)
(s/def :event/timestamp int?)
(s/def :search/url string?)
(s/def :error/message string?)
(s/def :error/code int?)

(defmulti event-type :event/type)
(defmethod event-type :event/search [_]
  (s/keys :req [:event/type :event/timestamp :search/url]))
(defmethod event-type :event/error [_]
  (s/keys :req [:event/type :event/timestamp :error/message :error/code]))

(s/def :event/event (s/multi-spec event-type :event/type))

(deftest multi-spec-test
  (is (s/valid? :event/event
                {:event/type :event/search
                 :event/timestamp 1463970123000
                 :search/url "https://clojure.org"}))
  (is (s/valid? :event/event
                {:event/type :event/error
                 :event/timestamp 1463970123000
                 :error/message "Invalid host"
                 :error/code 500}))
  (is (submap?
       '#:clojure.spec.alpha{:problems
                             [{:path [:event/restart],
                               :pred clojure.test-clojure.multi-spec/event-type,
                               :val #:event{:type :event/restart}, :reason "no method", :via [:event/event], :in []}],
                             :spec :event/event, :value #:event{:type :event/restart}}
       (s/explain-data :event/event
                       {:event/type :event/restart})))
  (is (submap?
       '#:clojure.spec.alpha{:problems ({:path [:event/search],
                                        :pred (clojure.core/fn [%] (clojure.core/contains? % :event/timestamp)),
                                        :val {:event/type :event/search, :search/url 200},
                                        :via [:event/event], :in []} {:path [:event/search :search/url],
                                                                      :pred clojure.core/string?, :val 200,
                                                                      :via [:event/event :search/url],
                                                                      :in [:search/url]}), :spec
                            :event/event, :value {:event/type :event/search, :search/url 200}}
       (s/explain-data  :event/event
                        {:event/type :event/search
                         :search/url 200}))))
