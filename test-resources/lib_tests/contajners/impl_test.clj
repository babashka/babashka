(ns contajners.impl-test
  (:require
    [clojure.test :as t]
    [contajners.impl :as impl]))

(t/deftest meta-cleanup
  (t/testing "remove internal namespace"
    (t/is (= [:foo]
             (impl/remove-internal-meta [:contajners/foo :foo])))))

(t/deftest param-gathering
  (t/testing "gathering params as header query and path"
    (t/is (= {:header {:a 1 :b 2}
              :query  {:c 3 :d 4}
              :path   {:e 5 :f 6}}
             (reduce (partial impl/gather-params {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6})
                     {}
                     [{:name "a" :in :header}
                      {:name "b" :in :header}
                      {:name "c" :in :query}
                      {:name "d" :in :query}
                      {:name "e" :in :path}
                      {:name "f" :in :path}])))))

(t/deftest body-serialization
  (t/testing "body serialization when a map"
    (t/is (= {:headers {"content-type" "application/json"}
              :body    "{\"a\":42}"}
             (impl/maybe-serialize-body {:body {:a 42}}))))
  (t/testing "body serialization when not a map"
    (t/is (= {:body "yes"}
             (impl/maybe-serialize-body {:body "yes"})))))

(t/deftest path-interpolation
  (t/testing "path interpolation"
    (t/is (= "/a/{w}/b/41/42"
             (impl/interpolate-path "/a/{w}/b/{x}/{y}" {:x 41 :y 42 :z 43})))))

(t/deftest json-parsing
  (t/testing "successful json parsing"
    (t/is (= {:a 42}
             (impl/try-json-parse "{\"a\":42}"))))
  (t/testing "failed json parsing"
    (t/is (= "yes"
             (impl/try-json-parse "yes")))))
