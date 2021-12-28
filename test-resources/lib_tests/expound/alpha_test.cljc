(ns expound.alpha-test
  (:require #?@(:clj
                ;; just to include the specs
                [[clojure.core.specs.alpha]
                 [ring.core.spec]
                 [onyx.spec]])

            ;; Deps for specs that generate specs, which are currently disabled
            #_[clojure.test.check.random :as random]
            #_[clojure.test.check.rose-tree :as rose]

            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [clojure.string :as string]
            [clojure.test :as ct :refer [is testing deftest use-fixtures]]
            [clojure.test.check.generators :as gen]

            [clojure.walk :as walk]
            [com.gfredericks.test.chuck :as chuck]
            [com.gfredericks.test.chuck.clojure-test :refer [checking]]
            [expound.alpha :as expound]
            [expound.ansi :as ansi]
            [expound.printer :as printer]
            [expound.problems :as problems]
            [expound.spec-gen :as sg]
            [expound.test-utils :as test-utils]
            [spec-tools.data-spec :as ds]
            #?(:clj [orchestra.spec.test :as orch.st]
               :cljs [orchestra-cljs.spec.test :as orch.st])))

;;;; override specs and add generators
;;;; this allows us to load expound with babaska and spartan.spec
(s/def :expound.printer/value-str-fn (s/with-gen ifn?
                                       #(gen/return (fn [_ _ _ _] "NOT IMPLEMENTED"))))

(s/def :expound.spec/spec (s/or
                           :set set?
                           :pred (s/with-gen ifn?
                                   #(gen/elements [boolean? string? int? keyword? symbol?]))
                           :kw qualified-keyword?
                           :spec (s/with-gen s/spec?
                                   #(gen/elements
                                     (for [pr [boolean? string? int? keyword? symbol?]]
                                       (s/spec pr))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def num-tests 5)

(use-fixtures :once
  test-utils/check-spec-assertions
  test-utils/instrument-all)

;; Missing onyx specs
(s/def :trigger/materialize any?)
(s/def :flow/short-circuit any?)

(defn pf
  "Fixes platform-specific namespaces and also formats using printf syntax"
  [s & args]
  (apply printer/format
         #?(:cljs (string/replace s "pf." "cljs.")
            :clj (string/replace s "pf." "clojure."))
         args))

(defn take-lines [n s]
  (string/join "\n" (take n (string/split-lines s))))

(defn formatted-exception [printer-options f]
  (let [printer (expound/custom-printer printer-options)
        exception-data (binding [s/*explain-out* printer]
                         (try
                           (f)
                           (catch #?(:cljs :default :clj Exception)
                                  e
                             #?(:cljs {:message (.-message e)
                                       :data (.-data e)}

                                :clj (Throwable->map e)))))
        ed #?(:cljs (-> exception-data :data)
              :clj (-> exception-data :via last :data))
        cause# (-> #?(:cljs (:message exception-data)
                      :clj (:cause exception-data))
                   (clojure.string/replace #"Call to (.*) did not conform to spec:"
                                           "Call to #'$1 did not conform to spec."))]

    (str cause#
         (if (re-find  #"Detected \d+ error" cause#)
           ""
           (str "\n"
                (with-out-str (printer ed)))))))

(defn orch-unstrument-test-fns [f]
  (orch.st/unstrument [`results-str-fn1
                       `results-str-fn2
                       `results-str-fn4
                       `results-str-fn7])
  (f))

(def inverted-ansi-codes
  (reduce
   (fn [m [k v]]
     (assoc m (str v) k))
   {}
   ansi/sgr-code))

(defn readable-ansi [s]
  (string/replace
   s
   #"\x1b\[([0-9]*)m"
   #(str "<" (string/upper-case (name (get inverted-ansi-codes (second %)))) ">")))

;; https://github.com/bhb/expound/issues/8
(deftest expound-output-ends-in-newline
  (is (= "\n" (str (last (expound/expound-str string? 1)))))
  (is (= "\n" (str (last (expound/expound-str string? ""))))))

(deftest expound-prints-expound-str
  (is (=
       (expound/expound-str string? 1)
       (with-out-str (expound/expound string? 1)))))

(deftest predicate-spec
  (is (= (pf "-- Spec failed --------------------

  1

should satisfy

  string?

-------------------------
Detected 1 error\n")
         (expound/expound-str string? 1))))

(s/def :simple-type-based-spec/str string?)

(deftest simple-type-based-spec
  (testing "valid value"
    (is (= "Success!\n"
           (expound/expound-str :simple-type-based-spec/str ""))))

  (testing "invalid value"
    (is (=
         (pf "-- Spec failed --------------------

  1

should satisfy

  string?

-- Relevant specs -------

:simple-type-based-spec/str:
  pf.core/string?

-------------------------
Detected 1 error\n")
         (expound/expound-str :simple-type-based-spec/str 1)))))

(s/def :set-based-spec/tag #{:foo :bar})
(s/def :set-based-spec/nilable-tag (s/nilable :set-based-spec/tag))
(s/def :set-based-spec/set-of-one #{:foobar})

(s/def :set-based-spec/one-or-two (s/or
                                   :one (s/cat :a #{:one})
                                   :two (s/cat :b #{:two})))

(deftest set-based-spec
  (testing "prints valid options"
    (is (= "-- Spec failed --------------------

  :baz

should be one of: :bar, :foo

-- Relevant specs -------

:set-based-spec/tag:
  #{:bar :foo}

-------------------------
Detected 1 error\n"
           (expound/expound-str :set-based-spec/tag :baz))))

  (testing "prints combined options for various specs"
    (is (= (pf "-- Spec failed --------------------

  [:three]
   ^^^^^^

should be one of: :one, :two

-- Relevant specs -------

:set-based-spec/one-or-two:
  (pf.spec.alpha/or
   :one
   (pf.spec.alpha/cat :a #{:one})
   :two
   (pf.spec.alpha/cat :b #{:two}))

-------------------------
Detected 1 error\n")
           (expound/expound-str :set-based-spec/one-or-two [:three]))))

  (testing "nilable version"
    (is (= (pf "-- Spec failed --------------------

  :baz

should be one of: :bar, :foo

or

should satisfy

  nil?

-- Relevant specs -------

:set-based-spec/tag:
  #{:bar :foo}
:set-based-spec/nilable-tag:
  (pf.spec.alpha/nilable :set-based-spec/tag)

-------------------------
Detected 1 error\n")
           (expound/expound-str :set-based-spec/nilable-tag :baz))))
  (testing "single element spec"
    (is (= (pf "-- Spec failed --------------------

  :baz

should be: :foobar

-- Relevant specs -------

:set-based-spec/set-of-one:
  #{:foobar}

-------------------------
Detected 1 error\n")
           (expound/expound-str :set-based-spec/set-of-one :baz)))))

(s/def :nested-type-based-spec/str string?)
(s/def :nested-type-based-spec/strs (s/coll-of :nested-type-based-spec/str))

(deftest nested-type-based-spec
  (is (=
       (pf "-- Spec failed --------------------

  [... ... 33]
           ^^

should satisfy

  string?

-- Relevant specs -------

:nested-type-based-spec/str:
  pf.core/string?
:nested-type-based-spec/strs:
  (pf.spec.alpha/coll-of :nested-type-based-spec/str)

-------------------------
Detected 1 error\n")
       (expound/expound-str :nested-type-based-spec/strs ["one" "two" 33]))))

(s/def :nested-type-based-spec-special-summary-string/int int?)
(s/def :nested-type-based-spec-special-summary-string/ints (s/coll-of :nested-type-based-spec-special-summary-string/int))

(deftest nested-type-based-spec-special-summary-string
  (is (=
       (pf "-- Spec failed --------------------

  [... ... \"...\"]
           ^^^^^

should satisfy

  int?

-- Relevant specs -------

:nested-type-based-spec-special-summary-string/int:
  pf.core/int?
:nested-type-based-spec-special-summary-string/ints:
  (pf.spec.alpha/coll-of
   :nested-type-based-spec-special-summary-string/int)

-------------------------
Detected 1 error\n")
       (expound/expound-str :nested-type-based-spec-special-summary-string/ints [1 2 "..."]))))

(s/def :or-spec/str-or-int (s/or :int int? :str string?))
(s/def :or-spec/vals (s/coll-of :or-spec/str-or-int))

(s/def :or-spec/str string?)
(s/def :or-spec/int int?)
(s/def :or-spec/m-with-str (s/keys :req [:or-spec/str]))
(s/def :or-spec/m-with-int (s/keys :req [:or-spec/int]))
(s/def :or-spec/m-with-str-or-int (s/or :m-with-str :or-spec/m-with-str
                                        :m-with-int :or-spec/m-with-int))

(deftest or-spec
  (testing "simple value"
    (is (= (pf "-- Spec failed --------------------

  :kw

should satisfy

  int?

or

  string?

-- Relevant specs -------

:or-spec/str-or-int:
  (pf.spec.alpha/or :int pf.core/int? :str pf.core/string?)

-------------------------
Detected 1 error\n")
           (expound/expound-str :or-spec/str-or-int :kw))))
  (testing "collection of values"
    (is (= (pf "-- Spec failed --------------------

  [... ... :kw ...]
           ^^^

should satisfy

  int?

or

  string?

-- Relevant specs -------

:or-spec/str-or-int:
  (pf.spec.alpha/or :int pf.core/int? :str pf.core/string?)
:or-spec/vals:
  (pf.spec.alpha/coll-of :or-spec/str-or-int)

-------------------------
Detected 1 error\n")
           (expound/expound-str :or-spec/vals [0 "hi" :kw "bye"]))))
  (is (= "-- Spec failed --------------------

  50

should satisfy

  coll?

-------------------------
Detected 1 error
"
         (expound/expound-str (s/or
                               :strs (s/coll-of string?)
                               :ints (s/coll-of int?))
                              50)))
  (is (= "-- Spec failed --------------------

  50

should be one of: \"a\", \"b\", 1, 2

-------------------------
Detected 1 error
"
         (expound/expound-str
          (s/or
           :letters #{"a" "b"}
           :ints #{1 2})
          50)))
  (is (= (pf "-- Spec failed --------------------

  {}

should contain keys: :or-spec/int, :or-spec/str

| key          | spec    |
|==============+=========|
| :or-spec/int | int?    |
|--------------+---------|
| :or-spec/str | string? |

-- Relevant specs -------

:or-spec/m-with-int:
  (pf.spec.alpha/keys :req [:or-spec/int])
:or-spec/m-with-str:
  (pf.spec.alpha/keys :req [:or-spec/str])
:or-spec/m-with-str-or-int:
  (pf.spec.alpha/or
   :m-with-str
   :or-spec/m-with-str
   :m-with-int
   :or-spec/m-with-int)

-------------------------
Detected 1 error
")
         (expound/expound-str :or-spec/m-with-str-or-int {})))
  (testing "de-dupes keys"
    (is (= "-- Spec failed --------------------

  {}

should contain keys: :or-spec/str

| key          | spec    |
|==============+=========|
| :or-spec/str | string? |

-------------------------
Detected 1 error
"
           (expound/expound-str (s/or :m-with-str1 (s/keys :req [:or-spec/str])
                                      :m-with-int2 (s/keys :req [:or-spec/str])) {})))))

(s/def :and-spec/name (s/and string? #(pos? (count %))))
(s/def :and-spec/names (s/coll-of :and-spec/name))
(deftest and-spec
  (testing "simple value"
    (is (= (pf "-- Spec failed --------------------

  \"\"

should satisfy

  (fn [%%] (pos? (count %%)))

-- Relevant specs -------

:and-spec/name:
  (pf.spec.alpha/and
   pf.core/string?
   (pf.core/fn [%%] (pf.core/pos? (pf.core/count %%))))

-------------------------
Detected 1 error\n")
           (expound/expound-str :and-spec/name ""))))

  (testing "shows both failures in order"
    (is (=
         (pf "-- Spec failed --------------------

  [... ... \"\" ...]
           ^^

should satisfy

  %s

-- Relevant specs -------

:and-spec/name:
  (pf.spec.alpha/and
   pf.core/string?
   (pf.core/fn [%%] (pf.core/pos? (pf.core/count %%))))
:and-spec/names:
  (pf.spec.alpha/coll-of :and-spec/name)

-- Spec failed --------------------

  [... ... ... 1]
               ^

should satisfy

  string?

-- Relevant specs -------

:and-spec/name:
  (pf.spec.alpha/and
   pf.core/string?
   (pf.core/fn [%%] (pf.core/pos? (pf.core/count %%))))
:and-spec/names:
  (pf.spec.alpha/coll-of :and-spec/name)

-------------------------
Detected 2 errors\n"
             #?(:cljs "(fn [%] (pos? (count %)))"
                :clj "(fn [%] (pos? (count %)))"))
         (expound/expound-str :and-spec/names ["bob" "sally" "" 1])))))

(s/def :coll-of-spec/big-int-coll (s/coll-of int? :min-count 10))

(deftest coll-of-spec
  (testing "min count"
    (is (=
         (pf "-- Spec failed --------------------

  []

should satisfy

  (<= 10 (count %%) %s)

-- Relevant specs -------

:coll-of-spec/big-int-coll:
  (pf.spec.alpha/coll-of pf.core/int? :min-count 10)

-------------------------
Detected 1 error\n"
             #?(:cljs "9007199254740991"
                :clj "Integer/MAX_VALUE"))
         (expound/expound-str :coll-of-spec/big-int-coll [])))))

(s/def :cat-spec/kw (s/cat :k keyword? :v any?))
(s/def :cat-spec/set (s/cat :type #{:foo :bar} :str string?))
(s/def :cat-spec/alt* (s/alt :s string? :i int?))
(s/def :cat-spec/alt (s/+ :cat-spec/alt*))
(s/def :cat-spec/alt-inline (s/+ (s/alt :s string? :i int?)))
(s/def :cat-spec/any (s/cat :x (s/+ any?))) ;; Not a useful spec, but worth testing
(deftest cat-spec
  (testing "too few elements"
    (is (= (pf "-- Syntax error -------------------

  []

should have additional elements. The next element \":k\" should satisfy

  keyword?

-- Relevant specs -------

:cat-spec/kw:
  (pf.spec.alpha/cat :k pf.core/keyword? :v pf.core/any?)

-------------------------
Detected 1 error\n")
           (expound/expound-str :cat-spec/kw [])))
    (is (= (pf "-- Syntax error -------------------

  []

should have additional elements. The next element \":type\" should be one of: :bar, :foo

-- Relevant specs -------

:cat-spec/set:
  (pf.spec.alpha/cat :type #{:bar :foo} :str pf.core/string?)

-------------------------
Detected 1 error\n")
           (expound/expound-str :cat-spec/set [])))
    (is (= (pf "-- Syntax error -------------------

  [:foo]

should have additional elements. The next element \":v\" should satisfy

  any?

-- Relevant specs -------

:cat-spec/kw:
  (pf.spec.alpha/cat :k pf.core/keyword? :v pf.core/any?)

-------------------------
Detected 1 error\n")
           (expound/expound-str :cat-spec/kw [:foo])))
    ;; This isn't ideal, but requires a fix from clojure
    ;; https://clojure.atlassian.net/browse/CLJ-2364
    (is (= (pf "-- Syntax error -------------------

  []

should have additional elements. The next element should satisfy

  (pf.spec.alpha/alt :s string? :i int?)

-- Relevant specs -------

:cat-spec/alt*:
  (pf.spec.alpha/alt :s pf.core/string? :i pf.core/int?)
:cat-spec/alt:
  (pf.spec.alpha/+ :cat-spec/alt*)

-------------------------
Detected 1 error\n")
           (expound/expound-str :cat-spec/alt [])))
    (is (= (pf "-- Syntax error -------------------

  []

should have additional elements. The next element should satisfy

  (pf.spec.alpha/alt :s string? :i int?)

-- Relevant specs -------

:cat-spec/alt-inline:
  (pf.spec.alpha/+
   (pf.spec.alpha/alt :s pf.core/string? :i pf.core/int?))

-------------------------
Detected 1 error\n")
           (expound/expound-str :cat-spec/alt-inline [])))
    (is (= (pf "-- Syntax error -------------------

  []

should have additional elements. The next element \":x\" should satisfy

  any?

-- Relevant specs -------

:cat-spec/any:
  (pf.spec.alpha/cat :x (pf.spec.alpha/+ pf.core/any?))

-------------------------
Detected 1 error\n")
           (expound/expound-str :cat-spec/any []))))
  (testing "too many elements"
    (is (= (pf "-- Syntax error -------------------

  [... ... :bar ...]
           ^^^^

has extra input

-- Relevant specs -------

:cat-spec/kw:
  (pf.spec.alpha/cat :k pf.core/keyword? :v pf.core/any?)

-------------------------
Detected 1 error\n")
           (expound/expound-str :cat-spec/kw [:foo 1 :bar :baz])))))

(s/def :keys-spec/name string?)
(s/def :keys-spec/age int?)
(s/def :keys-spec/user (s/keys :req [:keys-spec/name]
                               :req-un [:keys-spec/age]))

(s/def :key-spec/state string?)
(s/def :key-spec/city string?)
(s/def :key-spec/zip pos-int?)

(s/def :keys-spec/user2 (s/keys :req [(and :keys-spec/name
                                           :keys-spec/age)]
                                :req-un [(or
                                          :key-spec/zip
                                          (and
                                           :key-spec/state
                                           :key-spec/city))]))

(s/def :keys-spec/user3 (s/keys :req-un [(or
                                          :key-spec/zip
                                          (and
                                           :key-spec/state
                                           :key-spec/city))]))

(s/def :keys-spec/user4 (s/keys :req []))

(defmulti key-spec-mspec :tag)
(defmethod key-spec-mspec :int [_] (s/keys :req-un [::tag ::i]))
(defmethod key-spec-mspec :string [_] (s/keys :req-un [::tag ::s]))
(deftest keys-spec
  (testing "missing keys"
    (is (= (pf "-- Spec failed --------------------

  {}

should contain keys: :age, :keys-spec/name

| key             | spec    |
|=================+=========|
| :age            | int?    |
|-----------------+---------|
| :keys-spec/name | string? |

-- Relevant specs -------

:keys-spec/user:
  %s

-------------------------
Detected 1 error\n"
               #?(:cljs "(cljs.spec.alpha/keys :req [:keys-spec/name] :req-un [:keys-spec/age])"
                  :clj "(clojure.spec.alpha/keys\n   :req\n   [:keys-spec/name]\n   :req-un\n   [:keys-spec/age])"))
           (expound/expound-str :keys-spec/user {}))))
  (testing "missing compound keys"
    (is (= (pf "-- Spec failed --------------------

  {}

should contain keys:

(and (and :keys-spec/name :keys-spec/age) (or :zip (and :state :city)))

| key             | spec     |
|=================+==========|
| :city           | string?  |
|-----------------+----------|
| :state          | string?  |
|-----------------+----------|
| :zip            | pos-int? |
|-----------------+----------|
| :keys-spec/age  | int?     |
|-----------------+----------|
| :keys-spec/name | string?  |

-- Relevant specs -------

:keys-spec/user2:
  (pf.spec.alpha/keys
   :req
   [(and :keys-spec/name :keys-spec/age)]
   :req-un
   [(or :key-spec/zip (and :key-spec/state :key-spec/city))])

-------------------------
Detected 1 error\n")
           (expound/expound-str :keys-spec/user2 {})))
    (is (= (pf "-- Spec failed --------------------

  {}

should contain keys:

(or :zip (and :state :city))

| key    | spec     |
|========+==========|
| :city  | string?  |
|--------+----------|
| :state | string?  |
|--------+----------|
| :zip   | pos-int? |

-- Relevant specs -------

:keys-spec/user3:
  (pf.spec.alpha/keys
   :req-un
   [(or :key-spec/zip (and :key-spec/state :key-spec/city))])

-------------------------
Detected 1 error\n")
           (expound/expound-str :keys-spec/user3 {}))))

  (testing "inline spec with req-un"
    (is (= (pf "-- Spec failed --------------------

  {}

should contain keys: :age, :name

| key   | spec    |
|=======+=========|
| :age  | int?    |
|-------+---------|
| :name | string? |

-------------------------
Detected 1 error\n"
               #?(:cljs "(cljs.spec.alpha/keys :req [:keys-spec/name] :req-un [:keys-spec/age])"
                  :clj "(clojure.spec.alpha/keys\n   :req\n   [:keys-spec/name]\n   :req-un\n   [:keys-spec/age])"))
           (expound/expound-str (s/keys :req-un [:keys-spec/name :keys-spec/age]) {})))
    (s/def :key-spec/mspec (s/multi-spec key-spec-mspec :tag))
    (s/def :key-spec/i int?)
    (s/def :key-spec/s string?)
    ;; We can't inspect the contents of a multi-spec (to figure out
    ;; which spec we mean by :i), so this is the best we can do.
    (is (= "-- Spec failed --------------------

  {:tag :int}

should contain key: :i

| key | spec                                              |
|=====+===================================================|
| :i  | <can't find spec for unqualified spec identifier> |

-------------------------
Detected 1 error\n"
           (expound/expound-str
            :key-spec/mspec
            {:tag :int}
            {:print-specs? false}))))

  (testing "invalid key"
    (is (= (pf "-- Spec failed --------------------

  {:age ..., :keys-spec/name :bob}
                             ^^^^

should satisfy

  string?

-- Relevant specs -------

:keys-spec/name:
  pf.core/string?
:keys-spec/user:
  %s

-------------------------
Detected 1 error\n"
               #?(:cljs "(cljs.spec.alpha/keys :req [:keys-spec/name] :req-un [:keys-spec/age])"
                  :clj "(clojure.spec.alpha/keys\n   :req\n   [:keys-spec/name]\n   :req-un\n   [:keys-spec/age])"))
           (expound/expound-str :keys-spec/user {:age 1 :keys-spec/name :bob}))))
  (testing "contains compound specs"
    (s/def :keys-spec/states (s/coll-of :key-spec/state :kind vector?))
    (s/def :keys-spec/address (s/keys :req [:key-spec/city :key-space/state]))
    (s/def :keys-spec/cities (s/coll-of :key-spec/city :kind set?))
    (s/def :keys-spec/locations (s/keys :req-un [:keys-spec/states
                                                 :keys-spec/address
                                                 :keys-spec/locations]))
    (is (=
         "-- Spec failed --------------------

  {}

should contain keys: :address, :locations, :states

| key        | spec                                                          |
|============+===============================================================|
| :address   | (keys :req [:key-spec/city :key-space/state])                 |
|------------+---------------------------------------------------------------|
| :locations | (keys                                                         |
|            |  :req-un                                                      |
|            |  [:keys-spec/states :keys-spec/address :keys-spec/locations]) |
|------------+---------------------------------------------------------------|
| :states    | (coll-of :key-spec/state :kind vector?)                       |

-------------------------
Detected 1 error
"
         (expound/expound-str :keys-spec/locations {} {:print-specs? false})))))

(s/def :keys-spec/foo string?)
(s/def :keys-spec/bar string?)
(s/def :keys-spec/baz string?)
(s/def :keys-spec/qux (s/or :string string?
                            :int int?))
(s/def :keys-spec/child-1 (s/keys :req-un [:keys-spec/baz :keys-spec/qux]))
(s/def :keys-spec/child-2 (s/keys :req-un [:keys-spec/bar :keys-spec/child-1]))

(s/def :keys-spec/map-spec-1 (s/keys :req-un [:keys-spec/foo
                                              :keys-spec/bar
                                              :keys-spec/baz]))
(s/def :keys-spec/map-spec-2 (s/keys :req-un [:keys-spec/foo
                                              :keys-spec/bar
                                              :keys-spec/qux]))
(s/def :keys-spec/map-spec-3 (s/keys :req-un [:keys-spec/foo
                                              :keys-spec/child-2]))

(deftest grouping-and-key-specs
  (is (= (pf
          "-- Spec failed --------------------

  {:foo 1.2, :bar ..., :baz ...}
        ^^^

should satisfy

  string?

-- Spec failed --------------------

  {:foo ..., :bar 123, :baz ...}
                  ^^^

should satisfy

  string?

-- Spec failed --------------------

  {:foo ..., :bar ..., :baz true}
                            ^^^^

should satisfy

  string?

-------------------------
Detected 3 errors\n")
         (expound/expound-str :keys-spec/map-spec-1 {:foo 1.2
                                                     :bar 123
                                                     :baz true}
                              {:print-specs? false})))
  (is (= (pf
          "-- Spec failed --------------------

  {:foo 1.2, :bar ..., :qux ...}
        ^^^

should satisfy

  string?

-- Spec failed --------------------

  {:foo ..., :bar 123, :qux ...}
                  ^^^

should satisfy

  string?

-- Spec failed --------------------

  {:foo ..., :bar ..., :qux false}
                            ^^^^^

should satisfy

  string?

or

  int?

-------------------------
Detected 3 errors\n")
         (expound/expound-str :keys-spec/map-spec-2 {:foo 1.2
                                                     :bar 123
                                                     :qux false}
                              {:print-specs? false})))

  (is (=
       "-- Spec failed --------------------

  {:foo 1.2, :child-2 ...}
        ^^^

should satisfy

  string?

-- Spec failed --------------------

  {:foo ..., :child-2 {:bar 123, :child-1 ...}}
                            ^^^

should satisfy

  string?

-- Spec failed --------------------

  {:foo ...,
   :child-2
   {:bar ..., :child-1 {:baz true, :qux ...}}}
                             ^^^^

should satisfy

  string?

-- Spec failed --------------------

  {:foo ...,
   :child-2
   {:bar ..., :child-1 {:baz ..., :qux false}}}
                                       ^^^^^

should satisfy

  string?

or

  int?

-------------------------
Detected 4 errors\n"
       (expound/expound-str :keys-spec/map-spec-3 {:foo 1.2
                                                   :child-2 {:bar 123
                                                             :child-1 {:baz true
                                                                       :qux false}}}
                            {:print-specs? false}))))

(s/def :multi-spec/value string?)
(s/def :multi-spec/children vector?)
(defmulti el-type :multi-spec/el-type)
(defmethod el-type :text [_x]
  (s/keys :req [:multi-spec/value]))
(defmethod el-type :group [_x]
  (s/keys :req [:multi-spec/children]))
(s/def :multi-spec/el (s/multi-spec el-type :multi-spec/el-type))

(defmulti multi-spec-bar-spec :type)
(defmethod multi-spec-bar-spec ::b [_] (s/keys :req [::b]))
(deftest multi-spec
  (testing "missing dispatch key"
    (is (=
         (pf "-- Missing spec -------------------

Cannot find spec for

  {}

with

 Spec multimethod:      `expound.alpha-test/el-type`
 Dispatch value:        `nil`

-- Relevant specs -------

:multi-spec/el:
  (pf.spec.alpha/multi-spec
   expound.alpha-test/el-type
   :multi-spec/el-type)

-------------------------
Detected 1 error\n")
         (expound/expound-str :multi-spec/el {}))))
  (testing "invalid dispatch value"
    (is (=
         (pf "-- Missing spec -------------------

Cannot find spec for

  {:multi-spec/el-type :image}

with

 Spec multimethod:      `expound.alpha-test/el-type`
 Dispatch value:        `:image`

-- Relevant specs -------

:multi-spec/el:
  (pf.spec.alpha/multi-spec
   expound.alpha-test/el-type
   :multi-spec/el-type)

-------------------------
Detected 1 error\n")
         (expound/expound-str :multi-spec/el {:multi-spec/el-type :image}))))

  (testing "valid dispatch value, but other error"
    (is (=
         (pf "-- Spec failed --------------------

  {:multi-spec/el-type :text}

should contain key: :multi-spec/value

| key               | spec    |
|===================+=========|
| :multi-spec/value | string? |

-- Relevant specs -------

:multi-spec/el:
  (pf.spec.alpha/multi-spec
   expound.alpha-test/el-type
   :multi-spec/el-type)

-------------------------
Detected 1 error\n")
         (expound/expound-str :multi-spec/el {:multi-spec/el-type :text}))))

  ;; https://github.com/bhb/expound/issues/122
  (testing "when re-tag is a function"
    (s/def :multi-spec/b string?)
    (s/def :multi-spec/bar (s/multi-spec multi-spec-bar-spec (fn [val tag] (assoc val :type tag))))
    (is (= "-- Missing spec -------------------

Cannot find spec for

  {}

with

 Spec multimethod:      `expound.alpha-test/multi-spec-bar-spec`
 Dispatch value:        `nil`

-------------------------
Detected 1 error
"
           (expound/expound-str :multi-spec/bar {} {:print-specs? false})))))

(s/def :recursive-spec/tag #{:text :group})
(s/def :recursive-spec/on-tap (s/coll-of map? :kind vector?))
(s/def :recursive-spec/props (s/keys :opt-un [:recursive-spec/on-tap]))
(s/def :recursive-spec/el (s/keys :req-un [:recursive-spec/tag]
                                  :opt-un [:recursive-spec/props :recursive-spec/children]))
(s/def :recursive-spec/children (s/coll-of (s/nilable :recursive-spec/el) :kind vector?))

(s/def :recursive-spec/tag-2 (s/or :text (fn [n] (= n :text))
                                   :group (fn [n] (= n :group))))
(s/def :recursive-spec/on-tap-2 (s/coll-of map? :kind vector?))
(s/def :recursive-spec/props-2 (s/keys :opt-un [:recursive-spec/on-tap-2]))
(s/def :recursive-spec/el-2 (s/keys :req-un [:recursive-spec/tag-2]
                                    :opt-un [:recursive-spec/props-2
                                             :recursive-spec/children-2]))
(s/def :recursive-spec/children-2 (s/coll-of (s/nilable :recursive-spec/el-2) :kind vector?))

(deftest recursive-spec
  (testing "only shows problem with data at 'leaves' (not problems with all parents in tree)"
    (is (= (pf
            "-- Spec failed --------------------

  {:tag ..., :children [{:tag :group, :children [{:tag :group, :props {:on-tap {}}}]}]}
                        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

should satisfy

  nil?

or value

  {:tag ...,
   :children [{:tag ..., :children [{:tag :group, :props {:on-tap {}}}]}]}
                                    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

should satisfy

  nil?

or value

  {:tag ...,
   :children
   [{:tag ...,
     :children
     [{:tag ..., :props {:on-tap {}}}]}]}
                                 ^^

should satisfy

  vector?

-------------------------
Detected 1 error\n")
           (expound/expound-str
            :recursive-spec/el
            {:tag :group
             :children [{:tag :group
                         :children [{:tag :group
                                     :props {:on-tap {}}}]}]}
            {:print-specs? false}))))
  (testing "test that our new recursive spec grouping function works with
           alternative paths"
    (is (= (pf
            "-- Spec failed --------------------

  {:tag-2 ..., :children-2 [{:tag-2 :group, :children-2 [{:tag-2 :group, :props-2 {:on-tap-2 {}}}]}]}
                            ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

should satisfy

  nil?

or value

  {:tag-2 ...,
   :children-2 [{:tag-2 ..., :children-2 [{:tag-2 :group, :props-2 {:on-tap-2 {}}}]}]}
                                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

should satisfy

  nil?

or value

  {:tag-2 ...,
   :children-2
   [{:tag-2 ...,
     :children-2
     [{:tag-2 ..., :props-2 {:on-tap-2 {}}}]}]}
                                       ^^

should satisfy

  vector?

-------------------------
Detected 1 error\n")
           (expound/expound-str
            :recursive-spec/el-2
            {:tag-2 :group
             :children-2 [{:tag-2 :group
                           :children-2 [{:tag-2 :group
                                         :props-2 {:on-tap-2 {}}}]}]}
            {:print-specs? false})))))

(s/def :cat-wrapped-in-or-spec/kv (s/and
                                   sequential?
                                   (s/cat :k keyword? :v any?)))
(s/def :cat-wrapped-in-or-spec/type #{:text})
(s/def :cat-wrapped-in-or-spec/kv-or-string (s/or
                                             :map (s/keys :req [:cat-wrapped-in-or-spec/type])
                                             :kv :cat-wrapped-in-or-spec/kv))

(deftest cat-wrapped-in-or-spec
  (is (= (pf "-- Spec failed --------------------

  {\"foo\" \"hi\"}

should contain key: :cat-wrapped-in-or-spec/type

| key                          | spec     |
|==============================+==========|
| :cat-wrapped-in-or-spec/type | #{:text} |

or

should satisfy

  sequential?

-- Relevant specs -------

:cat-wrapped-in-or-spec/kv:
  (pf.spec.alpha/and
   pf.core/sequential?
   (pf.spec.alpha/cat :k pf.core/keyword? :v pf.core/any?))
:cat-wrapped-in-or-spec/kv-or-string:
  (pf.spec.alpha/or
   :map
   (pf.spec.alpha/keys :req [:cat-wrapped-in-or-spec/type])
   :kv
   :cat-wrapped-in-or-spec/kv)

-------------------------
Detected 1 error\n")
         (expound/expound-str :cat-wrapped-in-or-spec/kv-or-string {"foo" "hi"}))))

(s/def :map-of-spec/name string?)
(s/def :map-of-spec/age pos-int?)
(s/def :map-of-spec/name->age (s/map-of :map-of-spec/name :map-of-spec/age))
(deftest map-of-spec
  (is (= (pf "-- Spec failed --------------------

  {\"Sally\" \"30\"}
           ^^^^

should satisfy

  pos-int?

-- Relevant specs -------

:map-of-spec/age:
  pf.core/pos-int?
:map-of-spec/name->age:
  (pf.spec.alpha/map-of :map-of-spec/name :map-of-spec/age)

-------------------------
Detected 1 error\n")
         (expound/expound-str :map-of-spec/name->age {"Sally" "30"})))
  (is (= (pf "-- Spec failed --------------------

  {:sally ...}
   ^^^^^^

should satisfy

  string?

-- Relevant specs -------

:map-of-spec/name:
  pf.core/string?
:map-of-spec/name->age:
  (pf.spec.alpha/map-of :map-of-spec/name :map-of-spec/age)

-------------------------
Detected 1 error\n")
         (expound/expound-str :map-of-spec/name->age {:sally 30}))))

(deftest generated-simple-spec
  (checking
   "simple spec"
   (chuck/times num-tests)
   [simple-spec sg/simple-spec-gen
    form gen/any-printable]
   (is (string? (expound/expound-str simple-spec form)))))

(deftest generated-coll-of-specs
  (checking
   "'coll-of' spec"
   (chuck/times num-tests)
   [simple-spec sg/simple-spec-gen
    every-args (s/gen :specs/every-args)
    :let [spec (sg/apply-coll-of simple-spec every-args)]
    form gen/any-printable]
   (is (string? (expound/expound-str spec form)))))

(deftest generated-and-specs
  (checking
   "'and' spec"
   (chuck/times num-tests)
   [simple-spec1 sg/simple-spec-gen
    simple-spec2 sg/simple-spec-gen
    :let [spec (s/and simple-spec1 simple-spec2)]
    form gen/any-printable]
   (is (string? (expound/expound-str spec form)))))

(deftest generated-or-specs
  (checking
   "'or' spec generates string"
   (chuck/times num-tests)
   [simple-spec1 sg/simple-spec-gen
    simple-spec2 sg/simple-spec-gen
    :let [spec (s/or :or1 simple-spec1 :or2 simple-spec2)]
    form gen/any-printable]
   (is (string? (expound/expound-str spec form))))
  (checking
   "nested 'or' spec reports on all problems"
   (chuck/times num-tests)
   [simple-specs (gen/vector-distinct
                  (gen/elements [:specs/string
                                 :specs/vector
                                 :specs/int
                                 :specs/boolean
                                 :specs/keyword
                                 :specs/map
                                 :specs/symbol
                                 :specs/pos-int
                                 :specs/neg-int
                                 :specs/zero])
                  {:num-elements 4})
    :let [[simple-spec1
           simple-spec2
           simple-spec3
           simple-spec4] simple-specs
          spec (s/or :or1
                     (s/or :or1.1
                           simple-spec1
                           :or1.2
                           simple-spec2)
                     :or2
                     (s/or :or2.1
                           simple-spec3
                           :or2.2
                           simple-spec4))
          sp-form (s/form spec)]
    form gen/any-printable]
   (let [ed (s/explain-data spec form)]
     (when-not (zero? (count (::s/problems ed)))
       (is (= (dec (count (::s/problems ed)))
              (count (re-seq #"\nor\n" (expound/expound-str spec form))))
           (str "Failed to print out all problems\nspec: " sp-form "\nproblems: " (printer/pprint-str (::s/problems ed)) "\nmessage: " (expound/expound-str spec form)))))))

(deftest generated-map-of-specs
  (checking
   "'map-of' spec"
   (chuck/times num-tests)
   [simple-spec1 sg/simple-spec-gen
    simple-spec2 sg/simple-spec-gen
    simple-spec3 sg/simple-spec-gen
    every-args1 (s/gen :specs/every-args)
    every-args2 (s/gen :specs/every-args)
    :let [spec (sg/apply-map-of simple-spec1 (sg/apply-map-of simple-spec2 simple-spec3 every-args1) every-args2)]
    form test-utils/any-printable-wo-nan]
   (is (string? (expound/expound-str spec form)))))

(s/def :expound.ds/spec-key (s/or :kw keyword?
                                  :req (s/tuple
                                        #{:expound.ds/req-key}
                                        (s/map-of
                                         #{:k}
                                         keyword?
                                         :count 1))
                                  :opt (s/tuple
                                        #{:expound.ds/opt-key}
                                        (s/map-of
                                         #{:k}
                                         keyword?
                                         :count 1))))

(defn real-spec [form]
  (walk/prewalk
   (fn [x]
     (if (vector? x)
       (case (first x)
         :expound.ds/opt-key
         (ds/map->OptionalKey (second x))

         :expound.ds/req-key
         (ds/map->RequiredKey (second x))

         :expound.ds/maybe-spec
         (ds/maybe (second x))

         x)
       x))
   form))

(s/def :expound.ds/maybe-spec
  (s/tuple
   #{:expound.ds/maybe-spec}
   :expound.ds/spec))

(s/def :expound.ds/simple-specs
  #{string?
    vector?
    int?
    boolean?
    keyword?
    map?
    symbol?
    pos-int?
    neg-int?
    nat-int?})

(s/def :expound.ds/vector-spec (s/coll-of
                                :expound.ds/spec
                                :count 1
                                :kind vector?))

(s/def :expound.ds/set-spec (s/coll-of
                             :expound.ds/spec
                             :count 1
                             :kind set?))

(s/def :expound.ds/map-spec
  (s/map-of :expound.ds/spec-key
            :expound.ds/spec))

(s/def :expound.ds/spec
  (s/or
   :map :expound.ds/map-spec
   :vector :expound.ds/vector-spec
   :set :expound.ds/set-spec
   :simple :expound.ds/simple-specs
   :maybe :expound.ds/maybe-spec))

(deftest generated-data-specs
  (checking
   "generated data specs"
   (chuck/times num-tests)
   [data-spec (s/gen :expound.ds/spec)
    form test-utils/any-printable-wo-nan
    prefix (s/gen qualified-keyword?)
    :let [gen-spec (ds/spec prefix (real-spec data-spec))]]
   (is (string? (expound/expound-str gen-spec form)))))

;; FIXME - keys
;; FIXME - cat + alt, + ? *
;; FIXME - nilable
;; FIXME - test coll-of that is a set . can i should a bad element of a set?

(s/def :test-assert/name string?)
(deftest test-assert
  (testing "assertion passes"
    (is (= "hello"
           (s/assert :test-assert/name "hello"))))
  (testing "assertion fails"
    #?(:cljs
       (try
         (binding [s/*explain-out* expound/printer]
           (s/assert :test-assert/name :hello))
         (catch :default e
           (is (= "Spec assertion failed\n-- Spec failed --------------------

  :hello

should satisfy

  string?

-- Relevant specs -------

:test-assert/name:
  cljs.core/string?

-------------------------
Detected 1 error\n"
                  (.-message e)))))
       :clj
       (try
         (binding [s/*explain-out* expound/printer]
           (s/assert :test-assert/name :hello))
         (catch Exception e
           (is (= "Spec assertion failed
-- Spec failed --------------------

  :hello

should satisfy

  string?

-- Relevant specs -------

:test-assert/name:
  clojure.core/string?

-------------------------
Detected 1 error\n"
                  ;; FIXME - move assertion out of catch, similar to instrument tests
                  (:cause (Throwable->map e)))))))))

(s/def :test-explain-str/name string?)
(deftest test-explain-str
  (is (= (pf "-- Spec failed --------------------

  :hello

should satisfy

  string?

-- Relevant specs -------

:test-explain-str/name:
  pf.core/string?

-------------------------
Detected 1 error\n")
         (binding [s/*explain-out* expound/printer]
           (s/explain-str :test-explain-str/name :hello)))))

(s/fdef test-instrument-adder
  :args (s/cat :x int? :y int?)
  :fn #(> (:ret %) (-> % :args :x))
  :ret pos-int?)
(defn test-instrument-adder [& args]
  (let [[x y] args]
    (+ x y)))

(defn no-linum [s]
  (string/replace s #"(.cljc?):\d+" "$1:LINUM"))

(deftest test-instrument
  (st/instrument `test-instrument-adder)
  #?(:cljs (is (=
                "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
<filename missing>:<line number missing>

-- Spec failed --------------------

Function arguments

  (\"\" ...)
   ^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
                (formatted-exception {:print-specs? false} #(test-instrument-adder "" :x))))
     :clj
     (is (= "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
alpha_test.cljc:LINUM

-- Spec failed --------------------

Function arguments

  (\"\" ...)
   ^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
            (no-linum
             (formatted-exception {:print-specs? false :show-valid-values? false} #(test-instrument-adder "" :x))))))
  (st/unstrument `test-instrument-adder))

(deftest test-instrument-with-orchestra-args-spec-failure
  (orch.st/instrument `test-instrument-adder)
  #?(:cljs (is (=
                "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
<filename missing>:<line number missing>

-- Spec failed --------------------

Function arguments

  (\"\" ...)
   ^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
                (no-linum (formatted-exception {:print-specs? false} #(test-instrument-adder "" :x)))))
     :clj (is (= "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
alpha_test.cljc:LINUM

-- Spec failed --------------------

Function arguments

  (\"\" ...)
   ^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
                 (no-linum
                  (formatted-exception
                   {}
                   #(test-instrument-adder "" :x))))))

  (orch.st/unstrument `test-instrument-adder))

;; Note - you may need to comment out this test out when
;; using figwheel.main for testing, since the compilation
;; warning seems to impact the building of other tests
(deftest test-instrument-with-orchestra-args-syntax-failure
  (orch.st/instrument `test-instrument-adder)
  #?(:cljs (is (=
                "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
<filename missing>:<line number missing>

-- Syntax error -------------------

Function arguments

  (1)

should have additional elements. The next element \":y\" should satisfy

  int?

-------------------------
Detected 1 error\n"
                (no-linum (formatted-exception {:print-specs? false} #(test-instrument-adder 1)))))
     :clj
     (is (= "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
alpha_test.cljc:LINUM

-- Syntax error -------------------

Function arguments

  (1)

should have additional elements. The next element \":y\" should satisfy

  int?

-------------------------
Detected 1 error\n"
            (no-linum
             (formatted-exception
              {:print-specs? false}
              #(test-instrument-adder 1))))))
  (orch.st/unstrument `test-instrument-adder))

(deftest test-instrument-with-orchestra-ret-failure
  (orch.st/instrument `test-instrument-adder)
  #?(:cljs (is (=
                "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
<filename missing>:<line number missing>

-- Spec failed --------------------

Return value

  -3

should satisfy

  pos-int?

-------------------------
Detected 1 error\n"
                (formatted-exception {}
                                     #(test-instrument-adder -1 -2))
                #_(.-message (try
                               (binding [s/*explain-out* expound/printer]
                                 (test-instrument-adder -1 -2))
                               (catch :default e e)))))
     :clj
     (is (= "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
alpha_test.cljc:LINUM

-- Spec failed --------------------

Return value

  -3

should satisfy

  pos-int?

-------------------------
Detected 1 error\n"
            (no-linum
             (formatted-exception
              {:print-specs? false}
              #(test-instrument-adder -1 -2))))))
  (orch.st/unstrument `test-instrument-adder))

(deftest test-instrument-with-orchestra-fn-failure
  (orch.st/instrument `test-instrument-adder)
  #?(:cljs (is (=
                "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
<filename missing>:<line number missing>

-- Spec failed --------------------

Function arguments and return value

  {:ret 1, :args {:x 1, :y 0}}

should satisfy

  (fn [%] (> (:ret %) (-> % :args :x)))

-------------------------
Detected 1 error\n"
                (formatted-exception {} #(test-instrument-adder 1 0))))
     :clj
     (is (= "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
alpha_test.cljc:LINUM

-- Spec failed --------------------

Function arguments and return value

  {:ret 1, :args {:x 1, :y 0}}

should satisfy

  (fn
   [%]
   (> (:ret %) (-> % :args :x)))

-------------------------
Detected 1 error\n"
            (no-linum
             (formatted-exception {:print-specs? false} #(test-instrument-adder 1 0))))))

  (orch.st/unstrument `test-instrument-adder))

(deftest test-instrument-with-custom-value-printer
  (st/instrument `test-instrument-adder)
  #?(:cljs
     (is (=
          "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
<filename missing>:<line number missing>

-- Spec failed --------------------

Function arguments

  (\"\" :x)
   ^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
          (no-linum
           (formatted-exception {:print-specs? false :show-valid-values? true} #(test-instrument-adder "" :x)))))
     :clj
     (is (=
          "Call to #'expound.alpha-test/test-instrument-adder did not conform to spec.
alpha_test.cljc:LINUM

-- Spec failed --------------------

Function arguments

  (\"\" :x)
   ^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
          (no-linum
           (formatted-exception {:print-specs? false :show-valid-values? true} #(test-instrument-adder "" :x))))))

  (st/unstrument `test-instrument-adder))

(s/def :custom-printer/strings (s/coll-of string?))
(deftest custom-printer
  (testing "custom value printer"
    (is (= (pf "-- Spec failed --------------------

  <HIDDEN>

should satisfy

  string?

-- Relevant specs -------

:custom-printer/strings:
  (pf.spec.alpha/coll-of pf.core/string?)

-------------------------
Detected 1 error
")
           (binding [s/*explain-out* (expound/custom-printer {:value-str-fn (fn [_spec-name _form _path _val] "  <HIDDEN>")})]
             (s/explain-str :custom-printer/strings ["a" "b" :c]))))))

(s/def :alt-spec/int-alt-str (s/alt :int int? :string string?))

(s/def :alt-spec/num-types (s/alt :int int? :float float?))
(s/def :alt-spec/str-types (s/alt :int (fn [n] (= n "int"))
                                  :float (fn [n] (= n "float"))))
(s/def :alt-spec/num-or-str (s/alt :num :alt-spec/num-types
                                   :str :alt-spec/str-types))

(s/def :alt-spec/i int?)
(s/def :alt-spec/s string?)
(s/def :alt-spec/alt-or-map (s/or :i :alt-spec/i
                                  :s :alt-spec/s
                                  :k (s/keys :req-un [:alt-spec/i :alt-spec/s])))

(defmulti alt-spec-mspec :tag)
(s/def :alt-spec/mspec (s/multi-spec alt-spec-mspec :tag))
(defmethod alt-spec-mspec :x [_] (s/keys :req-un [:alt-spec/one-many-int]))

(deftest alt-spec
  (testing "alternatives at different paths in spec"
    (is (=
         "-- Spec failed --------------------

  [\"foo\"]

should satisfy

  int?

or value

  [\"foo\"]
   ^^^^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
         (expound/expound-str
          (s/or
           :i int?
           :seq (s/cat :x1 int? :x2 int?))
          ["foo"]
          {:print-specs? false})))
    (s/def :alt-spec/one-many-int (s/cat :bs (s/alt :one int?
                                                    :many (s/spec (s/+ int?)))))
    (is (= (pf "-- Spec failed --------------------

  [[\"1\"]]
   ^^^^^

should satisfy

  int?

or value

  [[\"1\"]]
    ^^^

should satisfy

  int?

-- Relevant specs -------

:alt-spec/one-many-int:
  (pf.spec.alpha/cat
   :bs
   (pf.spec.alpha/alt
    :one
    pf.core/int?
    :many
    (pf.spec.alpha/spec (pf.spec.alpha/+ pf.core/int?))))

-------------------------
Detected 1 error\n")
           (binding [s/*explain-out* (expound/custom-printer {})]
             (s/explain-str
              :alt-spec/one-many-int
              [["1"]]))))
    (s/def :alt-spec/one-many-int-or-str (s/cat :bs (s/alt :one :alt-spec/int-alt-str
                                                           :many (s/spec (s/+ :alt-spec/int-alt-str)))))
    (is (= "-- Spec failed --------------------

  [[:one]]
   ^^^^^^

should satisfy

  int?

or

  string?

or value

  [[:one]]
    ^^^^

should satisfy

  int?

or

  string?

-------------------------
Detected 1 error\n"
           (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
             (s/explain-str
              :alt-spec/one-many-int-or-str
              [[:one]]))))
    (s/def :alt-spec/int-or-str (s/or :i int?
                                      :s string?))
    (s/def :alt-spec/one-many-int-or-str (s/cat :bs (s/alt :one :alt-spec/int-or-str
                                                           :many (s/spec (s/+ :alt-spec/int-or-str)))))
    (is (= "-- Spec failed --------------------

  [[:one]]
   ^^^^^^

should satisfy

  int?

or

  string?

or value

  [[:one]]
    ^^^^

should satisfy

  int?

or

  string?

-------------------------
Detected 1 error\n"
           (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
             (s/explain-str
              :alt-spec/one-many-int-or-str
              [[:one]])))))
  (is (= (pf "-- Spec failed --------------------

  [:hi]
   ^^^

should satisfy

  int?

or

  string?

-- Relevant specs -------

:alt-spec/int-alt-str:
  %s

-------------------------
Detected 1 error\n"
             #?(:clj "(clojure.spec.alpha/alt
   :int
   clojure.core/int?
   :string
   clojure.core/string?)"
                :cljs "(cljs.spec.alpha/alt :int cljs.core/int? :string cljs.core/string?)"))
         (expound/expound-str :alt-spec/int-alt-str [:hi])))

  (is (= "-- Spec failed --------------------

  {:i \"\", :s 1}

should satisfy

  int?

or

  string?

-- Spec failed --------------------

  {:i \"\", :s ...}
      ^^

should satisfy

  int?

-- Spec failed --------------------

  {:i ..., :s 1}
              ^

should satisfy

  string?

-------------------------
Detected 3 errors
"

         (expound/expound-str
          :alt-spec/alt-or-map
          {:i "" :s 1}
          {:print-specs? false})))

  (is (= "-- Spec failed --------------------

  [true]
   ^^^^

should satisfy

  int?

or

  float?

or

  (fn [n] (= n \"int\"))

or

  (fn [n] (= n \"float\"))

-------------------------
Detected 1 error\n" (expound/expound-str :alt-spec/num-or-str [true] {:print-specs? false})))
  ;; If two s/alt specs have the same tags, we shouldn't confuse them.
  (is (= "-- Spec failed --------------------

  {:num-types [true], :str-types ...}
               ^^^^

should satisfy

  int?

or

  float?

-- Spec failed --------------------

  {:num-types ..., :str-types [false]}
                               ^^^^^

should satisfy

  (fn [n] (= n \"int\"))

or

  (fn [n] (= n \"float\"))

-------------------------
Detected 2 errors\n"
         (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
           (s/explain-str (s/keys :req-un [:alt-spec/num-types :alt-spec/str-types])
                          {:num-types [true] :str-types [false]}))))

  (is (=
       "-- Spec failed --------------------

  [\"\"]

should satisfy

  nil?

or value

  [\"\"]
   ^^

should satisfy

  int?

or

  float?

-------------------------
Detected 1 error
"
       (expound/expound-str
        (s/nilable (s/cat :n (s/alt :int int? :float float?)))
        [""]
        {:print-specs? false})))
  (is (=
       ;; This output is not what we want: ideally, the two alternates
       ;; should be grouped into a single problem.
       ;; I'm adding it as a spec to avoid regressions and to keep it as
       ;; an example of something I could improve.
       ;; The reason we can't do better is that we can't reliably look
       ;; at the form of a multi-spec. It would be nice if spec inserted
       ;; the actual spec form that was returned by the multi-spec, but
       ;; as it stands today, we'd have to figure out how to call the multi-
       ;; method with the actual value. That would be complicated and
       ;; potentially have unknown side effects from running arbitrary code.

       "-- Spec failed --------------------

  {:mspec {:tag ..., :one-many-int [[\"1\"]]}}
                                    ^^^^^

should satisfy

  int?

-- Spec failed --------------------

  {:mspec {:tag ..., :one-many-int [[\"1\"]]}}
                                     ^^^

should satisfy

  int?

-------------------------
Detected 2 errors\n"

       (expound/expound-str
        (s/keys
         :req-un [:alt-spec/mspec])
        {:mspec
         {:tag :x
          :one-many-int [["1"]]}}

        {:print-specs? false}))))

(defn mutate-coll [x]
  (cond
    (map? x)
    (into [] x)

    (vector? x)
    (into #{} x)

    (set? x)
    (reverse (into '() x))

    (list? x)
    (into {} (map vec (partition 2 x)))

    :else
    x))

(defn mutate-type [x]
  (cond
    (number? x)
    (str x)

    (string? x)
    (keyword x)

    (keyword? x)
    (str x)

    (boolean? x)
    (str x)

    (symbol? x)
    (str x)

    (char? x)
    #?(:cljs (.charCodeAt x)
       :clj (int x))

    (uuid? x)
    (str x)

    :else
    x))

(defn mutate [form path]
  (let [[head & rst] path]
    (cond
      (empty? path)
      (if (coll? form)
        (mutate-coll form)
        (mutate-type form))

      (map? form)
      (if (empty? form)
        (mutate-coll form)
        (let [k (nth (keys form) (mod head (count (keys form))))]
          (assoc form k
                 (mutate (get form k) rst))))

      (vector? form)
      (if (empty? form)
        (mutate-coll form)
        (let [idx (mod head (count form))]
          (assoc form idx
                 (mutate (nth form idx) rst))))

      (not (coll? form))
      (mutate-type form)

      :else
      (mutate-coll form))))

(deftest test-assert2
  (is (thrown-with-msg?
       #?(:cljs :default :clj Exception)
       #"\"Key must be integer\"\n\nshould be one of: \"Extra input\", \"Insufficient input\", \"no method"
       (binding [s/*explain-out* expound/printer]
         (try
           (s/check-asserts true)
           (s/assert (s/nilable #{"Insufficient input" "Extra input" "no method"}) "Key must be integer")
           (finally (s/check-asserts false)))))))

(defn inline-specs [keyword]
  (walk/postwalk
   (fn [x]
     (if (contains? (s/registry) x)
       (s/form x)
       x))
   (s/form keyword)))

#?(:clj
   (deftest real-spec-tests
     (checking
      "for any real-world spec and any data, explain-str returns a string"
      ;; At 50, it might find a bug in failures for the
      ;; :ring/handler spec, but keep it plugged in, since it
      ;; takes a long time to shrink
      (chuck/times num-tests)
      [spec sg/spec-gen
       form gen/any-printable]
      ;; Can't reliably test fspecs until
      ;; https://dev.clojure.org/jira/browse/CLJ-2258 is fixed
      ;; because the algorithm to fix up the 'in' paths depends
      ;; on the non-conforming value existing somewhere within
      ;; the top-level form
      (when-not
          ;; a conformer generally won't work against any arbitrary value
          ;; e.g. we can't conform 0 with the conformer 'seq'
       (or (contains? #{:conformers-test/string-AB} spec)
           (some
            #{"clojure.spec.alpha/fspec"}
            (->> spec
                 inline-specs
                 (tree-seq coll? identity)
                 (map str))))
        (is (string? (expound/expound-str spec form)))))))

#?(:clj
   (deftest assert-on-real-spec-tests
     (checking
      "for any real-world spec and any data, assert returns an error that matches explain-str"
      (chuck/times num-tests)
      [spec sg/spec-gen
       form gen/any-printable]
      ;; Can't reliably test fspecs until
      ;; https://dev.clojure.org/jira/browse/CLJ-2258 is fixed
      ;; because the algorithm to fix up the 'in' paths depends
      ;; on the non-conforming value existing somewhere within
      ;; the top-level form
      (when-not (some
                 #{"clojure.spec.alpha/fspec"}
                 (->> spec
                      inline-specs
                      (tree-seq coll? identity)
                      (map str)))
        (when-not (s/valid? spec form)
          (let [expected-err-msg (str "Spec assertion failed\n"
                                      (binding [s/*explain-out* (expound/custom-printer {:print-specs? true})]
                                        (s/explain-str spec form)))]
            (is (thrown-with-msg?
                 #?(:cljs :default :clj Exception)
                 (re-pattern (java.util.regex.Pattern/quote expected-err-msg))
                 (binding [s/*explain-out* expound/printer]
                   (try
                     (s/check-asserts true)
                     (s/assert spec form)
                     (finally
                       (s/check-asserts false)))))
                (str "Expected: " expected-err-msg))))))))

(deftest test-mutate
  (checking
   "mutation alters data structure"
   (chuck/times num-tests)
   [form gen/any-printable
    mutate-path (gen/vector gen/nat 1 10)]
   (is (not= form
             (mutate form mutate-path)))))

#?(:clj
   1
   #_(deftest real-spec-tests-mutated-valid-value
     ;; FIXME - we need to use generate mutated value, instead
     ;; of adding randomness to test
       #_(checking
          "for any real-world spec and any mutated valid data, explain-str returns a string"
          (chuck/times num-tests)
          [spec sg/spec-gen
           mutate-path (gen/vector gen/pos-int)]
          (when-not (some
                     #{"clojure.spec.alpha/fspec"}
                     (->> spec
                          inline-specs
                          (tree-seq coll? identity)
                          (map str)))
            (when (contains? (s/registry) spec)
              (try
                (let [valid-form (first (s/exercise spec 1))
                      invalid-form (mutate valid-form mutate-path)]
                  (is (string? (expound/expound-str spec invalid-form))))
                (catch clojure.lang.ExceptionInfo e
                  (when (not= :no-gen (::s/failure (ex-data e)))
                    (when (not= "Couldn't satisfy such-that predicate after 100 tries." (.getMessage e))
                      (throw e))))))))))

;; Using conformers for transformation should not crash by default, or at least give useful error message.
(defn numberify [s]
  (cond
    (number? s) s
    (re-matches #"^\d+$" s) #?(:cljs (js/parseInt s 10)
                               :clj (Integer. s))
    :else ::s/invalid))

(s/def :conformers-test/number (s/conformer numberify))

(defn conform-by
  [tl-key payload-key]
  (s/conformer (fn [m]
                 (let [id (get m tl-key)]
                   (if (and id (map? (get m payload-key)))
                     (assoc-in m [payload-key tl-key] id)
                     ::s/invalid)))))

(s/def :conformers-test.query/id qualified-keyword?)

(defmulti query-params :conformers-test.query/id)
(s/def :conformers-test.query/params (s/multi-spec query-params :conformers-test.query/id))
(s/def :user/id string?)

(defmethod query-params :conformers-test/lookup-user [_]
  (s/keys :req [:user/id]))

(s/def :conformers-test/query
  (s/and
   (conform-by :conformers-test.query/id :conformers-test.query/params)
   (s/keys :req [:conformers-test.query/id
                 :conformers-test.query/params])))

(s/def :conformers-test/string-AB-seq (s/cat :a #{\A} :b #{\B}))

(s/def :conformers-test/string-AB
  (s/and
   ;; conform as sequence (seq function)
   (s/conformer #(if (seqable? %) (seq %) %))
   ;; re-use previous sequence spec
   :conformers-test/string-AB-seq))

(defn parse-csv [s]
  (map string/upper-case (string/split s #",")))

(deftest conformers-test
  ;; Example from http://cjohansen.no/a-unified-specification/
  (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})
            *print-namespace-maps* false]
    (testing "conform string to int"
      (is (string?
           (s/explain-str :conformers-test/number "123a"))))
    ;; Example from https://github.com/bhb/expound/issues/15#issuecomment-326838879
    (testing "conform maps"
      (is (string? (s/explain-str :conformers-test/query {})))
      (is (= "-- Spec failed --------------------

Part of the value

  {:conformers-test.query/id :conformers-test/lookup-user, :conformers-test.query/params {}}

when conformed as

  {:conformers-test.query/id :conformers-test/lookup-user}

should contain key: :user/id

| key      | spec    |
|==========+=========|
| :user/id | string? |

-------------------------
Detected 1 error\n"
             (s/explain-str :conformers-test/query {:conformers-test.query/id :conformers-test/lookup-user
                                                    :conformers-test.query/params {}}))))
    ;; Minified example based on https://github.com/bhb/expound/issues/15
    ;; This doesn't look ideal, but really, it's not a good idea to use spec
    ;; for string parsing, so I'm OK with it
    (testing "conform string to seq"
      (is (=
           ;; clojurescript doesn't have a character type
           #?(:cljs "-- Spec failed --------------------\n\n  \"A\"C\"\"\n    ^^^\n\nshould be: \"B\"\n\n-------------------------\nDetected 1 error\n"
              :clj "-- Spec failed --------------------

  \"A\\C\"
    ^^

should be: \\B

-------------------------
Detected 1 error
")
           (s/explain-str :conformers-test/string-AB "AC"))))
    (testing "s/cat"
      (s/def :conformers-test/sorted-pair (s/and (s/cat :x int? :y int?) #(< (-> % :x) (-> % :y))))
      (is (= (pf "-- Spec failed --------------------

  [1 0]

when conformed as

  {:x 1, :y 0}

should satisfy

  %s

-------------------------
Detected 1 error
"
                 #?(:cljs "(fn [%] (< (-> % :x) (-> % :y)))"
                    :clj "(fn
   [%]
   (< (-> % :x) (-> % :y)))"))
             (expound/expound-str :conformers-test/sorted-pair [1 0] {:print-specs? false})))
      (is (= (pf "-- Spec failed --------------------

  [... [1 0]]
       ^^^^^

when conformed as

  {:x 1, :y 0}

should satisfy

  %s

-------------------------
Detected 1 error\n"
                 #?(:cljs "(fn [%] (< (-> % :x) (-> % :y)))"
                    :clj "(fn
   [%]
   (< (-> % :x) (-> % :y)))"))
             (expound/expound-str (s/coll-of :conformers-test/sorted-pair) [[0 1] [1 0]] {:print-specs? false})))
      (is (= (pf "-- Spec failed --------------------

  {:a [1 0]}
      ^^^^^

when conformed as

  {:x 1, :y 0}

should satisfy

  %s

-------------------------
Detected 1 error\n"
                 #?(:cljs "(fn [%] (< (-> % :x) (-> % :y)))"
                    :clj "(fn
   [%]
   (< (-> % :x) (-> % :y)))"))
             (expound/expound-str (s/map-of keyword? :conformers-test/sorted-pair) {:a [1 0]} {:print-specs? false})))
      (is (= (pf "-- Spec failed --------------------

  [... \"a\"]
       ^^^

should satisfy

  int?

-------------------------
Detected 1 error\n")
             (expound/expound-str :conformers-test/sorted-pair [1 "a"] {:print-specs? false}))))
    (testing "conformers that modify path of values"
      (s/def :conformers-test/vals (s/coll-of (s/and string?
                                                     #(re-matches #"[A-G]+" %))))
      (s/def :conformers-test/csv (s/and string?
                                         (s/conformer parse-csv)
                                         :conformers-test/vals))
      (is (= "-- Spec failed --------------------

Part of the value

  \"abc,def,ghi\"

when conformed as

  \"GHI\"

should satisfy

  (fn [%] (re-matches #\"[A-G]+\" %))

-------------------------
Detected 1 error\n"
             (expound/expound-str :conformers-test/csv "abc,def,ghi" {:print-specs? false}))))

    ;; this is NOT recommended!
    ;; so I'm not inclined to make this much nicer than
    ;; the default spec output
    (s/def :conformers-test/coerced-kw (s/and (s/conformer #(if (string? %)
                                                              (keyword %)
                                                              ::s/invalid))
                                              keyword?))
    (testing "coercion"
      (is (= (pf "-- Spec failed --------------------

  nil

should satisfy

  (pf.spec.alpha/conformer
   (fn
    [%%]
    (if
     (string? %%)
     (keyword %%)
     :pf.spec.alpha/invalid)))

-------------------------
Detected 1 error
")
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (s/explain-str :conformers-test/coerced-kw nil))))

      (is (= (pf "-- Spec failed --------------------

  [... ... ... 0]
               ^

should satisfy

  (pf.spec.alpha/conformer
   (fn
    [%%]
    (if
     (string? %%)
     (keyword %%)
     :pf.spec.alpha/invalid)))

-------------------------
Detected 1 error
")
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (s/explain-str (s/coll-of :conformers-test/coerced-kw) ["a" "b" "c" 0])))))
    ;; Also not recommended
    (s/def :conformers-test/str-kw? (s/and (s/conformer #(if (string? %)
                                                           (keyword %)
                                                           ::s/invalid)
                                                        name) keyword?))
    (testing "coercion with unformer"
      (is (= (pf "-- Spec failed --------------------

  nil

should satisfy

  (pf.spec.alpha/conformer
   (fn
    [%%]
    (if
     (string? %%)
     (keyword %%)
     :pf.spec.alpha/invalid)))

-------------------------
Detected 1 error
")
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (s/explain-str :conformers-test/coerced-kw nil))))

      (is (= (pf "-- Spec failed --------------------

  [... ... ... 0]
               ^

should satisfy

  (pf.spec.alpha/conformer
   (fn
    [%%]
    (if
     (string? %%)
     (keyword %%)
     :pf.spec.alpha/invalid)))

-------------------------
Detected 1 error
")
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (s/explain-str (s/coll-of :conformers-test/coerced-kw) ["a" "b" "c" 0])))))

    (s/def :conformers-test/name string?)
    (s/def :conformers-test/age pos-int?)
    (s/def :conformers-test/person (s/keys* :req-un [:conformers-test/name
                                                     :conformers-test/age]))
    ;; FIXME: Implementation could be simpler once
    ;; https://dev.clojure.org/jira/browse/CLJ-2406 is fixed
    (testing "spec defined with keys*"
      (is (= "-- Spec failed --------------------

  [... ... ... :Stan]
               ^^^^^

should satisfy

  string?

-------------------------
Detected 1 error
"
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (s/explain-str :conformers-test/person [:age 30 :name :Stan])))))

    (testing "spec defined with keys* and copies of bad value elsewhere in the data"
      (is (= "-- Spec failed --------------------

Part of the value

  [:Stan [:age 30 :name :Stan]]

when conformed as

  :Stan

should satisfy

  string?

-------------------------
Detected 1 error\n"
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (s/explain-str (s/tuple
                               keyword?
                               :conformers-test/person) [:Stan [:age 30 :name :Stan]])))))

    (testing "ambiguous value"
      (is (= (pf "-- Spec failed --------------------

  {[0 1] ..., [1 0] ...}
              ^^^^^

when conformed as

  {:x 1, :y 0}

should satisfy

  %s

-------------------------
Detected 1 error
"
                 #?(:cljs "(fn [%] (< (-> % :x) (-> % :y)))"
                    :clj "(fn
   [%]
   (< (-> % :x) (-> % :y)))"))
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (s/explain-str (s/map-of :conformers-test/sorted-pair any?) {[0 1] [1 0]
                                                                            [1 0] [1 0]})))))))

(s/def :duplicate-preds/str-or-str (s/or
                                    ;; Use anonymous functions to assure
                                    ;; non-equality
                                    :str1 #(string? %)
                                    :str2 #(string? %)))
(deftest duplicate-preds
  (testing "duplicate preds only appear once"
    (is (= (pf "-- Spec failed --------------------

  1

should satisfy

  (fn [%%] (string? %%))

-- Relevant specs -------

:duplicate-preds/str-or-str:
  (pf.spec.alpha/or
   :str1
   (pf.core/fn [%%] (pf.core/string? %%))
   :str2
   (pf.core/fn [%%] (pf.core/string? %%)))

-------------------------
Detected 1 error
")
           (expound/expound-str :duplicate-preds/str-or-str 1)))))

(s/def :fspec-test/div (s/fspec
                        :args (s/cat :x int? :y pos-int?)))

(defn my-div [x y]
  (assert (not (zero? (/ x y)))))

(defn  until-unsuccessful [f]
  (let [nil-or-failure #(if (= "Success!
" %)
                          nil
                          %)]
    (or (nil-or-failure (f))
        (nil-or-failure (f))
        (nil-or-failure (f))
        (nil-or-failure (f))
        (nil-or-failure (f)))))

(deftest fspec-exception-test
  (testing "args that throw exception"
    (is (= (pf "-- Exception ----------------------

  expound.alpha-test/my-div

threw exception

  \"Assert failed: (not (zero? (/ x y)))\"

with args:

  0, 1

-- Relevant specs -------

:fspec-test/div:
  (pf.spec.alpha/fspec
   :args
   (pf.spec.alpha/cat :x pf.core/int? :y pf.core/pos-int?)
   :ret
   pf.core/any?
   :fn
   nil)

-------------------------
Detected 1 error\n")

           ;;
           (until-unsuccessful #(expound/expound-str :fspec-test/div my-div))))

    (is (= (pf "-- Exception ----------------------

  [expound.alpha-test/my-div]
   ^^^^^^^^^^^^^^^^^^^^^^^^^

threw exception

  \"Assert failed: (not (zero? (/ x y)))\"

with args:

  0, 1

-- Relevant specs -------

:fspec-test/div:
  (pf.spec.alpha/fspec
   :args
   (pf.spec.alpha/cat :x pf.core/int? :y pf.core/pos-int?)
   :ret
   pf.core/any?
   :fn
   nil)

-------------------------
Detected 1 error\n")
           (until-unsuccessful #(expound/expound-str (s/coll-of :fspec-test/div) [my-div]))))))

(s/def :fspec-ret-test/my-int pos-int?)
(s/def :fspec-ret-test/plus (s/fspec
                             :args (s/cat :x int? :y pos-int?)
                             :ret :fspec-ret-test/my-int))

(defn my-plus [x y]
  (+ x y))

(deftest fspec-ret-test
  (testing "invalid ret"
    (is (= (pf "-- Function spec failed -----------

  expound.alpha-test/my-plus

returned an invalid value

  0

should satisfy

  pos-int?

-------------------------
Detected 1 error\n")
           (until-unsuccessful #(expound/expound-str :fspec-ret-test/plus my-plus {:print-specs? false}))))

    (is (= (pf "-- Function spec failed -----------

  [expound.alpha-test/my-plus]
   ^^^^^^^^^^^^^^^^^^^^^^^^^^

returned an invalid value

  0

should satisfy

  pos-int?

-------------------------
Detected 1 error\n")
           (until-unsuccessful #(expound/expound-str (s/coll-of :fspec-ret-test/plus) [my-plus] {:print-specs? false}))))
    (s/def :fspec-ret-test/return-map (s/fspec
                                       :args (s/cat)
                                       :ret (s/keys :req-un [:fspec-ret-test/my-int])))
    (is (= (pf "-- Function spec failed -----------

  <anonymous function>

returned an invalid value

  {}

should contain key: :my-int

| key     | spec     |
|=========+==========|
| :my-int | pos-int? |

-------------------------
Detected 1 error
")
           (until-unsuccessful #(expound/expound-str :fspec-ret-test/return-map
                                                     (fn [] {})
                                                     {:print-specs? false}))))))

(s/def :fspec-fn-test/minus (s/fspec
                             :args (s/cat :x int? :y int?)
                             :fn (s/and
                                  #(< (:ret %) (-> % :args :x))
                                  #(< (:ret %) (-> % :args :y)))))

(defn my-minus [x y]
  (- x y))

(deftest fspec-fn-test
  (testing "invalid ret"
    (is (= (pf "-- Function spec failed -----------

  expound.alpha-test/my-minus

failed spec. Function arguments and return value

  {:args {:x 0, :y 0}, :ret 0}

should satisfy

  %s

-------------------------
Detected 1 error\n"

               #?(:clj
                  "(fn
   [%]
   (< (:ret %) (-> % :args :x)))"
                  :cljs "(fn [%] (< (:ret %) (-> % :args :x)))"))
           (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
             (until-unsuccessful #(s/explain-str :fspec-fn-test/minus my-minus)))))

    (is (= (pf "-- Function spec failed -----------

  [expound.alpha-test/my-minus]
   ^^^^^^^^^^^^^^^^^^^^^^^^^^^

failed spec. Function arguments and return value

  {:args {:x 0, :y 0}, :ret 0}

should satisfy

  %s

-------------------------
Detected 1 error\n"
               #?(:clj
                  "(fn
   [%]
   (< (:ret %) (-> % :args :x)))"
                  :cljs "(fn [%] (< (:ret %) (-> % :args :x)))"))
           (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
             (until-unsuccessful #(s/explain-str (s/coll-of :fspec-fn-test/minus) [my-minus])))))))

(deftest ifn-fspec-test
  (testing "keyword ifn / ret failure"
    (is (= "-- Function spec failed -----------

  [:foo]
   ^^^^

returned an invalid value

  nil

should satisfy

  int?

-------------------------
Detected 1 error\n"
           (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
             (until-unsuccessful #(s/explain-str (s/coll-of (s/fspec :args (s/cat :x int?) :ret int?))
                                                 [:foo])))))
    (testing "set ifn / ret failure"
      (is (= "-- Function spec failed -----------

  [#{}]
   ^^^

returned an invalid value

  nil

should satisfy

  int?

-------------------------
Detected 1 error\n"
             (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
               (until-unsuccessful #(s/explain-str (s/coll-of (s/fspec :args (s/cat :x int?) :ret int?))
                                                   [#{}])))))))
  #?(:clj
     (testing "vector ifn / exception failure"
       (is (= "-- Exception ----------------------

  [[]]
   ^^

threw exception

  nil

with args:

  0

-------------------------
Detected 1 error\n"
              (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
                (until-unsuccessful #(s/explain-str (s/coll-of (s/fspec :args (s/cat :x int?) :ret int?))
                                                    [[]]))))))))

#?(:clj
   (deftest form-containing-incomparables
     (checking
      "for any value including NaN, or Infinity, expound returns a string"
      (chuck/times num-tests)
      [form (gen/frequency
             [[1 (gen/elements
                  [Double/NaN
                   Double/POSITIVE_INFINITY
                   Double/NEGATIVE_INFINITY
                   '(Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY)
                   [Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY]
                   {Double/NaN Double/NaN
                    Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY
                    Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY}])]
              [5 gen/any-printable]])]
      (is (string? (expound/expound-str (constantly false) form))))))

#?(:cljs
   (deftest form-containing-incomparables
     (checking
      "for any value including NaN, or Infinity, expound returns a string"
      (chuck/times num-tests)
      [form (gen/frequency
             [[1 (gen/elements
                  [js/NaN
                   js/Infinity
                   js/-Infinity
                   '(js/NaN js/Infinity js/-Infinity)
                   [js/NaN js/Infinity js/-Infinity]
                   {js/NaN js/NaN
                    js/Infinity js/Infinity
                    js/-Infinity js/-Infinity}])]
              [5 gen/any-printable]])]
      (is (string? (expound/expound-str (constantly false) form))))))

(defmulti pet :pet/type)
(defmethod pet :dog [_]
  (s/keys))
(defmethod pet :cat [_]
  (s/keys))

(defmulti animal :animal/type)
(defmethod animal :dog [_]
  (s/keys))
(defmethod animal :cat [_]
  (s/keys))

(s/def :multispec-in-compound-spec/pet1 (s/and
                                         map?
                                         (s/multi-spec pet :pet/type)))

(s/def :multispec-in-compound-spec/pet2 (s/or
                                         :map1 (s/multi-spec pet :pet/type)
                                         :map2 (s/multi-spec animal :animal/type)))

(deftest multispec-in-compound-spec
  (testing "multispec combined with s/and"
    (is (= (pf "-- Missing spec -------------------

Cannot find spec for

  {:pet/type :fish}

with

 Spec multimethod:      `expound.alpha-test/pet`
 Dispatch value:        `:fish`

-- Relevant specs -------

:multispec-in-compound-spec/pet1:
  (pf.spec.alpha/and
   pf.core/map?
   (pf.spec.alpha/multi-spec expound.alpha-test/pet :pet/type))

-------------------------
Detected 1 error\n")
           (expound/expound-str :multispec-in-compound-spec/pet1 {:pet/type :fish}))))
  ;; FIXME - improve this, maybe something like:
  ;;;;;;;;;;;;;;;;;;;

  ;;   {:pet/type :fish}

  ;; should be described by a spec multimethod, but

  ;;   expound.alpha-test/pet

  ;; is missing a method for value

  ;;   (:pet/type {:pet/type :fish}) ; => :fish

  ;; or

  ;; should be described by a spec multimethod, but

  ;;   expound.alpha-test/pet

  ;; is missing a method for value

  ;;  (:animal/type {:pet/type :fish}) ; => nil
  (testing "multispec combined with s/or"
    (is (= (pf "-- Missing spec -------------------

Cannot find spec for

  {:pet/type :fish}

with

 Spec multimethod:      `expound.alpha-test/pet`
 Dispatch value:        `:fish`

or with

 Spec multimethod:      `expound.alpha-test/animal`
 Dispatch value:        `nil`

-- Relevant specs -------

:multispec-in-compound-spec/pet2:
  (pf.spec.alpha/or
   :map1
   (pf.spec.alpha/multi-spec expound.alpha-test/pet :pet/type)
   :map2
   (pf.spec.alpha/multi-spec expound.alpha-test/animal :animal/type))

-------------------------
Detected 1 error\n")
           (expound/expound-str :multispec-in-compound-spec/pet2 {:pet/type :fish})))))

(expound/def :predicate-messages/string string? "should be a string")
(expound/def :predicate-messages/vector vector? "should be a vector")

(deftest predicate-messages
  (binding [s/*explain-out* (expound/custom-printer {:print-specs? false})]
    (testing "predicate with error message"
      (is (= "-- Spec failed --------------------

  :hello

should be a string

-------------------------
Detected 1 error
"
             (s/explain-str :predicate-messages/string :hello))))
    (testing "predicate within a collection"
      (is (= "-- Spec failed --------------------

  [... :foo]
       ^^^^

should be a string

-------------------------
Detected 1 error
"
             (s/explain-str (s/coll-of :predicate-messages/string) ["" :foo]))))
    (testing "two predicates with error messages"
      (is (= "-- Spec failed --------------------

  1

should be a string

or

should be a vector

-------------------------
Detected 1 error
"
             (s/explain-str (s/or :s :predicate-messages/string
                                  :v :predicate-messages/vector) 1))))
    (testing "one predicate with error message, one without"
      (is (= "-- Spec failed --------------------

  foo

should satisfy

  pos-int?

or

  vector?

or

should be a string

-------------------------
Detected 1 error
"
             (s/explain-str (s/or :p pos-int?
                                  :s :predicate-messages/string
                                  :v vector?) 'foo))))
    (testing "compound predicates"
      (let [email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$"]
        (expound/def :predicate-messages/email (s/and string? #(re-matches email-regex %)) "should be a valid email address")
        (is (= "-- Spec failed --------------------

  \"sally@\"

should be a valid email address

-------------------------
Detected 1 error
"
               (s/explain-str
                :predicate-messages/email
                "sally@"))))
      (expound/def :predicate-messages/score (s/int-in 0 100) "should be between 0 and 100")
      (is (= "-- Spec failed --------------------

  101

should be between 0 and 100

-------------------------
Detected 1 error
"
             (s/explain-str
              :predicate-messages/score
              101))))))

(s/fdef results-str-fn1
  :args (s/cat :x nat-int? :y nat-int?)
  :ret pos?)
(defn results-str-fn1 [x y]
  #?(:clj (+' x y)
     :cljs (+ x y)))

(s/fdef results-str-fn2
  :args (s/cat :x nat-int? :y nat-int?)
  :fn #(let [x (-> % :args :x)
             ret (-> % :ret)]
         (< x ret)))
(defn results-str-fn2 [x y]
  (+ x y))

(s/fdef results-str-fn3
  :args (s/cat :x #{0} :y #{0})
  :ret nat-int?)
(defn results-str-fn3 [x y]
  (+ x y))

(s/fdef results-str-fn4
  :args (s/cat :x int?)
  :ret (s/coll-of int?))
(defn results-str-fn4 [x]
  [x :not-int])

(s/fdef results-str-fn5
  :args (s/cat :x #{1} :y #{1})
  :ret string?)
(defn results-str-fn5
  [_x _y]
  #?(:clj (throw (Exception. "Ooop!"))
     :cljs (throw (js/Error. "Oops!"))))

(s/fdef results-str-fn6
  :args (s/cat :f fn?)
  :ret any?)
(defn results-str-fn6
  [f]
  (f 1))

(s/def :results-str-fn7/k string?)
(s/fdef results-str-fn7
  :args (s/cat :m (s/keys))
  :ret (s/keys :req-un [:results-str-fn7/k]))
(defn results-str-fn7
  [m]
  m)

(s/fdef results-str-missing-fn
  :args (s/cat :x int?))

(s/fdef results-str-missing-args-spec
  :ret int?)
(defn results-str-missing-args-spec [] 1)

(deftest explain-results
  (testing "explaining results with non-expound printer"
    (is (thrown-with-msg?
         #?(:cljs :default :clj Exception)
         #"Cannot print check results"
         (binding [s/*explain-out* s/explain-printer]
           (expound/explain-results-str (st/check `results-str-fn1))))))

  (testing "single bad result (failing return spec)"
    (is (= (pf
            "== Checked expound.alpha-test/results-str-fn1

-- Function spec failed -----------

  (expound.alpha-test/results-str-fn1 0 0)

returned an invalid value.

  0

should satisfy

  pos?

-------------------------
Detected 1 error
")
           (binding [s/*explain-out* expound/printer]
             (expound/explain-results-str (orch-unstrument-test-fns #(st/check `results-str-fn1))))))
    (is (= (pf
            "== Checked expound.alpha-test/results-str-fn7

-- Function spec failed -----------

  (expound.alpha-test/results-str-fn7 {})

returned an invalid value.

  {}

should contain key: :k

| key | spec    |
|=====+=========|
| :k  | string? |

-------------------------
Detected 1 error
")
           (binding [s/*explain-out* expound/printer]
             (expound/explain-results-str (orch-unstrument-test-fns #(st/check `results-str-fn7)))))))
  (testing "single bad result (failing fn spec)"
    (is (= (pf "== Checked expound.alpha-test/results-str-fn2

-- Function spec failed -----------

  (expound.alpha-test/results-str-fn2 0 0)

failed spec. Function arguments and return value

  {:args {:x 0, :y 0}, :ret 0}

should satisfy

  (fn
   [%%]
   (let
    [x (-> %% :args :x) ret (-> %% :ret)]
    (< x ret)))

-------------------------
Detected 1 error
")
           (binding [s/*explain-out* expound/printer]
             (expound/explain-results-str (orch-unstrument-test-fns #(st/check `results-str-fn2)))))))
  (testing "single valid result"
    (is (= "== Checked expound.alpha-test/results-str-fn3

Success!
"
           (binding [s/*explain-out* expound/printer]
             (expound/explain-results-str (st/check `results-str-fn3))))))
  #?(:clj
     (testing "multiple results"
       (is (= "== Checked expound.alpha-test/results-str-fn2

-- Function spec failed -----------

  (expound.alpha-test/results-str-fn2 0 0)

failed spec. Function arguments and return value

  {:args {:x 0, :y 0}, :ret 0}

should satisfy

  (fn
   [%]
   (let
    [x (-> % :args :x) ret (-> % :ret)]
    (< x ret)))

-------------------------
Detected 1 error


== Checked expound.alpha-test/results-str-fn3

Success!
"
              (binding [s/*explain-out* expound/printer]
                (expound/explain-results-str (orch-unstrument-test-fns #(st/check [`results-str-fn2 `results-str-fn3]))))))))

  (testing "check-fn"
    (is (= "== Checked <unknown> ========================

-- Function spec failed -----------

  (<unknown> 0 0)

failed spec. Function arguments and return value

  {:args {:x 0, :y 0}, :ret 0}

should satisfy

  (fn
   [%]
   (let
    [x (-> % :args :x) ret (-> % :ret)]
    (< x ret)))

-------------------------
Detected 1 error
"
           (binding [s/*explain-out* expound/printer]
             (expound/explain-result-str (st/check-fn `results-str-fn1 (s/spec `results-str-fn2)))))))
  #?(:clj (testing "custom printer"
            (is (= "== Checked expound.alpha-test/results-str-fn4

-- Function spec failed -----------

  (expound.alpha-test/results-str-fn4 0)

returned an invalid value.

  [0 :not-int]
     ^^^^^^^^

should satisfy

  int?

-------------------------
Detected 1 error
"
                   (binding [s/*explain-out* (expound/custom-printer {:show-valid-values? true})]
                     (expound/explain-results-str (orch-unstrument-test-fns #(st/check `results-str-fn4))))))))
  (testing "exceptions raised during check"
    (is (= "== Checked expound.alpha-test/results-str-fn5

  (expound.alpha-test/results-str-fn5 1 1)

 threw error"
           (binding [s/*explain-out* expound/printer]
             (take-lines 5 (expound/explain-results-str (st/check `results-str-fn5)))))))
  (testing "colorized output"
    (is (= (pf "<CYAN>== Checked expound.alpha-test/results-str-fn5 <NONE>

<RED>  (expound.alpha-test/results-str-fn5 1 1)<NONE>

 threw error")
           (binding [s/*explain-out* (expound/custom-printer {:theme :figwheel-theme})]
             (readable-ansi (take-lines 5 (expound/explain-results-str (st/check `results-str-fn5))))))))

  (testing "failure to generate"
    (is (=
         #?(:clj "== Checked expound.alpha-test/results-str-fn6

Unable to construct generator for [:f] in

  (clojure.spec.alpha/cat :f clojure.core/fn?)
"
            ;; CLJS doesn't contain correct data for check failure

            :cljs "== Checked expound.alpha-test/results-str-fn6

Unable to construct gen at: [:f] for: fn? in

  (cljs.spec.alpha/cat :f cljs.core/fn?)
")
         (binding [s/*explain-out* expound/printer]
           (expound/explain-results-str (st/check `results-str-fn6))))))
  (testing "no-fn failure"
    (is (= #?(:clj "== Checked expound.alpha-test/results-str-missing-fn

Failed to check function.

  expound.alpha-test/results-str-missing-fn

is not defined
"
              :cljs "== Checked <unknown> ========================

Failed to check function.

  <unknown>

is not defined
")
           (binding [s/*explain-out* expound/printer]
             (expound/explain-results-str (st/check `results-str-missing-fn))))))
  (testing "no args spec"
    (is (= (pf "== Checked expound.alpha-test/results-str-missing-args-spec

Failed to check function.

  (pf.spec.alpha/fspec :ret pf.core/int?)

should contain an :args spec
")
           (binding [s/*explain-out* expound/printer]
             (expound/explain-results-str (st/with-instrument-disabled (st/check `results-str-missing-args-spec))))))))

#?(:clj (deftest explain-results-gen
          (checking
           "all functions can be checked and printed"
           (chuck/times num-tests)
           [sym-to-check (gen/elements (remove
                                        ;; these functions print to stdout, but return
                                        ;; nothing
                                        #{`expound/explain-results
                                          `expound/explain-result
                                          `expound/expound
                                          `expound/printer}
                                        (st/checkable-syms)))]
           ;; Just confirm an error is not thrown
           (is (string?
                (binding [s/*explain-out* expound/printer]
                  (expound/explain-results-str
                   (st/with-instrument-disabled
                     (st/check sym-to-check
                               {:clojure.spec.test.check/opts {:num-tests 10}})))))
               (str "Failed to check " sym-to-check)))))

(s/def :colorized-output/strings (s/coll-of string?))
(deftest colorized-output
  (is (= (pf "-- Spec failed --------------------

  [... :a ...]
       ^^

should satisfy

  string?

-- Relevant specs -------

:colorized-output/strings:
  (pf.spec.alpha/coll-of pf.core/string?)

-------------------------
Detected 1 error
")
         (expound/expound-str :colorized-output/strings ["" :a ""] {:theme :none})))
  (is (= (pf "<NONE><NONE><CYAN>-- Spec failed --------------------<NONE>

  [... <RED>:a<NONE> ...]
  <MAGENTA>     ^^<NONE>

should satisfy

  <GREEN>string?<NONE>

<CYAN>-- Relevant specs -------<NONE>

:colorized-output/strings:
  (pf.spec.alpha/coll-of pf.core/string?)

<CYAN>-------------------------<NONE>
<CYAN>Detected<NONE> <CYAN>1<NONE> <CYAN>error<NONE>
")
         (readable-ansi (expound/expound-str :colorized-output/strings ["" :a ""] {:theme :figwheel-theme})))))

(s/def ::spec-name (s/with-gen
                     qualified-keyword?
                     #(gen/let [kw gen/keyword]
                        (keyword (str "expound-generated-spec/" (name kw))))))

(s/def ::fn-spec (s/with-gen
                   (s/or
                    :sym symbol?
                    :anon (s/cat :fn #{`fn `fn*}
                                 :args-list (s/coll-of any? :kind vector?)
                                 :body (s/* any?))
                    :form (s/cat :comp #{`comp `partial}
                                 :args (s/+ any?)))
                   #(gen/return `any?)))

(s/def ::pred-spec
  (s/with-gen
    ::fn-spec
    #(gen/elements
      [`any?
       `boolean?
       `bytes?
       `double?
       `ident?
       `indexed?
       `int?
       `keyword?
       `map?
       `nat-int?
       `neg-int?
       `pos-int?
       `qualified-ident?
       `qualified-keyword?
       `qualified-symbol?
       `seqable?
       `simple-ident?
       `simple-keyword?
       `simple-symbol?
       `string?
       `symbol?
       `uri?
       `uuid?
       `vector?])))

(s/def ::and-spec (s/cat
                   :and #{`s/and}
                   :branches (s/+
                              ::spec)))

(s/def ::or-spec (s/cat
                  :or #{`s/or}
                  :branches (s/+
                             (s/cat
                              :kw keyword?
                              :spec ::spec))))

(s/def ::set-spec (s/with-gen
                    (s/coll-of
                     any?
                     :kind set?
                     :min-count 1)
                    #(s/gen (s/coll-of
                             (s/or
                              :s string?
                              :i int?
                              :b boolean?
                              :k keyword?)
                             :kind set?))))

(s/def ::spec (s/or
               :amp ::amp-spec
               :alt ::alt-spec
               :and ::and-spec
               :cat ::cat-spec
               :coll ::coll-spec
               :defined-spec ::spec-name
               :every ::every-spec
               :fspec ::fspec-spec
               :keys ::keys-spec
               :map ::map-of-spec
               :merge ::merge-spec
               :multi ::multispec-spec
               :nilable ::nilable-spec
               :or ::or-spec
               :regex-unary ::regex-unary-spec
               :set ::set-spec
               :simple ::pred-spec
               :spec-wrapper (s/cat :wrapper #{`s/spec} :spec ::spec)
               :conformer (s/cat
                           :conformer #{`s/conformer}
                           :f ::fn-spec
                           :unf ::fn-spec)
               :with-gen (s/cat
                          :with-gen #{`s/with-gen}
                          :spec ::spec
                          :f ::fn-spec)
               :tuple-spec ::tuple-spec))

(s/def ::every-opts (s/*
                     (s/alt
                      :kind (s/cat
                             :k #{:kind}
                             :v #{nil
                                  vector? set? map? list?
                                  `vector? `set? `map? `list?})
                      :count (s/cat
                              :k #{:count}
                              :v (s/nilable nat-int?))
                      :min-count (s/cat
                                  :k #{:min-count}
                                  :v (s/nilable nat-int?))
                      :max-count (s/cat
                                  :k #{:max-count}
                                  :v (s/nilable nat-int?))
                      :distinct (s/cat
                                 :k #{:distinct}
                                 :v (s/nilable boolean?))
                      :into (s/cat
                             :k #{:into}
                             :v (s/or :coll #{[] {} #{}}
                                      :list #{'()}))
                      :gen-max (s/cat
                                :k #{:gen-max}
                                :v nat-int?))))

(s/def ::every-spec (s/cat
                     :every #{`s/every}
                     :spec ::spec
                     :opts ::every-opts))

(s/def ::coll-spec (s/cat
                    :coll-of #{`s/coll-of}
                    :spec (s/spec ::spec)
                    :opts ::every-opts))

(s/def ::map-of-spec (s/cat
                      :map-of #{`s/map-of}
                      :k ::spec
                      :w ::spec
                      :opts ::every-opts))

(s/def ::nilable-spec (s/cat
                       :nilable #{`s/nilable}
                       :spec ::spec))

(s/def ::name-combo
  (s/or
   :one ::spec-name
   :combo (s/cat
           :operator #{'and 'or}
           :operands
           (s/+
            ::name-combo))))

(s/def ::keys-spec (s/cat
                    :keys #{`s/keys `s/keys*}

                    :reqs (s/*
                           (s/cat
                            :op #{:req :req-un}
                            :names (s/coll-of
                                    ::name-combo
                                    :kind vector?)))
                    :opts (s/*
                           (s/cat
                            :op #{:opt :opt-un}
                            :names (s/coll-of
                                    ::spec-name
                                    :kind vector?)))))

(s/def ::amp-spec
  (s/cat :op #{`s/&}
         :spec ::spec
         :preds (s/*
                 (s/with-gen
                   (s/or :pred ::pred-spec
                         :defined ::spec-name)
                   #(gen/return `any?)))))

(s/def ::alt-spec
  (s/cat :op #{`s/alt}
         :key-pred-forms (s/+
                          (s/cat
                           :key keyword?
                           :pred (s/spec ::spec)))))

(s/def ::regex-unary-spec
  (s/cat :op #{`s/+ `s/* `s/?} :pred (s/spec ::spec)))

(s/def ::cat-pred-spec
  (s/or
   :spec (s/spec ::spec)
   :regex-unary ::regex-unary-spec
   :amp ::amp-spec
   :alt ::alt-spec))

(defmulti fake-multimethod :fake-tag)

(s/def ::multispec-spec
  (s/cat
   :mult-spec #{`s/multi-spec}
   :mm (s/with-gen
         symbol?
         #(gen/return `fake-multimethod))
   :tag (s/with-gen
          (s/or :sym symbol?
                :k keyword?)
          #(gen/return :fake-tag))))

(s/def ::cat-spec (s/cat
                   :cat #{`s/cat}
                   :key-pred-forms
                   (s/*
                    (s/cat
                     :key keyword?
                     :pred ::cat-pred-spec))))

(s/def ::fspec-spec (s/cat
                     :cat #{`s/fspec}
                     :args (s/cat
                            :args #{:args}
                            :spec ::spec)
                     :ret (s/?
                           (s/cat
                            :ret #{:ret}
                            :spec ::spec))
                     :fn (s/?
                          (s/cat
                           :fn #{:fn}
                           :spec (s/nilable ::spec)))))

(s/def ::tuple-spec (s/cat
                     :tuple #{`s/tuple}
                     :preds (s/+
                             ::spec)))

(s/def ::merge-spec (s/cat
                     :merge #{`s/merge}
                     :pred-forms (s/* ::spec)))

(s/def ::spec-def (s/cat
                   :def #{`s/def}
                   :name ::spec-name
                   :spec (s/spec ::spec)))

#?(:clj (s/def ::spec-defs (s/coll-of ::spec-def
                                      :min-count 1
                                      :gen-max 3)))

(defn exercise-count [spec]
  (case spec
    (::spec-def ::fspec-spec ::regex-unary-spec ::spec-defs ::alt-spec) 1

    (::cat-spec ::merge-spec ::and-spec ::every-spec ::spec ::coll-spec ::map-of-spec ::or-spec ::tuple-spec ::keys-spec) 2

    4))

(deftest spec-specs-can-generate
  (doseq [spec-spec (filter keyword? (sg/topo-sort (filter #(= "expound.alpha-test" (namespace %))
                                                           (keys (s/registry)))))]
    (is
     (doall (s/exercise spec-spec (exercise-count spec-spec)))
     (str "Failed to generate examples for spec " spec-spec))))

#_(defn sample-seq
    "Return a sequence of realized values from `generator`."
    [generator seed]
    (s/assert some? generator)
    (let [max-size 1
          r (if seed
              (random/make-random seed)
              (random/make-random))
          size-seq (gen/make-size-range-seq max-size)]
      (map #(rose/root (gen/call-gen generator %1 %2))
           (gen/lazy-random-states r)
           size-seq)))

#_(defn missing-specs [spec-defs]
    (let [defined (set (map second spec-defs))
          used (set
                (filter
                 #(and (qualified-keyword? %)
                       (= "expound-generated-spec" (namespace %)))
                 (tree-seq coll? seq spec-defs)))]
      (set/difference used defined)))

#?(:clj 1 #_(deftest eval-gen-test
          ;; FIXME - this is a useful test but not 100% reliable yet
          ;; so I'm disabling to get this PR in
              (binding [s/*recursion-limit* 2]
                (checking
                 "expound returns string"
                 5 ;; Hard-code at 5, since generating specs explodes in size quite quickly
                 [spec-defs (s/gen ::spec-defs)
                  pred-specs (gen/vector (s/gen ::pred-spec) 5)
                  seed (s/gen pos-int?)
                  mutate-path (gen/vector gen/pos-int)]
                 (try
                   (doseq [[spec-name spec] (map vector (missing-specs spec-defs) (cycle pred-specs))]
                     (eval `(s/def ~spec-name ~spec)))
                   (doseq [spec-def spec-defs]
                     (eval spec-def))

                   (let [spec (second (last spec-defs))
                         form (last (last spec-defs))
                         disallowed #{"clojure.spec.alpha/fspec"
                                      "clojure.spec.alpha/multi-spec"
                                      "clojure.spec.alpha/with-gen"}]
                     (when-not (or (some
                                    disallowed
                                    (map str (tree-seq coll? identity form)))
                                   (some
                                    disallowed
                                    (->> spec
                                         inline-specs
                                         (tree-seq coll? identity)
                                         (map str))))
                       (let [valid-form (first (sample-seq (s/gen spec) seed))
                             invalid-form (mutate valid-form mutate-path)]
                         (try
                           (is (string?
                                (expound/expound-str spec invalid-form)))
                           (is (not
                                (string/includes?
                                 (expound/expound-str (second (last spec-defs)) invalid-form)
                                 "should contain keys")))
                           (catch Exception e
                             (is (or
                                  (string/includes?
                                   (:cause (Throwable->map e))
                                   "Method code too large!")
                                  (string/includes?
                                   (:cause (Throwable->map e))
                                   "Cannot convert path."))))))))
                   (finally
                 ;; Get access to private atom in clojure.spec
                     (def spec-reg (deref #'s/registry-ref))
                     (doseq [k (filter
                                (fn [k] (= "expound-generated-spec" (namespace k)))
                                (keys (s/registry)))]
                       (swap! spec-reg dissoc k))))))))

(deftest clean-registry
  (testing "only base spec remains"
    (is (<= (count (filter
                    (fn [k] (= "expound-generated-spec" (namespace k)))
                    (keys (s/registry))))
            1)
        (str "Found leftover specs: " (vec (filter
                                            (fn [k] (= "expound-generated-spec" (namespace k)))
                                            (keys (s/registry))))))))

(deftest valid-spec-spec
  (checking
   "spec for specs validates against real specs"
   (chuck/times num-tests)
   [sp (gen/elements
        (sg/topo-sort
         (remove
          (fn [k]
            (string/includes? (pr-str (s/form (s/get-spec k))) "clojure.core.specs.alpha/quotable"))
          (filter
           (fn [k] (or
                    (string/starts-with? (namespace k) "clojure")
                    (string/starts-with? (namespace k) "expound")
                    (string/starts-with? (namespace k) "onyx")
                    (string/starts-with? (namespace k) "ring")))
           (keys (s/registry))))))]
   (is (s/valid? ::spec (s/form (s/get-spec sp)))
       (str
        "Spec name: " sp "\n"
        "Error: "
        (binding [s/*explain-out* (expound/custom-printer {:show-valid-values? true
                                                           :print-specs? false
                                                           :theme :figwheel-theme})]
          (s/explain-str ::spec (s/form (s/get-spec sp))))))))

(defmethod expound/problem-group-str ::test-problem1 [_type _spec-name _val _path _problems _opts]
  "fake-problem-group-str")

(defmethod expound/problem-group-str ::test-problem2 [type spec-name val path problems opts]
  (str "fake-problem-group-str\n"
       (expound/expected-str type spec-name val path problems opts)))

(defmethod expound/expected-str ::test-problem2 [_type _spec-name _val _path _problems _opts]
  "fake-expected-str")

(deftest extensibility-test
  (testing "can overwrite entire message"
    (let [printer-str #'expound/printer-str
          ed (assoc-in (s/explain-data int? "")
                       [::s/problems 0 :expound.spec.problem/type]
                       ::test-problem1)]

      (is (= "fake-problem-group-str\n\n-------------------------\nDetected 1 error\n"
             (printer-str {:print-specs? false} ed)))))
  (testing "can overwrite 'expected' str"
    (let [printer-str #'expound/printer-str
          ed (assoc-in (s/explain-data int? "")
                       [::s/problems 0 :expound.spec.problem/type]
                       ::test-problem2)]

      (is (= "fake-problem-group-str\nfake-expected-str\n\n-------------------------\nDetected 1 error\n"
             (printer-str {:print-specs? false} ed)))))
  (testing "if type has no mm implemented, throw an error"
    (let [printer-str #'expound/printer-str
          ed (assoc-in (s/explain-data int? "")
                       [::s/problems 0 :expound.spec.problem/type]
                       ::test-problem3)]

      (is (thrown-with-msg?
           #?(:cljs :default :clj Exception)
           #"No method in multimethod"
           (printer-str {:print-specs? false} ed))))))

#?(:clj (deftest macroexpansion-errors
          (let [actual (formatted-exception {:print-specs? false} #(macroexpand '(clojure.core/let [a] 2)))]
            (is (or
                 (= "Call to #'clojure.core/let did not conform to spec.
-- Spec failed --------------------

  ([a] ...)
   ^^^

should satisfy

  even-number-of-forms?

-------------------------
Detected 1 error\n"
                    actual)
                 (= "Call to clojure.core/let did not conform to spec.
-- Spec failed --------------------

  ([a] ...)
   ^^^

should satisfy

  even-number-of-forms?

-------------------------
Detected 1 error\n"
                    actual))))
          (let [ed (try
                     (macroexpand '(clojure.core/let [a] 2))
                     (catch Exception e
                       (-> (Throwable->map e) :via last :data)))]
            (is (= "-- Spec failed --------------------

  ([a] ...)
   ^^^

should satisfy

  even-number-of-forms?

-------------------------
Detected 1 error\n"
                   (with-out-str ((expound/custom-printer {:print-specs? false})

                                  ed)))))))

(deftest sorted-map-values
  (is (= "-- Spec failed --------------------

  {\"bar\" 1}

should satisfy

  number?

-------------------------
Detected 1 error\n"
         (expound/expound-str
          number?
          (sorted-map "bar" 1))))
  (is (= "-- Spec failed --------------------

  {:foo {\"bar\" 1}}

should satisfy

  number?

-------------------------
Detected 1 error\n"
         (expound/expound-str
          number?
          {:foo (sorted-map "bar"

                            1)}))))

(defn select-expound-info [spec value]
  (->> (s/explain-data spec value)
       (problems/annotate)
       (:expound/problems)
       (map #(select-keys % [:expound.spec.problem/type :expound/in]))
       (set)))

#?(:clj
   (deftest or-includes-problems-for-each-branch
     (let [p1 (select-expound-info :ring.sync/handler (fn handler [_req] {}))
           p2 (select-expound-info :ring.async/handler (fn handler [_req] {}))
           p3 (select-expound-info :ring.sync+async/handler (fn handler [_req] {}))
           all-problems (select-expound-info :ring/handler (fn handler [_req] {}))]

       (is (set/subset? p1 all-problems) {:extra (set/difference p1 all-problems)})
       (is (set/subset? p2 all-problems) {:extra (set/difference p2 all-problems)})
       (is (set/subset? p3 all-problems) {:extra (set/difference p3 all-problems)})))
   :cljs
   (set/index #{} [:x]) ; noop to keep clj-kondo happy
   )

(deftest defmsg-test
  (s/def :defmsg-test/id1 string?)
  (expound/defmsg :defmsg-test/id1 "should be a string ID")
  (testing "messages for predicate specs"
    (is (= "-- Spec failed --------------------

  123

should be a string ID

-------------------------
Detected 1 error\n"
           (expound/expound-str
            :defmsg-test/id1
            123
            {:print-specs? false}))))

  (s/def :defmsg-test/id2 (s/and string?
                                 #(<= 4 (count %))))
  (expound/defmsg :defmsg-test/id2 "should be a string ID of length 4 or more")
  (testing "messages for 'and' specs"
    (is (= "-- Spec failed --------------------

  \"123\"

should be a string ID of length 4 or more

-------------------------
Detected 1 error\n"
           (expound/expound-str
            :defmsg-test/id2
            "123"
            {:print-specs? false}))))

  (s/def :defmsg-test/statuses #{:ok :failed})
  (expound/defmsg :defmsg-test/statuses "should be either :ok or :failed")
  (testing "messages for set specs"
    (is (= "-- Spec failed --------------------

  :oak

should be either :ok or :failed

-------------------------
Detected 1 error
"
           (expound/expound-str
            :defmsg-test/statuses
            :oak
            {:print-specs? false}))))
  (testing "messages for alt specs"
    (s/def ::x int?)
    (s/def ::y int?)
    (expound/defmsg ::x "must be an integer")
    (is (=
         "-- Spec failed --------------------

  [\"\" ...]
   ^^

must be an integer

-------------------------
Detected 1 error\n"
         (expound/expound-str (s/alt :one
                                     (s/cat :x ::x)
                                     :two
                                     (s/cat :x ::x
                                            :y ::y))

                              ["" ""]
                              {:print-specs? false}))))

  (testing "messages for alt specs (if user duplicates existing message)"
    (s/def ::x int?)
    (s/def ::y int?)
    (expound/defmsg ::x "should satisfy\n\n  int?")
    (is (=
         "-- Spec failed --------------------

  [\"\"]
   ^^

should satisfy

  int?

-------------------------
Detected 1 error\n"
         (expound/expound-str (s/alt :one
                                     ::x
                                     :two
                                     ::y)
                              [""]
                              {:print-specs? false}))))
  (testing "messages for alternatives and set specs"
    (is (= "-- Spec failed --------------------

  :oak

should be either :ok or :failed

or

should satisfy

  string?

-------------------------
Detected 1 error\n"
           (expound/expound-str
            (s/or
             :num
             :defmsg-test/statuses
             :s string?)
            :oak
            {:print-specs? false})))))

(deftest printer
  (st/instrument ['expound/printer])
  (binding [s/*explain-out* expound/printer]
    (is (string? (s/explain-str int? "a")))
    (is (= "Success!\n" (s/explain-str int? 1)))
    (is (= "Success!\n" (with-out-str (expound/printer (s/explain-data int? 1))))))
  (st/unstrument ['expound/printer]))

(deftest undefined-key
  (is (= "-- Spec failed --------------------

  {}

should contain key: :undefined-key/does-not-exist

| key                           | spec                          |
|===============================+===============================|
| :undefined-key/does-not-exist | :undefined-key/does-not-exist |

-------------------------
Detected 1 error
"
         (expound/expound-str (s/keys :req [:undefined-key/does-not-exist])
                              {}
                              {:print-specs? false}))))

#?(:clj
   (deftype FakeDB [m]

     clojure.lang.Seqable
     (seq [_]
       (seq m))

     clojure.lang.IPersistentCollection

     (count [_]
       (count m))
     (cons [_ _o]
       (throw (Exception. "FakeDB doesn't implement 'cons'")))
     (empty [_]
       (FakeDB. {}))
     (equiv [_ o]
       (=
        m
        (:m o)))

     clojure.lang.Associative
     (containsKey [_ k] (contains? m k))
     (entryAt [_ k] (get m k))

     clojure.lang.IPersistentMap
     (assoc [_this _k _v] (throw (Exception. "FakeDB doesn't implement 'assoc'")))
     (assocEx [_this _k _v] (throw (Exception. "FakeDB doesn't implement 'assocEx'")))
     (without [_this _k] (throw (Exception. "FakeDB doesn't implement 'without'")))

     clojure.lang.ILookup
     (valAt [_ k]
       (get m k))
     (valAt [_ k not-found]
       (get m k not-found))))

(s/def ::db-val (s/or :i int? :s string?))

;; https://github.com/bhb/expound/issues/205
#?(:clj (deftest unwalkable-values
          ;; run bin/test-datomic for real test of datomic DB,
          ;; but this at least simulates the failure. We should not
          ;; try to walk arbitrary values
          (let [db (FakeDB. {:a 1})]
            (is (= true (map? db)))
            (is (= "Success!\n"
                   (expound/expound-str some? db)))
            (is (= "-- Spec failed --------------------

  [{:a 1}]
   ^^^^^^

should contain key: :expound.alpha-test/db-val

| key                        | spec                    |
|============================+=========================|
| :expound.alpha-test/db-val | (or :i int? :s string?) |

-------------------------
Detected 1 error
"
                   (expound/expound-str (s/cat :db (s/keys
                                                    :req [::db-val])) [db]))))))

;; https://github.com/bhb/expound/issues/217
(deftest small-values-for-print-length
  (binding [*print-length* 5]
    (is (= "-- Spec failed --------------------

  9

  in

  (0 1 2 3 4 ...)

should satisfy

  (fn [x] (< x 9))

-------------------------
Detected 1 error
"
           (expound/expound-str
            (clojure.spec.alpha/coll-of (fn [x] (< x 9)))
            (range 10))))))

;; https://github.com/bhb/expound/issues/215
(s/def :keys-within-operators.user/name string?)
(s/def :keys-within-operators.user/age pos-int?)

(deftest keys-within-operators

  (is (= "-- Spec failed --------------------

  {}

should contain keys: :age, :keys-within-operators.user/name

| key                              | spec     |
|==================================+==========|
| :age                             | pos-int? |
|----------------------------------+----------|
| :keys-within-operators.user/name | string?  |

-------------------------
Detected 1 error\n"
         (expound/expound-str (s/and (s/keys :req [:keys-within-operators.user/name]
                                             :req-un [:keys-within-operators.user/age])
                                     #(contains? % :foo)) {} {:print-specs? false})))

  (is (= "-- Spec failed --------------------

  {}

should contain keys: :age, :foo, :keys-within-operators.user/name

| key                              | spec                                              |
|==================================+===================================================|
| :age                             | pos-int?                                          |
|----------------------------------+---------------------------------------------------|
| :foo                             | <can't find spec for unqualified spec identifier> |
|----------------------------------+---------------------------------------------------|
| :keys-within-operators.user/name | string?                                           |

-------------------------
Detected 1 error\n"
         (expound/expound-str (s/or :k1 (s/keys :req [:keys-within-operators.user/name]
                                                :req-un [:keys-within-operators.user/age])
                                    :k2  #(contains? % :foo)) {} {:print-specs? false}))))
