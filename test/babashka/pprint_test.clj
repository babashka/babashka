(ns babashka.pprint-test
  (:require
   [babashka.impl.pprint]
   [babashka.test-utils :as test-utils]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn bb [& args]
  (str/trim (apply test-utils/bb (map str args))))

(deftest print-length-test
  (is (= "(0 1 2 3 4 5 6 7 8 9 ...)"
         (bb "-e" "(set! *print-length* 10) (clojure.pprint/pprint (range 20))"))))

(deftest print-namespaced-map-test
  (testing
      "Testing disabling of printing namespace maps..."
      (is (= "{:a/x 1, :a/y 2, :a/z {:b/x 10, :b/y 20}}"
            (bb "-e" "(binding [*print-namespace-maps* false] (clojure.pprint/pprint {:a/x 1 :a/y 2 :a/z {:b/x 10 :b/y 20}}))"))))
  (testing
      "Testing manually enabling printing namespace maps..."
      (is (= "#:a{:x 1, :y 2, :z #:b{:x 10, :y 20}}"
             (bb "-e" "(binding [*print-namespace-maps* true] (clojure.pprint/pprint {:a/x 1 :a/y 2 :a/z {:b/x 10 :b/y 20}}))")))))

(def custom-dispatch
  "(require '[clojure.pprint :as pprint])
   (import '[java.time ZonedDateTime])
   (defmethod pprint/simple-dispatch ZonedDateTime [zdt]
     (print (str \"#time/zdt \" (pr-str (str zdt)))))
   (def zdt (ZonedDateTime/parse \"2026-09-30T08:30-04:00[America/New_York]\"))")

(deftest custom-dispatch-test
  (testing "print in a custom dispatch fn goes to the pretty writer"
    (is (= "{:a #time/zdt \"2026-09-30T08:30-04:00[America/New_York]\", :b 1}"
           (bb "-e" (str custom-dispatch "(pprint/pprint {:a zdt :b 1})"))))
    (is (= "[#time/zdt \"2026-09-30T08:30-04:00[America/New_York]\"]"
           (bb "-e" (str custom-dispatch "(pprint/write [zdt])")))))
  (testing "with-out-str captures the pretty printed output"
    (is (= "\"{:a #time/zdt \\\"2026-09-30T08:30-04:00[America/New_York]\\\"}\\n\""
           (bb "-e" (str custom-dispatch "(prn (with-out-str (pprint/pprint {:a zdt})))"))))
    (is (= "\"{:a 1}\""
           (bb "-e" "(prn (with-out-str (clojure.pprint/write {:a 1})))")))))

(deftest get-pretty-writer-test
  (testing "write-out honors a pretty writer the caller installed itself"
    (is (= "{:a #time/zdt \"2026-09-30T08:30-04:00[America/New_York]\", :b 1}"
           (bb "-e" (str custom-dispatch
                         "(binding [*out* (pprint/get-pretty-writer *out*)]
                            (pprint/write-out {:a zdt :b 1})
                            (flush))"))))))

(deftest host-write-test
  (testing "the patched clojure.pprint/write still works outside of sci"
    (is (= "{:a 1}" (with-out-str (pprint/write {:a 1}))))))
