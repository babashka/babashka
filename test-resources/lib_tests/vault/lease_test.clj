(ns vault.lease-test
  (:require
    [clojure.test :refer [deftest is]]
    [vault.lease :as lease])
  (:import
    java.time.Instant))


(defmacro with-time
  "Evaluates the given body of forms with `vault.lease/now` rebound to always
  give the result `t`."
  [t & body]
  `(with-redefs [vault.lease/now (constantly (Instant/ofEpochMilli ~t))]
     ~@body))


(deftest missing-info
  (let [c (lease/new-store)]
    (is (nil? (lease/lookup c :foo))
        "lookup of unstored key should return nil")
    (is (nil? (lease/update! c nil))
        "storing nil should return nil")
    (is (nil? (lease/lookup c :foo))
        "lookup of nil store should return nil")))


(deftest secret-expiry
  (let [c (lease/new-store)]
    (with-time 1000
      (is (= {:path "foo/bar"
              :data {:bar "baz"}
              :lease-id "foo/bar/12345"
              :lease-duration 100
              :renewable true
              :vault.lease/issued (Instant/ofEpochMilli   1000)
              :vault.lease/expiry (Instant/ofEpochMilli 101000)}
             (lease/update! c {:path "foo/bar"
                               :lease-id "foo/bar/12345"
                               :lease-duration 100
                               :renewable true
                               :data {:bar "baz"}}))
          "storing secret info should return data structure"))
    (with-time 50000
      (is (= {:path "foo/bar"
              :data {:bar "baz"}
              :lease-id "foo/bar/12345"
              :lease-duration 100
              :renewable true
              :vault.lease/issued (Instant/ofEpochMilli   1000)
              :vault.lease/expiry (Instant/ofEpochMilli 101000)}
             (lease/lookup c "foo/bar"))
          "lookup of stored secret within expiry should return data structure"))
    (with-time 101001
      (is (lease/expired? (lease/lookup c "foo/bar"))
          "lookup of stored secret after expiry should return nil"))))


(deftest lease-filtering
  (let [c (lease/new-store)
        the-lease {:path "foo/bar"
                   :lease-id "foo/bar/12345"
                   :lease-duration 100
                   :renewable true
                   :vault.lease/renew true
                   :vault.lease/rotate true
                   :vault.lease/issued (Instant/ofEpochMilli   1000)
                   :vault.lease/expiry (Instant/ofEpochMilli 101000)}]
    (with-time 1000
      (lease/update! c {:path "foo/bar"
                        :data {:bar "baz"}
                        :lease-id "foo/bar/12345"
                        :lease-duration 100
                        :renewable true
                        :renew true
                        :rotate true}))
    (with-time 101001
      (is (= [the-lease] (lease/list-leases c))
          "Basic lease listing should work, and the data should match.")
      (is (= [the-lease] (lease/rotatable-leases c 0))
          "Expired but rotatable lease should be considered rotatable"))
    (with-time 100000
      (is (= [the-lease] (lease/renewable-leases c 2)),
          "Renewable leases should be listed when not expired yet.")
      (is (empty? (lease/renewable-leases c 1)),
          "Renewable leases should not be listed when outside the given window.")
      (is (empty? (lease/rotatable-leases c 0))
          "Non-expired, renewable leases should not be considered for rotation."))))


(deftest secret-invalidation
  (let [c (lease/new-store)]
    (is (some? (lease/update! c {:path "foo/bar"
                                 :data {:baz "qux"}
                                 :lease-id "foo/bar/12345"})))
    (is (some? (lease/lookup c "foo/bar")))
    (is (nil? (lease/remove-path! c "foo/bar")))
    (is (nil? (lease/lookup c "foo/bar"))
        "lookup of invalidated secret should return nil")))
