(ns exoscale.coax-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest testing is are run-tests]]
                            [exoscale.coax :as sc]))
  (:require
   #?(:clj [clojure.test :refer [deftest testing is are]])
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.test.check :as tc]
   [clojure.test.check.generators]
   [clojure.test.check.properties :as prop]
   [clojure.spec.test.alpha :as st]
   #?(:clj [clojure.test.check.clojure-test :refer [defspec]])
   #?(:cljs [clojure.test.check.clojure-test :refer-macros [defspec]])
   [exoscale.coax :as sc]
   [exoscale.coax.coercer :as c])
  #?(:clj (:import (java.net URI))))

#?(:clj (st/instrument))

(s/def ::infer-int int?)
(s/def ::infer-and-spec (s/and int? #(> % 10)))
(s/def ::infer-and-spec-indirect (s/and ::infer-int #(> % 10)))
(s/def ::infer-form (s/coll-of int?))
(s/def ::infer-nilable (s/nilable int?))

#?(:clj (s/def ::infer-decimal? decimal?))

(sc/def ::some-coercion c/to-long)

(s/def ::first-layer int?)
(sc/def ::first-layer (fn [x _] (inc (c/to-long x nil))))

(s/def ::second-layer ::first-layer)
(s/def ::second-layer-and (s/and ::first-layer #(> % 10)))

(s/def ::or-example (s/or :int int? :double double? :bool boolean?))

(s/def ::nilable-int (s/nilable ::infer-int))
(s/def ::nilable-pos-int (s/nilable (s/and ::infer-int pos?)))
(s/def ::nilable-string (s/nilable string?))

(s/def ::nilable-set #{nil})
(s/def ::int-set #{1 2})
(s/def ::float-set #{1.2 2.1})
(s/def ::boolean-set #{true})
(s/def ::symbol-set #{'foo/bar 'bar/foo})
(s/def ::ident-set #{'foo/bar :bar/foo})
(s/def ::string-set #{"hey" "there"})
(s/def ::keyword-set #{:a :b})
(s/def ::uuid-set #{#uuid "d6e73cc5-95bc-496a-951c-87f11af0d839"
                    #uuid "a6e73cc5-95bc-496a-951c-87f11af0d839"})
(s/def ::nil-set #{nil})
#?(:clj (s/def ::uri-set #{(URI. "http://site.com")
                           (URI. "http://site.org")}))
#?(:clj (s/def ::decimal-set #{42.42M 1.1M}))

(def enum-set #{:a :b})
(s/def ::referenced-set enum-set)

(def enum-map {:foo "bar"
               :baz "qux"})
(s/def ::calculated-set (->> enum-map keys (into #{})))

(s/def ::nilable-referenced-set (s/nilable enum-set))
(s/def ::nilable-calculated-set (s/nilable (->> enum-map keys (into #{}))))

(s/def ::nilable-referenced-set-kw (s/nilable ::referenced-set))
(s/def ::nilable-calculated-set-kw (s/nilable ::calculated-set))

(s/def ::unevaluatable-spec (letfn [(pred [x] (string? x))]
                              (s/spec pred)))

(sc/def ::some-coercion c/to-long)

(deftest test-coerce-from-registry
  (testing "it uses the registry to coerce a key"
    (is (= (sc/coerce ::some-coercion "123") 123)))

  (testing "it returns original value when it a coercion can't be found"
    (is (= (sc/coerce ::not-defined "123") "123")))

  (testing "go over nilables"
    (is (= (sc/coerce ::infer-nilable "123") 123))
    (is (= (sc/coerce ::infer-nilable nil) nil))
    (is (= (sc/coerce ::infer-nilable "") ""))
    (is (= (sc/coerce ::nilable-int "10") 10))
    (is (= (sc/coerce ::nilable-int "10" {::sc/idents {`int? (fn [x _] (keyword x))}}) :10))
    (is (= (sc/coerce ::nilable-pos-int "10") 10))

    (is (= (sc/coerce ::nilable-string nil) nil))
    (is (= (sc/coerce ::nilable-string 1) "1"))
    (is (= (sc/coerce ::nilable-string "") ""))
    (is (= (sc/coerce ::nilable-string "asdf") "asdf")))

  (testing "specs given as sets"
    (is (= (sc/coerce ::nilable-set nil) nil))
    (is (= (sc/coerce ::int-set "1") 1))
    (is (= (sc/coerce ::float-set "1.2") 1.2))
    (is (= (sc/coerce ::boolean-set "true") true))
    ;;(is (= (sc/coerce ::symbol-set "foo/bar") 'foo/bar))
    (is (= (sc/coerce ::string-set "hey") "hey"))
    (is (= (sc/coerce ::keyword-set ":b") :b))
    (is (= (sc/coerce ::uuid-set "d6e73cc5-95bc-496a-951c-87f11af0d839") #uuid "d6e73cc5-95bc-496a-951c-87f11af0d839"))
    ;;#?(:clj (is (= (sc/coerce ::uri-set "http://site.com") (URI. "http://site.com"))))
    #?(:clj (is (= (sc/coerce ::decimal-set "42.42M") 42.42M)))

    ;; The following tests can't work without using `eval`. We will avoid this
    ;; and hope that spec2 will give us a better way.
    ;;(is (= (sc/coerce ::referenced-set ":a") :a))
    ;;(is (= (sc/coerce ::calculated-set ":foo") :foo))
    ;;(is (= (sc/coerce ::nilable-referenced-set ":a") :a))
    ;;(is (= (sc/coerce ::nilable-calculated-set ":foo") :foo))
    ;;(is (= (sc/coerce ::nilable-referenced-set-kw ":a") :a))
    ;;(is (= (sc/coerce ::nilable-calculated-set-kw ":foo") :foo))

    (is (= (sc/coerce ::unevaluatable-spec "just a string") "just a string"))))

(deftest test-coerce!
  (is (= (sc/coerce! ::infer-int "123") 123))
  (is (thrown-with-msg? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                        #"Invalid coerced value" (sc/coerce! ::infer-int "abc"))))

(deftest test-conform
  (is (= (sc/conform ::or-example "true") [:bool true])))

(deftest test-coerce-from-predicates
  (are [predicate input output] (= (sc/coerce predicate input) output)
    `number? "42" 42
    `number? "foo" "foo"
    `integer? "42" 42
    `int? "42" 42
    `int? 42.0 42
    `int? 42.5 42
    `(s/int-in 0 100) "42" 42
    `pos-int? "42" 42
    `neg-int? "-42" -42
    `nat-int? "10" 10
    `even? "10" 10
    `odd? "9" 9
    `float? "42.42" 42.42
    `double? "42.42" 42.42
    `double? 42.42 42.42
    `double? 42 42.0

    `number? "42.42" 42.42
    `number? 42.42 42.42
    `number? 42 42

    `(s/double-in 0 100) "42.42" 42.42
    `string? 42 "42"
    `string? :a ":a"
    `string? :foo/bar ":foo/bar"
    `string? [] []
    `string? {} {}
    `string? #{} #{}
    `boolean? "true" true
    `boolean? "false" false
    `ident? ":foo/bar" :foo/bar
    `ident? "foo/bar" 'foo/bar
    `simple-ident? ":foo" :foo
    `qualified-ident? ":foo/baz" :foo/baz
    `keyword? "keyword" :keyword
    `keyword? ":keyword" :keyword
    `keyword? 'symbol :symbol
    `simple-keyword? ":simple-keyword" :simple-keyword
    `qualified-keyword? ":qualified/keyword" :qualified/keyword
    `symbol? "sym" 'sym
    `simple-symbol? "simple-sym" 'simple-sym
    `qualified-symbol? "qualified/sym" 'qualified/sym
    `uuid? "d6e73cc5-95bc-496a-951c-87f11af0d839" #uuid "d6e73cc5-95bc-496a-951c-87f11af0d839"
    `nil? nil nil
    `false? "false" false
    `true? "true" true
    `zero? "0" 0

    `(s/coll-of int?) ["11" "31" "42"] [11 31 42]
    `(s/coll-of int?) ["11" "foo" "42"] [11 "foo" 42]
    `(s/coll-of int? :kind list?) ["11" "foo" "42"] '(11 "foo" 42)
    `(s/coll-of int? :kind set?) ["11" "foo" "42"] #{11 "foo" 42}
    `(s/coll-of int? :kind set?) #{"11" "foo" "42"} #{11 "foo" 42}
    `(s/coll-of int? :kind vector?) '("11" "foo" "42") [11 "foo" 42]
    `(s/every int?) ["11" "31" "42"] [11 31 42]

    `(s/map-of keyword? int?) {"foo" "42" "bar" "31"} {:foo 42 :bar 31}
    `(s/map-of keyword? int?) "foo" "foo"
    `(s/every-kv keyword? int?) {"foo" "42" "bar" "31"} {:foo 42 :bar 31}

    `(s/or :int int? :double double? :bool boolean?) "42" 42
    `(s/or :double double? :bool boolean?) "42.3" 42.3
    `(s/or :int int? :double double? :bool boolean?) "true" true

    `(s/or :b keyword? :a string?) "abc" "abc"
    `(s/or :a string? :b keyword?) "abc" "abc"
    `(s/or :b keyword? :a string?) :abc :abc

    `(s/or :str string? :kw keyword? :number? number?) :asdf :asdf
    `(s/or :str string? :kw keyword? :number? number?) "asdf" "asdf"
    `(s/or :kw keyword? :str string? :number? number?) "asdf" "asdf"
    `(s/or :number? number? :kw keyword?) "1" 1
    `(s/or :number? number?) "1" 1
    `(s/or :number? number? :kw keyword? :str string?) "1" "1"
    `(s/or :number? number? :kw keyword? :str string?) 1 1
    #{:a :b} "a" :a
    #{1 2} "1" 1

    #?@(:clj [`uri? "http://site.com" (URI. "http://site.com")])
    #?@(:clj [`decimal? "42.42" 42.42M
              `decimal? "42.42M" 42.42M])))

(def test-gens
  {`inst? (s/gen (s/inst-in #inst "1980" #inst "9999"))})

#?(:cljs
   (defn ->js [var-name]
     (-> (str var-name)
         (str/replace #"/" ".")
         (str/replace #"-" "_")
         (str/replace #"\?" "_QMARK_")
         (js/eval))))

(defn safe-gen [s sp]
  (try
    (or (test-gens s)
        (s/gen sp))
    (catch #?(:clj Exception :cljs :default) _ nil)))

#?(:clj
   ;; FIXME won't run on cljs
   (deftest test-coerce-generative
     (doseq [s (->> (sc/registry)
                    ::sc/idents
                    (keys)
                    (filter symbol?))
             :let [sp #?(:clj @(resolve s) :cljs (->js s))
                   gen (safe-gen s sp)]
             :when gen]
       (let [res (tc/quick-check 100
                                 (prop/for-all [v gen]
                                               (s/valid? sp (sc/coerce s (-> (pr-str v)
                                                                             (str/replace #"^#[^\"]+\"|\"]?$"
                                                                                          ""))))))]
         (if-not (= true (:result res))
           (throw (ex-info (str "Error coercing " s)
                           {:symbol s
                            :spec sp
                            :result res})))))))

#?(:clj (deftest test-coerce-inst
          (are [input output] (= (sc/coerce `inst? input)
                                 output)
            "2020-05-17T21:37:57.830-00:00" #inst "2020-05-17T21:37:57.830-00:00"
            "2018-09-28" #inst "2018-09-28")))

(deftest test-coerce-inference-test
  (are [keyword input output] (= (sc/coerce keyword input) output)
    ::infer-int "123" 123
    ::infer-and-spec "42" 42
    ::infer-and-spec-indirect "43" 43
    ::infer-form ["20" "43"] [20 43]
    ::infer-form '("20" "43") '(20 43)
    ::infer-form (map str (range 2)) '(0 1)
    ::second-layer "41" 42
    ::second-layer-and "41" 42

    #?@(:clj [::infer-decimal? "123.4" 123.4M])
    #?@(:clj [::infer-decimal? 123.4 123.4M])
    #?@(:clj [::infer-decimal? 123.4M 123.4M])
    #?@(:clj [::infer-decimal? "" ""])
    #?@(:clj [::infer-decimal? [] []])))

(deftest test-coerce-structure
  (is (= (sc/coerce-structure {::some-coercion "321"
                               ::not-defined "bla"
                               :sub {::infer-int "42"}})
         {::some-coercion 321
          ::not-defined "bla"
          :sub {::infer-int 42}}))
  (is (= (sc/coerce-structure {::some-coercion "321"
                               ::not-defined "bla"
                               :unqualified 12
                               :sub {::infer-int "42"}}
                              {::sc/idents {::not-defined `keyword?}})
         {::some-coercion 321
          ::not-defined :bla
          :unqualified 12
          :sub {::infer-int 42}}))
  (is (= (sc/coerce-structure {::or-example "321"}
                              {::sc/op sc/conform})
         {::or-example [:int 321]})))

(s/def ::bool boolean?)
(s/def ::simple-keys (s/keys :req [::infer-int]
                             :opt [::bool]))
(s/def ::nested-keys (s/keys :req [::infer-form ::simple-keys]
                             :req-un [::bool]))

(deftest test-coerce-keys
  (is (= {::infer-int 123}
         (sc/coerce ::simple-keys {::infer-int "123"})))
  (is (= {::infer-form [1 2 3]
          ::simple-keys {::infer-int 456
                         ::bool true}
          :bool true}
         (sc/coerce ::nested-keys {::infer-form ["1" "2" "3"]
                                   ::simple-keys {::infer-int "456"
                                                  ::bool "true"}
                                   :bool "true"})))
  (is (= "garbage" (sc/coerce ::simple-keys "garbage"))))

(s/def ::head double?)
(s/def ::body int?)
(s/def ::arm int?)
(s/def ::leg double?)
(s/def ::arms (s/coll-of ::arm))
(s/def ::legs (s/coll-of ::leg))
(s/def ::name string?)
(s/def ::animal (s/keys :req [::head ::body ::arms ::legs]
                        :opt-un [::name ::id]))

(deftest test-coerce-with-registry-overrides
  (testing "it uses overrides when provided"
    (is (= {::head 1
            ::body 16
            ::arms [4 4]
            ::legs [7 7]
            :foo "bar"
            :name :john}
           (sc/coerce ::animal
                      {::head "1"
                       ::body "16"
                       ::arms ["4" "4"]
                       ::legs ["7" "7"]
                       :foo "bar"
                       :name "john"}
                      {::sc/idents
                       {::head c/to-long
                        ::leg c/to-long
                        ::name c/to-keyword}}))
        "Coerce with option form")
    (is (= 1 (sc/coerce `string? "1" {::sc/idents {`string? c/to-long}}))
        "overrides works on qualified-idents")

    (is (= [1] (sc/coerce `(s/coll-of string?) ["1"]
                          {::sc/idents {`string? c/to-long}}))
        "overrides works on qualified-idents, also with composites")

    (is (= ["foo" "bar" "baz"]
           (sc/coerce `vector?
                      "foo,bar,baz"
                      {::sc/idents {`vector? (fn [x _] (str/split x #"[,]"))}}))
        "override on real world use case with vector?")))

(s/def ::foo int?)
(s/def ::bar string?)
(s/def ::qualified (s/keys :req [(or ::foo ::bar)]))
(s/def ::unqualified (s/keys :req-un [(or ::foo ::bar)]))

(deftest test-or-conditions-in-qualified-keys
  (is (= (sc/coerce ::qualified {::foo "1" ::bar "hi"})
         {::foo 1 ::bar "hi"})))

(deftest test-or-conditions-in-unqualified-keys
  (is (= (sc/coerce ::unqualified {:foo "1" :bar "hi"})
         {:foo 1 :bar "hi"})))

(s/def ::tuple (s/tuple ::foo ::bar int?))

(deftest test-tuple
  (is (= [0 "" 1] (sc/coerce ::tuple ["0" nil "1"])))
  (is (= "garbage" (sc/coerce ::tuple "garbage"))))

(deftest test-merge
  (s/def ::merge (s/merge (s/keys :req-un [::foo])
                          ::unqualified
                          ;; TODO: add s/multi-spec test
                          ))
  (is (= {:foo 1 :bar "1" :c {:a 2}}
         (sc/coerce ::merge {:foo "1" :bar 1 :c {:a 2}}))
      "Coerce new vals appropriately")
  (is (= {:foo 1 :bar "1" :c {:a 2}}
         (sc/coerce ::merge {:foo 1 :bar "1" :c {:a 2}}))
      "Leave out ok vals")

  (s/def ::merge2 (s/merge (s/keys :req [::foo])
                           ::unqualified))

  (is (= {::foo 1 :bar "1" :c {:a 2}
          :foo 1}
         (sc/coerce ::merge2 {::foo "1" :foo "1" :bar "1" :c {:a 2}}))
      "Leave out ok vals")

  (is (= "garbage" (sc/coerce ::merge "garbage"))
      "garbage is passthrough")

  (s/def ::x qualified-keyword?)
  (sc/def ::x (fn [x _] (keyword "y" x)))
  (s/def ::m1 (s/keys :opt [::x]))
  (s/def ::mm (s/merge ::m1 ::m1))
  (is (= {::x :y/quux}
         (sc/coerce ::mm
                    {::x "quux"}
                    {::sc/cache? false}))))

(def d :kw)
;; no vars in cljs
#?(:clj (defmulti multi #'d) :cljs (defmulti multi :kw))
(defmethod multi :default [_] (s/keys :req-un [::foo]))
(defmethod multi :kw [_] ::unqualified)
(s/def ::multi (s/multi-spec multi :hit))

(deftest test-multi-spec
  (is (= {:not "foo"} (sc/coerce ::multi {:not "foo"})))
  (is (= {:foo 1} (sc/coerce ::multi {:foo 1})))
  (is (= {:foo 1} (sc/coerce ::multi {:foo "1"})))
  (is (= {:foo 1 :d :kw} (sc/coerce ::multi {:d :kw :foo "1"})))
  (is (= "garbage" (sc/coerce ::multi "garbage"))))

(deftest test-gigo
  (is (= (sc/coerce `(some-unknown-form string?) 1) 1))
  (is (= (sc/coerce `(some-unknown-form) 1) 1)))

(deftest invalidity-test
  (is (= :exoscale.coax/invalid (sc/coerce* `int? [] {})))
  (is (= :exoscale.coax/invalid (sc/coerce* `(s/coll-of int?) 1 {})))
  (is (= :exoscale.coax/invalid (sc/coerce* ::int-set "" {}))))


(deftest test-caching
  (s/def ::bs (s/keys :req [::bool]))
  (is (= false (sc/coerce ::bool "false")))
  (is (= false (::bool (sc/coerce ::bs {::bool "false"}))))
  (is (= false (sc/coerce ::bool
                          "false"
                          {:exoscale.coax/cache? false})))
  (is (= false (::bool (sc/coerce ::bs
                                  {::bool "false"}
                                  {:exoscale.coax/cache? false})))))
