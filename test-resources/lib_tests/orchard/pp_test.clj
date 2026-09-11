(ns orchard.pp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is are testing]]
            [orchard.pp :as sut]
            [orchard.print :as print]
            [orchard.print-test]))

(defn replace-crlf [s]
  (str/replace s #"\r\n" "\n"))

(defn pp
  [x & {:keys [print-length print-level print-meta print-readably print-namespace-maps]
        :or {print-length nil
             print-level nil
             print-meta false
             print-readably true
             print-namespace-maps false}
        :as opts}]
  (binding [*print-length* print-length
            *print-level* print-level
            *print-meta* print-meta
            *print-readably* print-readably
            *print-namespace-maps* print-namespace-maps]
    (replace-crlf (with-out-str (sut/pprint x opts)))))

(deftest pprint-test
  (is (= "{}\n" (pp {})))
  (is (= "[nil nil]\n" (pp [nil nil])))
  (is (= "{:a 1}\n" (pp {:a 1})))
  (is (= "(1 nil)\n" (pp '(1 nil))))
  (is (= "{:a 1, :b 2, :c 3, :d 4}\n" (pp {:a 1 :b 2 :c 3 :d 4} :max-width 24)))

  (is (= "{:args\n [{:op :var,\n   :assignable? true}]}\n"
         (pp {:args [{:op :var :assignable? true}]} :max-width 24)))

  (is (= "{:a 1,\n :b 2,\n :c 3,\n :d 4,\n :e 5}\n"
         (pp {:a 1 :b 2 :c 3 :d 4 :e 5} :max-width 24)))

  (is (= "{:a\n 1,\n :b\n 2,\n :c\n 3,\n :d\n 4}\n"
         (pp {:a 1 :b 2 :c 3 :d 4} :max-width 0)))

  (is (= "{:a 1,\n :b 2,\n :c 3,\n :d 4,\n :e {:f 6}}\n"
         (pp {:a 1 :b 2 :c 3 :d 4 :e {:f 6}} :max-width 24)))

  (is (= "{:a 1,\n :b 2,\n :c 3,\n :d 4,\n :e\n {:a 1,\n  :b 2,\n  :c 3,\n  :d 4,\n  :e\n  {:f 6,\n   :g 7,\n   :h 8,\n   :i 9,\n   :j 10}}}\n"
         (pp {:a 1
              :b 2
              :c 3
              :d 4
              :e {:a 1 :b 2 :c 3 :d 4 :e {:f 6 :g 7 :h 8 :i 9 :j 10}}}
             :max-width 24)))

  ;; Max width
  (is (= "{:a\n 1,\n :b\n 2,\n :c\n 3,\n :d\n 4}\n"
         (pp {:a 1 :b 2 :c 3 :d 4} :max-width 0)))

  ;; Meta
  (is (= "^{:b 2} {:a 1}\n"
         (pp (with-meta {:a 1} {:b 2}) :print-meta true)))
  (is (= "^{:b\n  2}\n{:a\n 1}\n"
         (pp (with-meta {:a 1} {:b 2}) :print-meta true :max-width 2)))

  ;; Print level
  (is (= "#\n" (pp {} :print-level 0)))
  (is (= "#\n" (pp {:a 1} :print-level 0)))
  (is (= "{#}\n" (pp {:a {:b 2}} :print-level 1)))
  (is (= "{:a #}\n" (pp {:a {:b 2}} :print-level 2)))
  (is (= "{:a {#}}\n" (pp {:a {:b 2}} :print-level 3)))
  (is (= "{#}\n" (pp {{:a 1} :b} :print-level 1)))
  (is (= "{# :b}\n" (pp {{:a 1} :b} :print-level 2)))
  (is (= "{{#} :b}\n" (pp {{:a 1} :b} :print-level 3)))
  (is (= "#\n" (pp '(:a (:b (:c (:d)))) :print-level 0)))
  (is (= "(:a #)\n" (pp '(:a (:b (:c (:d)))) :print-level 1)))
  (is (= "(:a (:b #))\n" (pp '(:a (:b (:c (:d)))) :print-level 2)))
  (is (= "(:a (:b (:c #)))\n" (pp '(:a (:b (:c (:d)))) :print-level 3)))
  (is (= "(:a (:b (:c (:d))))\n" (pp '(:a (:b (:c (:d)))) :print-level 4)))
  (is (= "(:a (:b (:c (:d))))\n" (pp '(:a (:b (:c (:d)))) :print-level 5)))

  ;; Print length
  (is (= "(...)\n" (pp '() :print-length 0)))
  (is (= "[...]\n" (pp [] :print-length 0)))
  (is (= "#{...}\n" (pp #{} :print-length 0)))
  (is (= "{...}\n" (pp {} :print-length 0)))
  (is (= "(...)\n" (pp (cons 1 '()) :print-length 0))) ; Cons
  (is (= "(...)\n" (pp (range) :print-length 0)))
  (is (= "(0 ...)\n" (pp (range) :print-length 1)))
  (is (= "(...)\n" (pp '(1 2 3) :print-length 0)))
  (is (= "(1 ...)\n" (pp '(1 2 3) :print-length 1)))
  (is (= "(1 2 ...)\n" (pp '(1 2 3) :print-length 2)))
  (is (= "(1 2 3)\n" (pp '(1 2 3) :print-length 3)))
  (is (= "(1 2 3)\n" (pp '(1 2 3) :print-length 4)))

  ;; Print level and print length
  (is (= "#\n" (pp {} :print-level 0 :print-length 0)))
  (is (= "{...}\n" (pp {} :print-level 1 :print-length 0)))
  (is (= "#\n" (pp {} :print-level 0 :print-length 1)))
  (is (= "{}\n" (pp {} :print-level 1 :print-length 1)))

  (is (= "#\n" (pp {:a 1 :b 2} :print-level 0 :print-length 0)))
  (is (= "{...}\n" (pp {:a 1 :b 2} :print-level 1 :print-length 0)))
  (is (= "#\n" (pp {:a 1 :b 2} :print-level 0 :print-length 1)))
  (is (= "{#, ...}\n" (pp {:a 1 :b 2} :print-level 1 :print-length 1)))

  ;; Width
  (is (= "{[]\n [ab000000000000000000000000000000000000000000000000000000000000000N]}\n"
         (pp {[]
              ['ab000000000000000000000000000000000000000000000000000000000000000N]}
             :max-width 72)))

  ;; Reader macros
  (is (= "#'clojure.core/map\n" (pp #'map)))
  (is (= "(#'map)\n" (pp '(#'map))))
  (is (= "#{#'mapcat #'map}\n" (pp '#{#'map #'mapcat})))

  (is (= "{:arglists '([xform* coll]), :added \"1.7\"}\n"
         (pp '{:arglists (quote ([xform* coll])) :added "1.7"})))

  (is (= "@(foo)\n" (pp '@(foo))))
  (is (= "'foo\n" (pp ''foo)))
  #_:clj-kondo/ignore
  (is (= "~foo\n" (pp '~foo)))

  (is (= "('#{boolean\n    char\n    floats})\n"
         (pp '('#{boolean char floats}) :max-width 23)))

  (is (= "#\n"
         (pp '('#{boolean char floats}) :max-width 23 :print-level 0)))

  (is (= "(...)\n"
         (pp '('#{boolean char floats}) :max-width 23 :print-length 0)))

  (is (= "('#{boolean\n    char\n    floats})\n"
         (pp '('#{boolean char floats}) :max-width 23 :print-length 3)))

  ;; Namespace maps
  (is (= "#:a{:b 1}\n" (pp {:a/b 1} :print-namespace-maps true)))
  (is (= "#:a{:b 1, :c 2}\n" (pp {:a/b 1 :a/c 2} :print-namespace-maps true)))
  (is (= "{:a/b 1, :c/d 2}\n" (pp {:a/b 1 :c/d 2} :print-namespace-maps true)))
  (is (= "#:a{:b #:a{:b 1}}\n" (pp {:a/b {:a/b 1}} :print-namespace-maps true)))
  (is (= "#:a{b 1}\n" (pp {'a/b 1} :print-namespace-maps true)))
  (is (= "#:a{b 1, c 3}\n" (pp {'a/b 1 'a/c 3} :print-namespace-maps true)))
  (is (= "{a/b 1, c/d 2}\n" (pp {'a/b 1 'c/d 2} :print-namespace-maps true)))
  (is (= "#:a{b #:a{b 1}}\n" (pp {'a/b {'a/b 1}} :print-namespace-maps true)))
  (is (= "{:a/b 1}\n" (pp {:a/b 1} :print-namespace-maps false)))
  (is (= "{:a/b 1, :a/c 2}\n" (pp {:a/b 1 :a/c 2} :print-namespace-maps false)))
  (is (= "{:a/b 1, :c/d 2}\n" (pp {:a/b 1 :c/d 2} :print-namespace-maps false)))
  (is (= "{:a/b {:a/b 1}}\n" (pp {:a/b {:a/b 1}} :print-namespace-maps false)))
  (is (= "{a/b 1}\n" (pp {'a/b 1} :print-namespace-maps false)))
  (is (= "{a/b 1, a/c 3}\n" (pp {'a/b 1 'a/c 3} :print-namespace-maps false)))
  (is (= "{a/b 1, c/d 2}\n" (pp {'a/b 1 'c/d 2} :print-namespace-maps false)))
  (is (= "{a/b {a/b 1}}\n" (pp {'a/b {'a/b 1}} :print-namespace-maps false)))
  (is (= "#:a{:b 1,\n    :c 2}\n" (pp #:a{:b 1 :c 2} :max-width 14 :print-namespace-maps true)))

  ;; Custom tagged literals
  ;; (is (= "#time/date \"2023-10-02\"\n" (pp #time/date "2023-10-02")))

  ;; Sorted maps
  (is (= "{}\n" (pp (sorted-map))))
  (is (= "{:a 1, :b 2}\n" (pp (sorted-map :a 1 :b 2))))
  (is (= "{:a 1, ...}\n" (pp (sorted-map :a 1 :b 2) :print-length 1)))
  (is (= "{:a 1,\n :b 2}\n" (pp (sorted-map :a 1 :b 2) :max-width 7)))

  ;; Sorted sets
  (is (= "#{}\n" (pp (sorted-set))))
  (is (= "#{1 2 3}\n" (pp (sorted-set 1 2 3))))
  (is (= "#{1 ...}\n" (pp (sorted-set 1 2 3) :print-length 1)))
  (is (= "#{1\n  2\n  3}\n" (pp (sorted-set 1 2 3) :max-width 3)))

  ;; Symbolic
  (is (= "##Inf\n" (pp ##Inf)))
  (is (= "##-Inf\n" (pp ##-Inf)))
  (is (= "##NaN\n" (pp ##NaN)))

  ;; Map entries
  (is (= "[:a 1]\n" (pp (find {:a 1} :a))))
  (is (= "[[:a 1]]\n" (pp [(find {:a 1} :a)])))
  (is (= "([:a 1])\n" (pp (list (find {:a 1} :a)))))
  (is (= "#{[:a 1]}\n" (pp #{(find {:a 1} :a)})))
  (is (= "#\n" (pp (find {:a 1} :a) :print-level 0)))
  (is (= "[:a 1]\n" (pp (find {:a 1} :a) :print-level 1)))
  (is (= "[...]\n" (pp (find {:a 1} :a) :print-length 0)))
  (is (= "[:a ...]\n" (pp (find {:a 1} :a) :print-length 1)))
  (is (= "[...]\n" (pp (find {:a 1} :a) :print-level 1 :print-length 0)))
  (is (= "#\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-level 0)))
  (is (= "#\n" (pp (find {:a 1} :a) :print-level 0 :print-length 1)))
  (is (= "#\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-level 0 :print-length 0)))
  (is (= "[# #]\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-level 1)))
  (is (= "[# ...]\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-level 1 :print-length 1)))
  (is (= "[...]\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-length 0 :print-level 1)))
  (is (= "[...]\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-length 0)))
  (is (= "[[:a ...] ...]\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-length 1)))
  (is (= "[[:a 1] [:b 1]]\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-level 2)))
  (is (= "[[:a 1] [:b 1]]\n" (pp (find {[:a 1] [:b 1]} [:a 1]) :print-level 3)))
  (is (= "[0\n 1]\n" (pp (find {0 1} 0) :max-width 2))))

(deftest pprint-array-test
  (is (= "boolean[] {true false}\n" (pp (boolean-array [true false]))))
  (is (= "byte[] {97 98}\n" (pp (byte-array [(int \a) (int \b)]))))
  (is (= "char[] {\\a \\b}\n" (pp (char-array [\a \b]))))
  (is (= "double[] {1.0 2.0}\n" (pp (double-array [1.0 2.0]))))
  (is (= "float[] {3.0 4.0}\n" (pp (float-array [3.0 4.0]))))
  (is (= "int[] {1 2 3}\n" (pp (int-array [1 2 3]))))
  (is (= "java.lang.Long[] {4 5 6}\n" (pp (into-array [4 5 6]))))
  (is (= "long[] {7 8 9}\n" (pp (long-array [7 8 9]))))
  (is (= "java.lang.Object[] {{:a 1} {:b 2}}\n" (pp (object-array [{:a 1} {:b 2}]))))
  (is (= "short[] {10 11 22}\n" (pp (short-array [10 11 22]))))
  (is (= "[Ljava.lang.Object;[] {java.lang.Object[] {1 2 3}
                       java.lang.Object[] {4 5 6}}\n"
         (pp (to-array-2d [[1 2 3] [4 5 6]])))))

(deftest pprint-meta-test
  ;; clojure.pprint prints this incorrectly with meta
  (is (= "{:a 1}\n"
         (pp (with-meta {:a 1} {:b 2}) :print-meta true :print-readably false)))

  (is (= "{:a 1}\n"
         (pp (with-meta {:a 1} {}) :print-meta true))))

(deftest pprint-reader-macro-edge-cases-test
  ;; do not print the reader macro character if the collection following the
  ;; character exceeds print level
  (is (= "#\n" (pp '('#{boolean char floats}) :print-level 0)))
  (is (= "(#)\n" (pp '('#{boolean char floats}) :print-level 1)))
  (is (= "(#)\n" (pp '('#{boolean char floats}) :print-level 1 :print-length 1)))

  ;; reader macro characters do not count towards *print-length*
  (is (= "(...)\n" (pp '('#{boolean char floats}) :print-length 0)))
  (is (= "('#{boolean ...})\n" (pp '('#{boolean char floats}) :print-length 1))))

(deftest map-entry-separator-test
  (is (= "{:a 1, :b 2}\n" (pp {:a 1 :b 2}))))

(deftest pprint-no-limits
  (are [result form] (match? result (sut/pprint-str form))
    "1" 1
    "\"2\"" "2"
    "\"special \\\" \\\\ symbols\"" "special \" \\ symbols"
    ":foo" :foo
    ":abc/def" :abc/def
    "sym" 'sym
    "(:a :b :c)" '(:a :b :c)
    "[1 2 3]" [1 2 3]
    "{:a 1, :b 2}" {:a 1 :b 2}
    "[:a 1]" (first {:a 1 :b 2})
    "([:a 1] [:b 2])" (seq {:a 1 :b 2})
    "[[:a 1] [:b 2]]" (vec {:a 1 :b 2})
    "{}" {}
    "{}" (java.util.HashMap.)
    "#{:a}" #{:a}
    "(1 2 3)" (lazy-seq '(1 2 3))
    "(1 1 1 1 1)" (java.util.ArrayList. ^java.util.Collection (repeat 5 1))
    "{:a 1, :b 2}" (let [^java.util.Map x {:a 1 :b 2}]
                     (java.util.HashMap. x))
    "#TestRecord{:a 1, :b 2, :c 3, :d 4}" (orchard.print-test/->TestRecord 1 2 3 4)
    "long[] {1 2 3 4}" (long-array [1 2 3 4])
    "long[] {}" (long-array [])
    "java.lang.Long[] {0 1 2 3 4}" (into-array Long (range 5))
    "java.lang.Long[] {}" (into-array Long [])
    "#<MyTestType test1>" (orchard.print-test/->MyTestType "test1")
    "#atom[1]" (atom 1)
    "#delay[<pending>]" (delay 1)
    "#delay[1]" (doto (delay 1) deref)
    #"#delay\[<failed> #error\[java.lang.ArithmeticException \"Divide by zero\"" (let [d (delay (/ 1 0))] (try @d (catch Exception _)) d)
    #"#error\[clojure.lang.ExceptionInfo \"Boom\"" (ex-info "Boom" {})
    "#function[clojure.core/str]" str))

(deftest pprint-limits
  (testing "global writer limits will stop the printing when reached"
    (are [result form] (= result (binding [print/*max-atom-length* 10
                                           print/*max-total-length* 30
                                           *print-length* 5
                                           *print-level* 10]
                                   (sut/pprint-str form)))
      "\"aaaaaaaaa..." (apply str (repeat 300 "a"))
      "[\"aaaaaaaaa... \"aaaaaaaaa...]" [(apply str (repeat 300 "a")) (apply str (repeat 300 "a"))]
      "(1 1 1 1 1 ...)" (repeat 1)
      "[(1 1 1 1 1 ...)]" [(repeat 1)]
      "{:a {(0 1 2 3 4 ...) 1, 2 3, 4..." {:a {(range 10) 1, 2 3, 4 5, 6 7, 8 9, 10 11}}
      "(1 1 1 1 1..." (java.util.ArrayList. ^java.util.Collection (repeat 100 1))
      "{:m {:m {:m {:m {:m 1234, :e 1..." (orchard.print-test/nasty 5)
      "{:b {:a {:..." orchard.print-test/graph-with-loop)

    (is (= "java.lang.Long[] {0 1 2 3 4 ....."
           (binding [print/*max-total-length* 30
                     *print-length* 5]
             (sut/pprint-str (into-array Long (range 10)))))))

  (testing "writer won't go much over total-length"
    (is (= 2003 (count (binding [print/*max-total-length* 2000]
                         (print/print-str orchard.print-test/infinite-map)))))))

(deftest short-record-names-test
  (testing "*short-record-names* controls how records are printed"
    (is (= "#TestRecord{:a (0 1 2 3 4 5 6 7 8 9 10 11 12 13 14),
            :b (0 1 2 3 4 5 6 7 8 9 10 11 12 13 14),
            :c 3,
            :d 4}"
           (sut/pprint-str (orchard.print-test/->TestRecord (range 15) (range 15) 3 4))))
    (binding [print/*short-record-names* false]
      (is (= "#orchard.print_test.TestRecord{:a (0 1 2 3 4 5 6 7 8 9 10 11 12 13 14),
                               :b (0 1 2 3 4 5 6 7 8 9 10 11 12 13 14),
                               :c 3,
                               :d 4}"
             (sut/pprint-str (orchard.print-test/->TestRecord (range 15) (range 15) 3 4)))))))
