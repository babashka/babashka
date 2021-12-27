(ns clojure.test-clojure.instr
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer :all]))

(set! *warn-on-reflection* true)

;; utils

(defmacro with-feature [feature & body]
  `(try ~feature
     ~@body
     (catch Exception ex#)))

;; instrument tests

(defn kwargs-fn
  ([opts] opts)
  ([a b] [a b])
  ([a b & {:as m}] [a b m]))

(defn no-kwargs-fn
  ([opts] opts)
  ([a b] [a b])
  ([args inner & opts] [args inner opts]))

(defn no-kwargs-destruct-fn
  ([opts] opts)
  ([{:as a} b] [a b])
  ([{:as args} inner & opts] [args inner opts]))

(defn just-varargs [& args]
  (apply + args))

(defn add10 [n]
  (+ 10 n))

(alter-meta! #'add10 dissoc :arglists)

;;; Specs

(s/def ::a any?)
(s/def ::b number?)
(s/def ::c any?)
(s/def ::m map?)

(s/fdef kwargs-fn
  :args (s/alt :unary  (s/cat :a ::a)
               :binary (s/cat :a ::a :b ::b)
               :variadic (s/cat :a ::a
                                :b ::b
                                :kwargs (s/keys* :opt-un [::a ::b ::c]))))

(s/fdef no-kwargs-fn
  :args (s/alt :unary  (s/cat :a ::a)
               :binary (s/cat :a ::a :b ::b)
               :variadic (s/cat :a ::a
                                :b ::b
                                :varargs (s/cat :numbers (s/* number?)))))

(s/fdef no-kwargs-destruct-fn
  :args (s/alt :unary  (s/cat :a ::a)
               :binary (s/cat :a ::a :m ::m)
               :variadic (s/cat :a ::a
                                :b ::b
                                :varargs (s/cat :numbers (s/* number?)))))

(s/fdef just-varargs
  :args (s/cat :numbers (s/* number?))
  :ret number?)

(s/fdef add10
  :args (s/cat :arg ::b)
  :ret number?)

(defn- fail-no-kwargs [& args] (apply no-kwargs-fn args))
(defn- fail-kwargs [& args] (apply kwargs-fn args))

(with-feature (kwargs-fn 1 2 {:a 1 :b 2})
  (deftest test-instrument
    (testing "that a function taking fixed args and varargs is spec'd and checked at runtime"
      (letfn [(test-varargs-raw []
                (are [x y] (= x y)
                  1                         (no-kwargs-fn 1)
                  [1 2]                     (no-kwargs-fn 1 2)
                  [1 2 [3 4 5]]             (no-kwargs-fn 1 2 3 4 5)))]
        (testing "that the raw kwargs function operates as expected"
          (test-varargs-raw))

        (testing "that the instrumented kwargs function operates as expected"
          (stest/instrument `no-kwargs-fn {})

          (test-varargs-raw)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not conform to spec" (no-kwargs-fn 1 :not-num)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not conform to spec" (no-kwargs-fn 1 2 :not-num 3)))

          ;; BB-TEST-PATCH: bb gets sci internals instead
          #_(testing "that the ex-info data looks correct"
            (try (fail-no-kwargs 1 :not-num)
                 (catch Exception ei
                   (is (= 'clojure.test-clojure.instr/fail-no-kwargs (-> ei ex-data :clojure.spec.test.alpha/caller :var-scope)))))

            (try (fail-no-kwargs 1 2 :not-num 3)
                 (catch Exception ei
                   (is (= 'clojure.test-clojure.instr/fail-no-kwargs (-> ei ex-data :clojure.spec.test.alpha/caller :var-scope)))))))

        (testing "that the uninstrumented kwargs function operates as the raw function"
          (stest/unstrument `no-kwargs-fn)
          (test-varargs-raw))))

    (testing "that a function taking only varargs is spec'd and checked at runtime"
      (letfn [(test-varargs-raw []
                (are [x y] (= x y)
                  1                         (just-varargs 1)
                  3                         (just-varargs 1 2)
                  15                        (just-varargs 1 2 3 4 5)))]
        (testing "that the raw varargs function operates as expected"
          (test-varargs-raw))

        (testing "that the instrumented varargs function operates as expected"
          (stest/instrument `just-varargs {})

          (test-varargs-raw)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not conform to spec" (just-varargs 1 :not-num)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not conform to spec" (just-varargs 1 2 :not-num 3))))

        (testing "that the uninstrumented kwargs function operates as the raw function"
          (stest/unstrument `just-varargs)
          (test-varargs-raw))))

    (testing "that a function taking keyword args is spec'd and checked at runtime"
      (letfn [(test-kwargs-baseline []
                (are [x y] (= x y)
                  1                         (kwargs-fn 1)
                  [1 2]                     (kwargs-fn 1 2)
                  [1 2 {:a 1}]              (kwargs-fn 1 2 :a 1)
                  [1 2 {:a 1}]              (kwargs-fn 1 2 {:a 1})
                  [1 2 {:a 1 :b 2}]         (kwargs-fn 1 2 :a 1 {:b 2})))
              (test-kwargs-extended []
                (are [x y] (= x y)
                  [1 :not-num]              (kwargs-fn 1 :not-num)
                  [1 2 {:a 1 :b :not-num}]  (kwargs-fn 1 2 :a 1 {:b :not-num})))]
        (testing "that the raw kwargs function operates as expected"
          (test-kwargs-baseline)
          (test-kwargs-extended))

        (testing "that the instrumented kwargs function operates as expected"
          (stest/instrument `kwargs-fn {})

          (test-kwargs-baseline)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not conform to spec" (kwargs-fn 1 :not-num)))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not conform to spec" (kwargs-fn 1 2 :a 1 {:b :not-num})))

          ;; BB-TEST-PATCH: bb gets sci internals instead
          #_(testing "that the ex-info data looks correct"
              (try (fail-kwargs 1 :not-num)
                (catch Exception ei
                  (is (= 'clojure.test-clojure.instr/fail-kwargs (-> ei ex-data :clojure.spec.test.alpha/caller :var-scope)))))

              (try (fail-kwargs 1 2 :a 1 {:b :not-num})
                (catch Exception ei
                  (is (= 'clojure.test-clojure.instr/fail-kwargs (-> ei ex-data :clojure.spec.test.alpha/caller :var-scope)))))))

        (testing "that the uninstrumented kwargs function operates as the raw function"
          (stest/unstrument `kwargs-fn)
          (test-kwargs-baseline)
          (test-kwargs-extended))))

    (testing "that a var with no arglists meta is spec'd and checked at runtime"
      (stest/instrument `add10 {})
      (is (= 11 (add10 1)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did not conform to spec" (add10 :not-num)))
      (is (= 11 (add10 1))))

    (testing "that a function with positional destructuring in its parameter list is spec'd and checked at runtime"
      (stest/instrument `no-kwargs-destruct-fn {})

      (is (= [{:a 1} {}]         (no-kwargs-destruct-fn {:a 1} {})))
      (is (= [{:a 1} 2 [3 4 5]]  (no-kwargs-destruct-fn {:a 1} 2 3 4 5))))))
