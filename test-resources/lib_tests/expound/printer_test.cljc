(ns expound.printer-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :as ct :refer [is deftest use-fixtures testing]]
            [expound.printer :as printer]
            [clojure.string :as string]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [expound.test-utils :as test-utils :refer [contains-nan?]]
            [expound.spec-gen :as sg]
            [expound.problems :as problems]))

(def num-tests 5)

(use-fixtures :once
  test-utils/check-spec-assertions
  test-utils/instrument-all)

(defn example-fn [])
(defn get-args [& args] args)

(deftest pprint-fn
  (is (= "string?"
         (printer/pprint-fn (::s/spec (s/explain-data string? 1)))))
  (is (= "expound.printer-test/example-fn"
         (printer/pprint-fn example-fn)))
  (is (= "<anonymous function>"
         (printer/pprint-fn #(inc (inc %)))))
  (is (= "<anonymous function>"
         (printer/pprint-fn (constantly true))))
  (is (= "<anonymous function>"
         (printer/pprint-fn (comp vec str))))
  (is (= "expound.test-utils/instrument-all"
         (printer/pprint-fn test-utils/instrument-all)))
  (is (= "expound.test-utils/contains-nan?"
         (printer/pprint-fn contains-nan?))))

(s/def :print-spec-keys/field1 string?)
(s/def :print-spec-keys/field2 (s/coll-of :print-spec-keys/field1))
(s/def :print-spec-keys/field3 int?)
(s/def :print-spec-keys/field4 string?)
(s/def :print-spec-keys/field5 string?)
(s/def :print-spec-keys/key-spec (s/keys
                                  :req [:print-spec-keys/field1]
                                  :req-un [:print-spec-keys/field2]))
(s/def :print-spec-keys/key-spec2 (s/keys
                                   :req-un [(and
                                             :print-spec-keys/field1
                                             (or
                                              :print-spec-keys/field2
                                              :print-spec-keys/field3))]))
(s/def :print-spec-keys/key-spec3 (s/keys
                                   :req-un [:print-spec-keys/field1
                                            :print-spec-keys/field4
                                            :print-spec-keys/field5]))
(s/def :print-spec-keys/set-spec (s/coll-of :print-spec-keys/field1
                                            :kind set?))
(s/def :print-spec-keys/vector-spec (s/coll-of :print-spec-keys/field1
                                               :kind vector?))
(s/def :print-spec-keys/key-spec4 (s/keys
                                   :req-un [:print-spec-keys/set-spec
                                            :print-spec-keys/vector-spec
                                            :print-spec-keys/key-spec3]))

(defn copy-key [m k1 k2]
  (assoc m k2 (get m k1)))

(deftest print-spec-keys*
  (is (=
       [{"key" :field2, "spec" "(coll-of :print-spec-keys/field1)"}
        {"key" :print-spec-keys/field1, "spec" "string?"}]
       (printer/print-spec-keys*
        (map #(copy-key % :via :expound/via)
             (::s/problems
              (s/explain-data
               :print-spec-keys/key-spec
               {}))))))
  (is (nil?
       (printer/print-spec-keys*
        (map #(copy-key % :via :expound/via)
             (::s/problems
              (s/explain-data
               (s/keys
                :req [:print-spec-keys/field1]
                :req-un [:print-spec-keys/field2])
               {}))))))

  (is (=
       [{"key" :print-spec-keys/field1, "spec" "string?"}]
       (printer/print-spec-keys*
        (map #(copy-key % :via :expound/via)
             (::s/problems
              (s/explain-data
               (s/keys
                :req [:print-spec-keys/field1]
                :req-un [:print-spec-keys/field2])
               {:field2 [""]}))))))

  (is (=
       [{"key" :print-spec-keys/field1, "spec" "string?"}
        {"key" :print-spec-keys/field2,
         "spec" "(coll-of :print-spec-keys/field1)"}]
       (printer/print-spec-keys*
        (map #(copy-key % :via :expound/via)
             (::s/problems
              (s/explain-data
               (s/keys
                :req [:print-spec-keys/field1
                      :print-spec-keys/field2])
               {}))))))
  (is (=
       [{"key" :field1, "spec" "string?"}
        {"key" :field2, "spec" "(coll-of :print-spec-keys/field1)"}
        {"key" :field3, "spec" "int?"}]
       (printer/print-spec-keys*
        (map #(copy-key % :via :expound/via)
             (::s/problems
              (s/explain-data
               :print-spec-keys/key-spec2
               {}))))))
  (is (=
       [{"key" :key-spec3,
         "spec" #?(:clj
                   "(keys\n :req-un\n [:print-spec-keys/field1\n  :print-spec-keys/field4\n  :print-spec-keys/field5])"
                   :cljs
                   "(keys\n :req-un\n [:print-spec-keys/field1\n  :print-spec-keys/field4 \n  :print-spec-keys/field5])")}
        {"key" :set-spec, "spec" #?(:clj
                                    "(coll-of\n :print-spec-keys/field1\n :kind\n set?)"
                                    :cljs
                                    "(coll-of :print-spec-keys/field1 :kind set?)")}
        {"key" :vector-spec, "spec" #?(:clj "(coll-of\n :print-spec-keys/field1\n :kind\n vector?)"
                                       :cljs "(coll-of\n :print-spec-keys/field1 \n :kind \n vector?)")}]
       (printer/print-spec-keys*
        (map #(copy-key % :via :expound/via)
             (::s/problems
              (s/explain-data
               :print-spec-keys/key-spec4
               {})))))))

(deftest print-table
  (is (=
       "
| :key | :spec |
|======+=======|
| abc  | a     |
|      | b     |
|------+-------|
| def  | d     |
|      | e     |
"
       (printer/print-table [{:key "abc" :spec "a\nb"}
                             {:key "def" :spec "d\ne"}])))
  ;; can select ordering of keys
  (is (=
       "
| :b | :c |
|====+====|
| 2  | 3  |
|----+----|
| {} | () |
"
       (printer/print-table
        [:b :c]
        [{:a 1 :b 2 :c 3}
         {:a [] :b {} :c '()}])))

  ;; ordering is deterministic, not based on hashmap
  ;; semantics
  (is (=
       "
| :k | :a | :b | :c | :d | :e | :f | :g | :h | :i | :j |
|====+====+====+====+====+====+====+====+====+====+====|
| k  | a  | b  | c  | d  | e  | f  | g  | h  | i  | j  |
|----+----+----+----+----+----+----+----+----+----+----|
| k  | a  | b  | c  | d  | e  | f  | g  | h  | i  | j  |
"
       (printer/print-table
        [:k :a :b :c :d :e :f :g :h :i :j]
        [{:a "a" :b "b" :c "c" :d "d" :e "e" :f "f" :g "g" :h "h" :i "i" :j "j" :k "k" :l "l"}
         {:l "l" :k "k" :j "j" :i "i" :h "h" :g "g" :f "f" :e "e" :d "d" :c "c" :b "b" :a "a"}]))))

(deftest print-table-gen
  (checking
   "any table with have constant width"
   num-tests
   [col-count (s/gen pos-int?)
    keys (s/gen (s/coll-of keyword? :min-count 1))
    row-count (s/gen pos-int?)
    vals (s/gen (s/coll-of
                 (s/coll-of string? :count col-count)
                 :count row-count))
    :let [rows (mapv
                #(zipmap keys (get vals %))
                (range 0 row-count))
          table (printer/print-table rows)
          srows (rest (string/split table #"\n"))]]

   (is (apply = (map count srows))))

  (checking
   "any table will contain a sub-table of all rows but the last"
   num-tests
   [col-count (s/gen pos-int?)
    keys (s/gen (s/coll-of keyword? :min-count 1))
    row-count (s/gen (s/int-in 2 10))
    vals (s/gen (s/coll-of
                 (s/coll-of string? :count col-count)
                 :count row-count))
    :let [rows (mapv
                #(zipmap keys (get vals %))
                (range 0 row-count))
          sub-rows (butlast rows)
          table (printer/print-table rows)
          sub-table (printer/print-table sub-rows)
          sub-table-last-row (last (string/split sub-table #"\n"))
          table-last-row (last (string/split table #"\n"))]]
      ;; If the line we delete shrinks the width of the table
      ;; (because it was the widest value)
      ;; then the property will not apply
   (when (= (count sub-table-last-row) (count table-last-row))
     (is (string/includes? table sub-table))))

  #?(:clj
     (checking
      "for any known registered spec, table has max width"
      num-tests
      [spec sg/spec-gen
       :let [rows [{:key spec
                    :spec (printer/expand-spec spec)}]
             table (printer/print-table rows)
             srows (rest (string/split table #"\n"))]]
      (is (< (count (last srows)) 200)))
     :cljs
     ;; Noop, just to make clj-kondo happy
     (sg/topo-sort [])))

(deftest highlighted-value
  (testing "atomic value"
    (is (= "\"Fred\"\n^^^^^^"
           (printer/highlighted-value
            {}
            {:expound/form "Fred"
             :expound/in []}))))
  (testing "value in vector"
    (is (= "[... :b ...]\n     ^^"
           (printer/highlighted-value
            {}
            {:expound/form [:a :b :c]
             :expound/in [1]}))))
  (testing "long, composite values are pretty-printed"
    (is (= (str "{:letters {:a \"aaaaaaaa\",
           :b \"bbbbbbbb\",
           :c \"cccccccd\",
           :d \"dddddddd\",
           :e \"eeeeeeee\"}}"
                #?(:clj  "\n          ^^^^^^^^^^^^^^^"
                   :cljs "\n          ^^^^^^^^^^^^^^^^"))
           ;; ^- the above works in clojure - maybe not CLJS?
           (printer/highlighted-value
            {}
            {:expound/form
             {:letters
              {:a "aaaaaaaa"
               :b "bbbbbbbb"
               :c "cccccccd"
               :d "dddddddd"
               :e "eeeeeeee"}}
             :expound/in [:letters]}))))
  (testing "args to function"
    (is (= "(1 ... ...)\n ^"
           (printer/highlighted-value
            {}
            {:expound/form (get-args 1 2 3)
             :expound/in [0]}))))
  (testing "show all values"
    (is (= "(1 2 3)\n ^"
           (printer/highlighted-value
            {:show-valid-values? true}
            {:expound/form (get-args 1 2 3)
             :expound/in [0]}))))

  (testing "special replacement chars are not used"
    (is (= "\"$ $$ $1 $& $` $'\"\n^^^^^^^^^^^^^^^^^^"
           (printer/highlighted-value
            {}
            (first
             (:expound/problems
              (problems/annotate
               (s/explain-data keyword? "$ $$ $1 $& $` $'"))))))))

  (testing "nested map-of specs"
    (is (= "{:a {:b 1}}\n        ^"
           (printer/highlighted-value
            {}
            (first
             (:expound/problems
              (problems/annotate
               (s/explain-data :highlighted-value/nested-map-of {:a {:b 1}})))))))
    (is (= "{:a {\"a\" ...}}\n     ^^^"
           (printer/highlighted-value
            {}
            (first
             (:expound/problems
              (problems/annotate
               (s/explain-data :highlighted-value/nested-map-of {:a {"a" :b}})))))))
    (is (= "{1 ...}\n ^"
           (printer/highlighted-value
            {}
            (first
             (:expound/problems
              (problems/annotate
               (s/explain-data :highlighted-value/nested-map-of {1 {:a :b}}))))))))

  (testing "nested keys specs"
    (is (= "{:address {:city 1}}\n                 ^"
           (printer/highlighted-value
            {}
            (first
             (:expound/problems
              (problems/annotate
               (s/explain-data :highlighted-value/house {:address {:city 1}})))))))
    (is (= "{:address {\"city\" \"Denver\"}}\n          ^^^^^^^^^^^^^^^^^"
           (printer/highlighted-value
            {}
            (first
             (:expound/problems
              (problems/annotate
               (s/explain-data :highlighted-value/house {:address {"city" "Denver"}})))))))
    (is (= "{\"address\" {:city \"Denver\"}}\n^^^^^^^^^^^^^^^^^^^^^^^^^^^^"
           (printer/highlighted-value
            {}
            (first
             (:expound/problems
              (problems/annotate
               (s/explain-data :highlighted-value/house {"address" {:city "Denver"}})))))))))

(deftest highlighted-value-on-alt
  (is (= "[... 0]\n     ^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (clojure.spec.alpha/alt :a int?
                                      :b (clojure.spec.alpha/spec (clojure.spec.alpha/cat :c int?)))
              [1 0]))))))))

(deftest highlighted-value-on-coll-of
  ;; sets
  (is (= "#{1 3 2 :a}\n        ^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              #{1 :a 2 3})))))))
  (is (= "#{:a}\n  ^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              #{:a})))))))

  ;; lists
  (is (= "(... :a ... ...)\n     ^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              '(1 :a 2 3))))))))
  (is (= "(:a)\n ^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              '(:a))))))))

  ;; vectors
  (is (= "[... :a ... ...]\n     ^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              [1 :a 2 3])))))))

  (is (= "[:a]\n ^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              [:a])))))))

    ;; maps
  (is (= "[1 :a]\n^^^^^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              {1 :a 2 3})))))))

  (is (= "[:a 1]\n^^^^^^"
         (printer/highlighted-value
          {}
          (first
           (:expound/problems
            (problems/annotate
             (s/explain-data
              (s/coll-of integer?)
              {:a 1}))))))))
