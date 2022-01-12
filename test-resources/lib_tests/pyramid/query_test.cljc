(ns pyramid.query-test
  (:require
   [pyramid.query :as p.q :refer [q]]
   [clojure.test :as t]))


(def db {:person/id {"123" {:person/id "123"
                            :person/name "foo"
                            :person/best-friend [:person/id "789"]
                            :person/friends [[:person/id "456"]
                                             [:person/id "789"]]}
                     "456" {:person/id "456"
                            :person/name "bar"
                            :person/friends [[:person/id "123"]
                                             [:person/id "789"]]}
                     "789" {:person/id "789"
                            :person/name "baz"
                            :person/friends [[:person/id "123"]
                                             [:person/id "456"]]}
                     "1011" {:person/id "1011"}}
         :person {:bar "baz"}
         :asdf "jkl"})


(t/deftest joins
  (t/is (= '(["123"] ["456"] ["789"] ["1011"])
           (q '[:find ?id
                :where
                [?e :person/id ?id]]
              db)))

  (t/is (= '(["123" "foo"] ["456" "bar"] ["789" "baz"])
           (q '[:find ?id ?name
                :where
                [?e :person/id ?id]
                [?e :person/name ?name]]
              db)))

  (t/is (= '(["123" "foo" [:person/id "789"]])
           (q '[:find ?id ?name ?friend
                :where
                [?e :person/id ?id]
                [?e :person/name ?name]
                [?e :person/best-friend ?friend]]
              db)))

  (t/is (= '(["foo" "baz"])
           (q '[:find ?name ?friend-name
                :where
                [?e :person/name ?name]
                [?e :person/best-friend ?friend]
                [?friend :person/name ?friend-name]]
              db)))

  (t/is (= '()
           (q '[:find ?id
                :where
                [?e :person/name "asdf"]
                [?e :person/id ?id]]
              db))
        "not found")

  (t/is (= '(["123" "foo"])
           (q '[:find ?id ?name
                :in $ ?name
                :where
                [?e :person/name ?name]
                [?e :person/id ?id]]
              db
              "foo"))
        "join on :in")

  (t/is (= '(["foo" "bar"]
             ["foo" "baz"]
             ["bar" "foo"]
             ["bar" "baz"]
             ["baz" "foo"]
             ["baz" "bar"])
           (q '[:find ?name ?friend-name
                :where
                [?e :person/name ?name]
                [?e :person/friends ^:many ?friend]
                [?friend :person/name ?friend-name]]
              db))
        "multiple cardinality value")

  (t/is (= '(["123" "foo"]
             ["456" "bar"])
           (q '[:find ?id ?name
                :in $ ^:many ?name
                :where
                [?e :person/name ?name]
                [?e :person/id ?id]]
              db
              ["foo" "bar"]))
        "multi cardinality join on :in")

  (t/is (= '(["foo" "foo"]
             ["foo" "bar"]
             ["foo" "baz"]
             ["bar" "foo"]
             ["bar" "bar"]
             ["bar" "baz"]
             ["baz" "foo"]
             ["baz" "bar"]
             ["baz" "baz"])
           (q '[:find ?name1 ?name2
                :where
                [?e1 :person/name ?name1]
                [?e2 :person/name ?name2]]
              db))
        "cross product"))
