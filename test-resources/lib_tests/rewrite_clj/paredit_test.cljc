(ns rewrite-clj.paredit-test
  (:require [clojure.test :refer [deftest is testing]]
            [rewrite-clj.node :as n]
            [rewrite-clj.paredit :as pe]
            [rewrite-clj.zip :as z]
            [rewrite-clj.zip.test-helper :as th]))

;; special positional markers recognized by test-helper fns
;; ⊚ - node location
;; ◬ - root :forms node

(def zipper-opts [{} {:track-position? true}])

(defn- zipper-opts-desc [opts]
  (str "zipper opts " opts))

(deftest kill-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s                             expected]
              [["⊚1 2 3 4"                   "◬"]
               ["  ⊚1 2 3 4"                 "⊚  "]
               ["[⊚1 2 3 4]"                 "⊚[]"]
               ["[   ⊚1 2 3 4]"              "[⊚   ]"] ;; 3 spaces are parsed as one node
               ["⊚[]"                        "◬"]
               ["[1⊚ 2 3 4]"                 "[⊚1]"]
               ["[1 ⊚2 3 4]"                 "[1⊚ ]"]
               ["[1 2 ⊚3 4]"                 "[1 2⊚ ]"]
               ["[1 2 3 ⊚4]"                 "[1 2 3⊚ ]"]
               ["[1 2]⊚ ; some comment"      "⊚[1 2]"]
               ["[⊚[1 2 3 4]]"               "⊚[]"]
               ["[1 2 3 4]⊚ 2"               "⊚[1 2 3 4]"]
               ["⊚[1 2 3 4] 5"               "◬"]
               ["[1 [2 3]⊚ 4 5]"             "[1 ⊚[2 3]]"]
               ["[1 [2 [3 [4]]]⊚ 5 6]"       "[1 ⊚[2 [3 [4]]]]"]
               ["[1\n[2⊚\n[3\n4]\n5]]"       "[1\n[⊚2]]"]
               ["[1\n[2\n[3 \n⊚  4]\n5]]"    "[1\n[2\n[3 ⊚\n]\n5]]"]
               ["[ \n  \n  \n ⊚1 2 3 4]"     "[ \n  \n  \n⊚ ]"]
               ["[ ⊚\n  \n 1 2 3 4]"         "[⊚ ]"]
               ["[ \n  ⊚\n 1 2 3 4]"         "[ \n⊚  ]"] ;; multiple spaces are a single node
               ["[ \n⊚  \n 1 2 3 4]"         "[ ⊚\n]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) before changes")
            (is (= expected (-> zloc pe/kill th/root-locmarked-string)))))))))

(deftest kill-at-pos-test
  ;; for this pos fn test, ⊚ in `s` represents character row/col for the `pos`
  ;; ⊚ in `expected` is at zipper node granularity
  (doseq [[sloc                                                                 expected]
          [["2 [⊚] 5"                                                           "2⊚ "]
           ["2 ⊚[] 5"                                                           "2⊚ "]
           ["2⊚ [] 5"                                                           "⊚2"]
           ["⊚2 [] 5"                                                           "◬"]
           ["41; dill⊚dall\n42"                                                 "41⊚; dill\n42"]
           ["(str \"He⊚llo \" \"World!\")"                                      "(str ⊚\"He\" \"World!\")" ]
           ["(str \"\nSecond line\n  Third⊚ Line\n    Fourth Line\n        \")" "(str ⊚\"\nSecond line\n  Third\")"]
           ["\n(println \"Hello⊚\n         There\n         World\")"            "\n(println ⊚\"Hello\")"]
           ["42 ⊚\"\""                                                          "42⊚ "]
           ["42 \"⊚\""                                                          "42⊚ "]
           ["7 ⊚\"foo\""                                                        "7⊚ "]
           ["7 \"foo⊚\""                                                        "7⊚ "]
           ["7 \"⊚foo\""                                                        "7 ⊚\"\""]
           ["\"\n⊚ \""                                                          "⊚\"\n\""]
           ["\"f⊚oo\""                                                          "⊚\"f\""]
           ["[:foo⊚ \"Hello World\"]"                                           "[⊚:foo]"]
           ["[:foo ⊚\"Hello World\"]"                                           "[:foo⊚ ]"]
           ["[:foo \"Hello ⊚World\"]"                                           "[:foo ⊚\"Hello \"]"]
           ["foo ⊚; dingo"                                                      "foo⊚ "]
           ["foo ;⊚; dingo"                                                     "foo ⊚;"]
           ["[1 2 3] ⊚;; dingo"                                                 "[1 2 3]⊚ "]
           ["[1 2 3] ;⊚; dingo"                                                 "[1 2 3] ⊚;"]
           ["[1 2 3]⊚ ;; dingo"                                                 "⊚[1 2 3]"]
           ["[1 2 3]⊚;; dingo"                                                  "⊚[1 2 3]"]
           [";; ding⊚o\ndog\n"                                                  "⊚;; ding\ndog\n"]
           [";; dingo⊚\ndog\n"                                                  "⊚;; dingo\ndog\n"]
           ["[1⊚ 2 3 4]"                                                        "[⊚1]"]
           ["[1⊚   2 3 4]"                                                      "[⊚1]"]
           ["[⊚;a comment\n \n]"                                                "⊚[]"]
           ["[\n ⊚\n ;a comment\n]"                                             "[\n⊚ ]"]
           ["42 ;; A comment⊚ of some length"                                   "42 ⊚;; A comment"]
           ["⊚[]"                                                               "◬"]
           ["[⊚]"                                                               "◬"]
           ["[\n⊚ ]"                                                            "[⊚\n]"]]]
    (let [{:keys [pos s]} (th/pos-and-s sloc)
          zloc (z/of-string* s {:track-position? true})]
      (doseq [pos [pos [(:row pos) (:col pos)]]]
        (testing (str (pr-str sloc) " @pos " pos)
          (is (= expected (-> zloc (pe/kill-at-pos pos) th/root-locmarked-string))))))))

(deftest kill-one-at-pos-test
  ;; for this pos fn test, ⊚ in `s` represents character row/col the the `pos`
  ;; ⊚ in `expected` is at zipper node granularity
  (doseq [[s                              expected]
          [["(+ ⊚100 200)"                "(⊚+ 200)"]
           ["(foo ⊚(bar do))"             "(⊚foo)"]
           ["[10⊚ 20 30]"                 "[⊚10 30]"]      ;; searches forward for node
           ["[10 ⊚20 30]"                 "[⊚10 30]"]
           ["[[10]⊚ 20 30]"               "[⊚[10] 30]"]    ;; searches forward for node
           ["[[10] ⊚20 30]"               "[⊚[10] 30]"]    ;; navigates left after delete when possible
           ["[10] [⊚20 30]"               "[10] ⊚[30]"]
           ["[⊚10\n 20\n 30]"             "⊚[20\n 30]"]
           ["[10\n⊚ 20\n 30]"             "[⊚10\n 30]"]
           ["[10\n 20\n⊚ 30]"             "[10\n ⊚20]"]
           ["[⊚10 20 30]"                 "⊚[20 30]"]
           ["⊚[10 20 30]"                 "◬"]
           ["32 [⊚]"                      "⊚32"]

           ;; in comment
           ["2 ; hello⊚ world"            "2 ⊚; hello world"]   ;; only kill word if word spans pos
           ["2 ; hello ⊚world"            "2 ⊚; hello "]        ;; at w of world, kill it
           ["2 ; ⊚hello world"            "2 ⊚;  world"]        ;; at h of hello, kill it
           ["2 ; hello worl⊚d"            "2 ⊚; hello "]        ;; at d of world, kill it
           ["2 ;⊚ hello world"            "2 ⊚; hello world"]   ;; not in any word, no-op
           ["2 ⊚; hello world"            "⊚2"]                 ;; kill comment node when at start of comment

           ;; in string
           ["3 \"hello⊚ world\""            "3 ⊚\"hello world\""] ;; not in word, no-op
           ["3 \"hello ⊚world\""            "3 ⊚\"hello \""]
           ["3 \"hello worl⊚d\""            "3 ⊚\"hello \""]
           ["3 \"⊚hello world\""            "3 ⊚\" world\""]
           ["3 ⊚\"hello world\""            "⊚3"]                 ;; at start quote, kill node
           ["3 \"hello world⊚\""            "⊚3"]                 ;; at end quote, kill node
           ["3 \"⊚foo bar do\n lorem\""     "3 ⊚\" bar do\n lorem\""]
           ["3 \"foo bar do\n⊚ lorem\""     "3 ⊚\"foo bar do\n lorem\""] ;; not in word, no-op
           ["3 \"foo bar do\n ⊚lorem\""     "3 ⊚\"foo bar do\n \""]
           ["3 \"foo bar ⊚do\n lorem\""     "3 ⊚\"foo bar \n lorem\""]]]
    (let [{:keys [pos s]} (th/pos-and-s s)
          zloc (z/of-string* s {:track-position? true})]
      (doseq [pos [pos [(:row pos) (:col pos)]]]
        (testing (str s " @pos " pos)
          (is (= expected (-> zloc (pe/kill-one-at-pos pos) th/root-locmarked-string))))))))

(deftest slurp-forward-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s expected]
              [["[[1 ⊚2] 3 4]"                        "[[1 ⊚2 3] 4]"]
               ["[[⊚1 2] 3 4]"                        "[[⊚1 2 3] 4]"]
               ["[[1⊚ 2] 3 4]"                        "[[1⊚ 2 3] 4]"]
               ["[⊚[] 1 2 3]"                         "[[⊚1] 2 3]"]
               ["[⊚[1] 2 3]"                          "[⊚[1] 2 3]"]
               ["[⊚[1] 2 3] 4"                        "[⊚[1] 2 3 4]"]
               ["[[1⊚ 2] 3 4]"                        "[[1⊚ 2 3] 4]"]
               ["[[[⊚1 2]] 3 4]"                      "[[[⊚1 2] 3] 4]"]
               ["[[[[[⊚1 2]]]] 3 4]"                  "[[[[[⊚1 2]]] 3] 4]"]
               ["[1 [⊚2 [3 4]] 5]"                    "[1 [⊚2 [3 4] 5]]"]
               ["[[1 [⊚2]] 3 4]"                      "[[1 [⊚2] 3] 4]"]
               ["(get ⊚:x) :a"                        "(get ⊚:x :a)"]
               ["(get ⊚{}) :a"                        "(get ⊚{} :a)"]
               ["(get ⊚{} :a)"                        "(get {⊚:a})"]
               ["(get ⊚{:a} :b)"                      "(get ⊚{:a} :b)"]
               ["⊚[#_uneval] :a"                      "⊚[#_uneval] :a"]
               ["[⊚#_uneval] :a"                      "[⊚#_uneval :a]"]
               ["(a ⊚[    ] b) c"                     "(a [    ⊚b]) c"]
               ["(a [  ⊚b   ] c) d"                   "(a [  ⊚b   c]) d"]
               ["(let [⊚dill]\n  {:a 1}\n  {:b 2})"   "(let [⊚dill\n{:a 1}]\n  {:b 2})"]
               ["(a [⊚foo]\n#_b  c)"                  "(a [⊚foo\n#_b]  c)"]
               ["(a ⊚b c);; comment\n(d e f)"         "(a ⊚b c ;; comment\n(d e f))"]]]
        (let [zloc (th/of-locmarked-string s opts)]
          (is (= s (th/root-locmarked-string zloc)) "(sanity) before changes")
          (is (= expected (-> zloc pe/slurp-forward th/root-locmarked-string))))))))

(deftest slurp-foward-into-test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      (doseq [[s                                      expected-from-parent               expected-from-current]
              [["[[1 ⊚2] 3 4]"                        "[[1 ⊚2 3] 4]"                     :ditto]
               ["[[⊚1 2] 3 4]"                        "[[⊚1 2 3] 4]"                     :ditto]
               ["[[1⊚ 2] 3 4]"                        "[[1⊚ 2 3] 4]"                     :ditto]
               ["[⊚[] 1 2 3]"                         "[⊚[] 1 2 3]"                      "[⊚[1] 2 3]"]
               ["[⊚[1] 2 3]"                          "[⊚[1] 2 3]"                       "[⊚[1 2] 3]"]
               ["[[⊚1] 2 3]"                          "[[⊚1 2] 3]"                       :ditto]
               ["[[1⊚ 2] 3 4]"                        "[[1⊚ 2 3] 4]"                     :ditto]
               ["[[[⊚1 2]] 3 4]"                      "[[[⊚1 2] 3] 4]"                   :ditto]
               ["[[[[[⊚1 2]]]] 3 4]"                  "[[[[[⊚1 2]]] 3] 4]"               :ditto]
               ["[1 [⊚2 [3 4]] 5]"                    "[1 [⊚2 [3 4] 5]]"                 :ditto]
               ["[[1 [⊚2]] 3 4]"                      "[[1 [⊚2] 3] 4]"                   :ditto]
               ["[:a :b [:c :d :e [⊚:f]]] :g"         "[:a :b [:c :d :e [⊚:f]] :g]"      :ditto]
               ["(get ⊚:x) :a"                        "(get ⊚:x :a)"                     :ditto]
               ["(get ⊚{}) :a"                        "(get ⊚{} :a)"                     :ditto]
               ["(get ⊚{} :a)"                        "(get ⊚{} :a)"                     "(get ⊚{:a})"]
               ["(get ⊚{:a} :b)"                      "(get ⊚{:a} :b)"                   "(get ⊚{:a :b})"]
               ["⊚[#_uneval] :a"                      "⊚[#_uneval] :a"                   "⊚[#_uneval :a]"]
               ["[⊚#_uneval] :a"                      "[⊚#_uneval :a]"                   "[⊚#_uneval :a]"]
               ["(a ⊚[    ] b) c"                     "(a ⊚[    ] b c)"                  "(a ⊚[    b]) c"]
               ["(a [  ⊚b   ] c) d"                   "(a [  ⊚b   c]) d"                 :ditto]
               ["(let [⊚dill]\n  {:a 1}\n  {:b 2})"   "(let [⊚dill\n{:a 1}]\n  {:b 2})"  :ditto]
               ["(a [⊚foo]\n#_b  c)"                  "(a [⊚foo\n#_b]  c)"               :ditto
               ["(a ⊚b c);; comment\n(d e f)"         "(a ⊚b c ;; comment\n(d e f))"     :ditto]]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res-from-parent (pe/slurp-forward-into zloc {:from :parent})
                res-default (pe/slurp-forward-into zloc)
                res-from-current (pe/slurp-forward-into zloc {:from :current})]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected-from-parent (th/root-locmarked-string res-default)) "root-string after default slurp")
            (is (= expected-from-parent (th/root-locmarked-string res-from-parent)) "root-string after from parent")
            (if (= :ditto expected-from-current)
              (is (= expected-from-parent (th/root-locmarked-string res-from-current)) "root-string after from current same as from parent")
              (is (= expected-from-current (th/root-locmarked-string res-from-current)) "root-string after from current"))))))))

(deftest slurp-foward-fully-into-test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      (doseq [[s                                      expected-from-parent          expected-from-current ]
              [["[1 [⊚2] 3 4]"                        "[1 [⊚2 3 4]]"                :ditto]
               ["[1 ⊚[] 2 3 4] 5"                     "[1 ⊚[] 2 3 4 5]"             "[1 ⊚[2 3 4]] 5"]
               ["[[[1 ⊚[] 2 3 4] 5 6] 7] 8"           "[[[1 ⊚[] 2 3 4 5 6]] 7] 8"   "[[[1 ⊚[2 3 4]] 5 6] 7] 8" ]
               ["[[1 ⊚[]] 3 4]"                       "[[1 ⊚[] 3 4]]"               "[[1 ⊚[3 4]]]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res-from-parent (pe/slurp-forward-fully-into zloc {:from :parent})
                res-default (pe/slurp-forward-fully-into zloc)
                res-from-current (pe/slurp-forward-fully-into zloc {:from :current})]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected-from-parent (th/root-locmarked-string res-default)) "root-string after default slurp")
            (is (= expected-from-parent (th/root-locmarked-string res-from-parent)) "root-string after from parent")
            (if (= :ditto expected-from-current)
              (is (= expected-from-parent (th/root-locmarked-string res-from-current)) "root-string after from current same as from parent")
              (is (= expected-from-current (th/root-locmarked-string res-from-current)) "root-string after from current"))))))))

(deftest slurp-foward-fully-test ;; deprecated but we still need to test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      (doseq [[s                                      expected]
              [["[1 [⊚2] 3 4]"                        "[1 [⊚2 3 4]]"]
               ["[1 ⊚[] 2 3 4]"                       "[1 [⊚2 3 4]]"]
               ["[[[1 ⊚[] 2 3 4] 5] 6] 7"             "[[[1 [⊚2 3 4]] 5] 6] 7"]
               ["[[1 ⊚[]] 3 4]"                       "[[1 [⊚3 4]]]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res (pe/slurp-forward-fully zloc)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected (th/root-locmarked-string res)) "root-string after")))))))

(deftest slurp-backward-into-test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      (doseq [[s                       expected-from-parent         expected-from-current]
              [["[1 2 [⊚3 4]]"         "[1 [2 ⊚3 4]]"               :ditto]
               ["[1 2 [3 ⊚4]]"         "[1 [2 3 ⊚4]]"               :ditto]
               ["[1 2 3 4 ⊚[]]"        "[1 2 3 4 ⊚[]]"              "[1 2 3 ⊚[4]]"]
               ["[1 2 [[3 ⊚4]]]"       "[1 [2 [3 ⊚4]]]"             :ditto]
               ["[1 2 [[[3 ⊚4]]]]"     "[1 [2 [[3 ⊚4]]]]"           :ditto]
               [":a [⊚[] :b]"          "[:a ⊚[] :b]"                :ditto]
               ["[:a ⊚[] :b]"          "[:a ⊚[] :b]"                "[⊚[:a] :b]"]
               ["[⊚:a [] :b]"          "[⊚:a [] :b]"                :ditto]
               ["[1 2 \n \n [⊚3 4]]"   "[1 [2\n\n⊚3 4]]"            :ditto]
               ["[1 2 ;dill\n [⊚3 4]]" "[1 [2 ;dill\n⊚3 4]]"        :ditto]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res-from-parent (pe/slurp-backward-into zloc {:from :parent})
                res-default (pe/slurp-backward-into zloc)
                res-from-current (pe/slurp-backward-into zloc {:from :current})]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected-from-parent (th/root-locmarked-string res-default)) "root-string after default slurp")
            (is (= expected-from-parent (th/root-locmarked-string res-from-parent)) "root-string after from parent")
            (if (= :ditto expected-from-current)
              (is (= expected-from-parent (th/root-locmarked-string res-from-current)) "root-string after from current same as from parent")
              (is (= expected-from-current (th/root-locmarked-string res-from-current)) "root-string after from current"))))))))

(deftest slurp-backward-test ;; deprecated but we still need to test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      (doseq [[s                       expected]
              [["[1 2 [⊚3 4]]"         "[1 [2 ⊚3 4]]"]
               ["[1 2 [3 ⊚4]]"         "[1 [2 3 ⊚4]]"]
               ["[1 2 3 4 ⊚[]]"        "[1 2 3 [⊚4]]"]
               ["[1 2 [[3 ⊚4]]]"       "[1 [2 [3 ⊚4]]]"]
               ["[1 2 [[[3 ⊚4]]]]"     "[1 [2 [[3 ⊚4]]]]"]
               [":a [⊚[] :b]"          "[:a ⊚[] :b]"]
               ["[:a ⊚[] :b]"          "[[⊚:a] :b]"]
               ["[⊚:a [] :b]"          "[⊚:a [] :b]"]
               ["[1 2 \n \n [⊚3 4]]"   "[1 [2\n\n⊚3 4]]"]
               ["[1 2 ;dill\n [⊚3 4]]" "[1 [2 ;dill\n⊚3 4]]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res (pe/slurp-backward zloc)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected (th/root-locmarked-string res)) "root-string after")))))))

(deftest slurp-backward-fully-into-test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      (doseq [[s                          expected-from-parent    expected-from-current]
              [["[1 2 3 [⊚4] 5]"          "[[1 2 3 ⊚4] 5]"        :ditto]
               ["[1 2 3 4 ⊚[] 5]"         "[1 2 3 4 ⊚[] 5]"       "[⊚[1 2 3 4] 5]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res-from-parent (pe/slurp-backward-fully-into zloc {:from :parent})
                res-default (pe/slurp-backward-fully-into zloc)
                res-from-current (pe/slurp-backward-fully-into zloc {:from :current})]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected-from-parent (th/root-locmarked-string res-default)) "root-string after default slurp")
            (is (= expected-from-parent (th/root-locmarked-string res-from-parent)) "root-string after from parent")
            (if (= :ditto expected-from-current)
              (is (= expected-from-parent (th/root-locmarked-string res-from-current)) "root-string after from current same as from parent")
              (is (= expected-from-current (th/root-locmarked-string res-from-current)) "root-string after from current"))))))))

(deftest slurp-backward-fully-test ;; deprecated but we still need to test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      (doseq [[s                          expected]
              [["[1 2 3 [⊚4] 5]"          "[[1 2 3 ⊚4] 5]"]
               ["[1 2 3 4 ⊚[] 5]"         "[[⊚1 2 3 4] 5]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res (pe/slurp-backward-fully zloc)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected (th/root-locmarked-string res)) "root-string after")))))))

(deftest barf-forward-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s                                                   expected]
              [["[[1 ⊚2 3] 4]"                                     "[[1 ⊚2] 3 4]"]
               ["[[⊚1 2 3] 4]"                                     "[[⊚1 2] 3 4]" ]
               ["[[1 2 ⊚3] 4]"                                     "[[1 2] ⊚3 4]"]
               ["[[1 2 3⊚ ] 4]"                                    "[[1 2] ⊚3 4]"]
               ["[[1 2⊚ 3] 4]"                                     "[[1 2] ⊚3 4]"]
               ["[[⊚1] 2]"                                         "[[] ⊚1 2]"]
               ["(⊚(x) 1)"                                         "(⊚(x)) 1"]
               ["(⊚(x)1)"                                          "(⊚(x)) 1"]
               ["(⊚(x)(y))"                                        "(⊚(x)) (y)"]
               ["[⊚{:a 1} {:b 2} {:c 3}]"                          "[⊚{:a 1} {:b 2}] {:c 3}"]
               ["[{:a 1} ⊚{:b 2} {:c 3}]"                          "[{:a 1} ⊚{:b 2}] {:c 3}"]
               ["[{:a 1} {:b 2} ⊚{:c 3}]"                          "[{:a 1} {:b 2}] ⊚{:c 3}"]
               ["[⊚1 ;; comment\n2]"                               "[⊚1];; comment\n2"]
               ["[1 ⊚;; comment\n2]"                               "[1];; comment\n⊚2"]
               ["[1 ;; comment\n⊚2]"                               "[1];; comment\n⊚2"]
               ["[1 ;; comment\n⊚2]"                               "[1];; comment\n⊚2"]
               ["[1 ;; cmt1\n;; cmt2\n⊚2]"                         "[1];; cmt1\n;; cmt2\n⊚2"]
               ["[1 \n   \n;; cmt1\n  \n;; cmt2\n   \n\n  ⊚2]"     "[1]\n\n;; cmt1\n\n;; cmt2\n\n\n⊚2"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) string before")
            (is (= expected (-> zloc pe/barf-forward th/root-locmarked-string)) "root string after")))))))

(deftest barf-backward-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s                                                   expected]
              [["[1 [2 ⊚3 4]]"                                    "[1 2 [⊚3 4]]"]
               ["[1 [2 3 ⊚4]]"                                    "[1 2 [3 ⊚4]]"]
               ["[1 [⊚2 3 4]]"                                    "[1 ⊚2 [3 4]]"]
               ["[1 [2⊚ 3 4]]"                                    "[1 ⊚2 [3 4]]"]
               ["[1 [⊚ 2 3 4]]"                                   "[1 ⊚2 [3 4]]"]
               ["[1 [⊚2]]"                                        "[1 ⊚2 []]"]
               ["(1 ⊚(x))"                                        "1 (⊚(x))"]
               ["(1⊚(x))"                                         "1 (⊚(x))"]
               ["((x)⊚(y))"                                       "(x) (⊚(y))"]
               ["[{:a 1} {:b 2} ⊚{:c 3}]"                         "{:a 1} [{:b 2} ⊚{:c 3}]"]
               ["[{:a 1} ⊚{:b 2} {:c 3}]"                         "{:a 1} [⊚{:b 2} {:c 3}]"]
               ["[⊚{:a 1} {:b 2} {:c 3}]"                         "⊚{:a 1} [{:b 2} {:c 3}]"]
               ["[1 ;; comment\n⊚2]"                              "1 ;; comment\n[⊚2]"]
               ["[1 ⊚;; comment\n2]"                              "⊚1 ;; comment\n[2]"]
               ["[⊚1 ;; comment\n2]"                              "⊚1 ;; comment\n[2]"]
               ["[⊚1 ;; cmt1\n;; cmt2\n2]"                        "⊚1 ;; cmt1\n;; cmt2\n[2]"]
               ["[⊚1 \n   \n;; cmt1\n  \n;; cmt2\n   \n\n  2]"    "⊚1 \n\n;; cmt1\n\n;; cmt2\n\n\n[2]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) string before")
            (is (= expected (-> zloc pe/barf-backward th/root-locmarked-string)) "root string after")))))))

(deftest wrap-around-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s                 t           expected]
              [["⊚1"             :list       "(⊚1)"]
               ["⊚1"             :vector     "[⊚1]"]
               ["⊚1"             :map        "{⊚1}"]
               ["⊚1"             :set        "#{⊚1}"]
               ["[⊚1\n 2]"       :vector     "[[⊚1]\n 2]"]
               ["(-> ⊚#(+ 1 1))" :list       "(-> (⊚#(+ 1 1)))"]]]
        (let [zloc (th/of-locmarked-string s opts)]
          (is (= s (th/root-locmarked-string zloc)) "(sanity) string before")
          (is (= expected (-> zloc (pe/wrap-around t) th/root-locmarked-string)) "string after"))))))

(deftest wrap-fully-forward-slurp-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s               t           expected]
              [["[1 ⊚2 3 4]"   :vector     "[1 [⊚2 3 4]]"]
               ["[1 ⊚2 3 4]"   :map        "[1 {⊚2 3 4}]"]
               ["[1 ⊚2 3 4]"   :list       "[1 (⊚2 3 4)]"]
               ["[1 ⊚2 3 4]"   :set        "[1 #{⊚2 3 4}]"]
               ["[1 ⊚2]"       :list       "[1 (⊚2)]"]
               ["⊚[]"          :list       "(⊚[])"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) string before")
            (is (= expected (-> zloc (pe/wrap-fully-forward-slurp t) th/root-locmarked-string)) "string after")))))))

;; TODO what about comments?
(deftest splice-killing-backward-test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts" opts)
      (doseq [[s                                              expected]
              [["(foo (let ((x 5)) ⊚(sqrt n)) bar)"           "(foo ⊚(sqrt n) bar)"]
               ["(  a  (  b  c  ⊚d  e  f)  g)"                "(  a  ⊚d  e  f  g)"]
               ["(  [a]  (  [b]  [c]  ⊚[d]  [e]  [f])  [g])"  "(  [a]  ⊚[d]  [e]  [f]  [g])"]
               ["(  [a]  (  [b]  [c]  [d]  [e]  ⊚[f])  [g])"  "(  [a]  ⊚[f]  [g])"]
               ["(  (⊚ )  [g])"                               "(  ⊚[g])"]
               ["(  [a]  (⊚ ))"                               "(  ⊚[a])"]
               ["(  (⊚ ))"                                    "⊚()"]
               ["[⊚1]"                                        "⊚1"]
               ["[⊚1 2]"                                      "⊚1 2"]
               ["[1 2 ⊚3 4 5]"                                "⊚3 4 5"]
               ["[1 2⊚ 3 4 5]"                                "⊚3 4 5"]
               ["[1 2 3 4 5⊚ ]"                               "◬"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res (pe/splice-killing-backward zloc)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected (th/root-locmarked-string res)) "root-string after")))))))

;; TODO what about comments?
(deftest splice-killing-forward-test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts" opts)
      (doseq [[s                                              expected]
              [["(a (b c ⊚d e f) g)"                          "(a b ⊚c g)"]
               ["(a (⊚b c d e) f)"                            "(⊚a f)"]
               ["(  a  (  b  c  ⊚d  e  f)  g)"                "(  a  b  ⊚c  g)"]
               ["(  [a]  (  [b]  [c]  ⊚[d]  [e]  [f])  [g])"  "(  [a]  [b]  ⊚[c]  [g])"]
               ["(  [a]  (  ⊚[b]  [c]  [d]  [e]  [f])  [g])"  "(  ⊚[a]  [g])"]
               ["(  (  ⊚[b]  [c]  [d]  [e]  [f])  [g])"       "(  ⊚[g])"]
               ["(  [a]  (  ⊚[b]  [c]  [d]  [e]  [f]))"       "(  ⊚[a])"]
               ["(  (  ⊚[b]  [c]  [d]  [e]  [f]))"            "⊚()"]
               ["(  (⊚ )  [g])"                               "(  ⊚[g])"]
               ["(  [a]  (⊚ ))"                               "(  ⊚[a])"]
               ["(  (⊚ ))"                                    "⊚()"]
               ["[⊚1]"                                        "◬"]
               ["[⊚1 2]"                                      "◬"]
               ["[1 2 ⊚3 4 5]"                                "1 ⊚2"]
               ["[1 2⊚ 3 4 5]"                                "1 ⊚2"]
               ["[ ⊚1 2 3 4 5 ]"                              "◬"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)
                res (pe/splice-killing-forward zloc)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) s before change")
            (is (= expected (th/root-locmarked-string res)) "root-string after")))))))

(deftest split-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s                                       expected]
              [["[⊚1 2]"                               "[⊚1] [2]"]
               ["[1 2 ⊚3 4 5]"                         "[1 2 ⊚3] [4 5]"]
               ["[1 ⊚2 3 4]"                           "[1 ⊚2] [3 4]"]
               ["[1 2⊚ 3 4]"                           "[1 ⊚2] [3 4]"]
               ["[⊚1 2 3 4 5]"                         "[⊚1] [2 3 4 5]"]
               ["[⊚1]"                                 "[⊚1]"]         ;; no-op
               ["[1 2 3 4 ⊚5]"                         "[1 2 3 4 ⊚5]"] ;; no-op
               ["{⊚:a 1 :b 2}"                         "{⊚:a} {1 :b 2}"]
               ["(foo ⊚bar baz boop)"                  "(foo ⊚bar) (baz boop)"]
               ["#{:a ⊚:b :c}"                         "#{:a ⊚:b} #{:c}"]
               ["[⊚1 ;dill\n]"                         "[⊚1 ;dill\n]"] ;; no-op
               ["\n[1 ;dill\n ⊚2 ;dall\n 3 ;jalla\n]"  "\n[1 ;dill\n ⊚2 ;dall\n] [3 ;jalla\n]"]]]
        (testing s
          (let [zloc (th/of-locmarked-string s opts)]
            (is (= s (th/root-locmarked-string zloc)) "(sanity) string before")
            (is (= expected (-> zloc pe/split th/root-locmarked-string)) "string after")))))))

(deftest split-at-pos-test
  ;; for this pos fn test, ⊚ in `s` represents character row/col the the `pos`
  ;; ⊚ in `expected` is at zipper node granularity
  (doseq [[s                                       expected]
          [["(\"Hello ⊚World\" 42)"                "(⊚\"Hello \" \"World\" 42)"]
           ["(\"⊚Hello World\" 101)"               "(⊚\"\" \"Hello World\" 101)"]
           ["(\"H⊚ello World\" 101)"               "(⊚\"H\" \"ello World\" 101)"]
           ["(\"Hello World⊚\" 101)"               "(⊚\"Hello World\") (101)"]
           ["bingo bango (\"Hello\n Wor⊚ld\" 101)" "bingo bango (⊚\"Hello\n Wor\" \"ld\" 101)"]
           ["(⊚\"Hello World\" 101)"               "(⊚\"Hello World\") (101)"]]]
    (let [{:keys [pos s]} (th/pos-and-s s)
          zloc (z/of-string* s {:track-position? true})]
      (doseq [pos [pos [(:row pos) (:col pos)]]]
        (testing (str s " @pos " pos)
          (is (= expected (-> zloc (pe/split-at-pos pos) th/root-locmarked-string))))))))

(deftest join-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s                                                            expected]
              [["[1 2]⊚ [3 4]"                                              "[1 2 ⊚3 4]"]
               ["#{1 2} ⊚[3 4]"                                             "#{1 2 ⊚3 4}"]
               ["(1 2)⊚ {3 4}"                                              "(1 2 ⊚3 4)"]
               ["{:a 1} ⊚(:b 2)"                                            "{:a 1 ⊚:b 2}"]
               ["[foo]⊚[bar]"                                               "[foo ⊚bar]"]
               ["[foo]   ⊚[bar]"                                            "[foo   ⊚bar]"]
               ["\n[[1 2]⊚ ; the first stuff\n [3 4] ; the second stuff\n]" "\n[[1 2 ; the first stuff\n ⊚3 4] ; the second stuff\n]"]
               ;; strings
               ["(\"Hello \" ⊚\"World\")"                                   "(⊚\"Hello World\")"]
               ["(⊚\"Hello \" \"World\")" "(⊚\"Hello \" \"World\")"]
               ["(\"Hello \" ;; comment\n;; comment2\n⊚\"World\")"
                "(⊚\"Hello World\" ;; comment\n;; comment2\n)"]
               ["\"foo\"⊚\"bar\""         "⊚\"foobar\""]]]
        (let [zloc (th/of-locmarked-string s opts)]
          (is (= s (th/root-locmarked-string zloc)) "(sanity) string before")
          (is (= expected (-> zloc pe/join th/root-locmarked-string)) "string after"))))))

(deftest raise-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (is (= "[1 ⊚3]"
             (-> (th/of-locmarked-string "[1 [2 ⊚3 4]]" opts)
                 pe/raise
                 th/root-locmarked-string))))))

(deftest move-to-prev-test
  (doseq [opts zipper-opts]
    (testing (zipper-opts-desc opts)
      (doseq [[s                          expected]
              [["(+ 1 ⊚2)"                "(+ ⊚2 1)"]
               ["(+ 1 (+ 2 3) ⊚4)"        "(+ 1 (+ 2 3 ⊚4))"]
               ["(+ 1 (+ 2 3 ⊚4))"        "(+ 1 (+ 2 ⊚4 3))"]
               ["(+ 1 (+ 2 ⊚4 3))"        "(+ 1 (+ ⊚4 2 3))"]
               ["(+ 1 (+ ⊚4 2 3))"        "(+ 1 (⊚4 + 2 3))"]
               ["(+ 1 (⊚4 + 2 3))"        "(+ 1 ⊚4 (+ 2 3))"]]]
        (let [zloc (th/of-locmarked-string s opts)]
          (is (= s (th/root-locmarked-string zloc)) "(sanity) string before")
          (is (= expected (-> zloc pe/move-to-prev th/root-locmarked-string)) "string after"))))))

(deftest ops-on-changed-zipper-test
  (doseq [opts zipper-opts]
    (testing (str "zipper opts " opts)
      ;; create our zipper dynamically to avoid any reader metadata
      ;; we used to rely on this metadata and it was a problem
      ;; see https://github.com/clj-commons/rewrite-clj/issues/256
      (let [zloc (-> (z/of-node (n/forms-node
                                  [(n/token-node 'foo) (n/spaces 1)
                                   (n/list-node
                                     [(n/token-node 'bar) (n/spaces 1)
                                      (n/token-node 'baz) (n/spaces 1)
                                      (n/vector-node
                                        [(n/token-node 1) (n/spaces 1)
                                         (n/token-node 2)])
                                      (n/spaces 1)
                                      (n/vector-node
                                        [(n/token-node 3) (n/spaces 1)
                                         (n/token-node 4)])
                                      (n/spaces 1)
                                      (n/keyword-node :bip) (n/spaces 1)
                                      (n/keyword-node :bop)])
                                   (n/spaces 1)
                                   (n/token-node :bap)])
                                opts)
                     z/right z/down z/right z/right z/down)]
        ;;               1         2         3         4
        ;;      12345678901234567890123456789012345678901
        (is (= "foo (bar baz [⊚1 2] [3 4] :bip :bop) :bap" (th/root-locmarked-string zloc)) "(sanity) before")
        (is (= "foo (bar baz ⊚1 [2] [3 4] :bip :bop) :bap" (-> zloc pe/barf-backward th/root-locmarked-string)))
        (is (= "foo (bar baz [⊚1] 2 [3 4] :bip :bop) :bap" (-> zloc pe/barf-forward th/root-locmarked-string)))
        (is (= "foo (bar baz [1 2 ⊚3 4] :bip :bop) :bap" (-> zloc z/up z/right pe/join th/root-locmarked-string)))
        (is (= "foo (bar baz ⊚[] [3 4] :bip :bop) :bap" (-> zloc pe/kill th/root-locmarked-string)))
        (when (:track-position? opts)
          (is (= "foo (bar baz [1 2] [3 4]⊚ ) :bap" (-> zloc (pe/kill-at-pos {:row 1 :col 28}) th/root-locmarked-string))))
        (is (= "foo (bar baz ⊚1 [2] [3 4] :bip :bop) :bap" (-> zloc pe/move-to-prev th/root-locmarked-string)))
        (is (= "foo (bar baz ⊚1 [3 4] :bip :bop) :bap" (-> zloc pe/raise th/root-locmarked-string)))
        (is (= "foo (bar [baz ⊚1 2] [3 4] :bip :bop) :bap" (-> zloc pe/slurp-backward th/root-locmarked-string)))
        (is (= "foo ([bar baz ⊚1 2] [3 4] :bip :bop) :bap" (-> zloc pe/slurp-backward-fully th/root-locmarked-string)))
        (is (= "foo (bar baz [⊚1 2 [3 4]] :bip :bop) :bap" (-> zloc pe/slurp-forward th/root-locmarked-string)))
        (is (= "foo (bar baz [1 2] [⊚3 4 :bip :bop]) :bap" (-> zloc z/up z/right z/down pe/slurp-forward-fully th/root-locmarked-string)))
        (is (= "foo (bar baz ⊚1 2 [3 4] :bip :bop) :bap" (-> zloc z/up pe/splice th/root-locmarked-string)))
        (is (= "foo (bar baz ⊚2 [3 4] :bip :bop) :bap" (-> zloc z/right pe/splice-killing-backward th/root-locmarked-string)))
        (is (= "foo (bar baz ⊚2 [3 4] :bip :bop) :bap" (-> zloc z/right pe/splice-killing-backward th/root-locmarked-string)))
        (is (= "foo (bar baz [⊚1] [2] [3 4] :bip :bop) :bap" (-> zloc pe/split th/root-locmarked-string)))
        (when (:track-position? opts)
          (is (= "foo (bar baz [1 2] [⊚3] [4] :bip :bop) :bap" (-> zloc (pe/split-at-pos {:row 1 :col 22}) th/root-locmarked-string))))
        (is (= "foo (bar baz [#{⊚1} 2] [3 4] :bip :bop) :bap" (-> zloc (pe/wrap-around :set) th/root-locmarked-string)))
        (is (= "foo (bar baz [{⊚1 2}] [3 4] :bip :bop) :bap" (-> zloc (pe/wrap-fully-forward-slurp :map) th/root-locmarked-string)))))))
