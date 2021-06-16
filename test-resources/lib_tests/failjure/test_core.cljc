(ns failjure.test-core
  (:require #?@(:clj [[clojure.test :refer :all]
                      [failjure.core :as f]]
                :cljs [[cljs.test :refer-macros [deftest testing is]]
                       [failjure.core :as f :include-macros true]])))

(deftest failjure-core-test
  (testing "attempt"
    (let [handle-error #(str "Error: " (f/message %))]
    (is (= "Ok" (f/attempt handle-error "Ok")))
    (is (= "Error: failed" (f/attempt handle-error (f/fail "failed"))))))

  (testing "attempt-all"
    (testing "basically works"
      (is (= "Ok"
             (f/attempt-all [x "O"
                             y "k"]
                            (str x y)))))

                                        ; Fails
    (testing "Returns/short-circuits on failures"
      (is (= (f/fail "k")
             (f/attempt-all [x "O"
                             y (f/fail "k")
                             _ (is false "Did not short-circuit")
                             ]
                            (str x y)))))

    (testing "Returns the exception for try*"
      (let [result (f/attempt-all [x "O"
                                y (f/try* #?(:clj (Integer/parseInt "k")
                                             :cljs (throw (js/Error. "Fails."))))
                                z (is false "Did not short-circuit")]
                                (str x y z))]
        (is (f/failed? result))
        #?(:clj (is (instance? NumberFormatException result))
           :cljs (is (instance? js/Error result)))))

    (testing "Runs when-failed"
                                        ; Runs when-failed
      (is (= "Fail"
             (f/attempt-all [x "O"
                           y (f/fail "Fail")
                           z "!"]
                          (str x y z)
                          (f/when-failed [e]
                            (f/message e))))))

    (testing "Runs if-failed (which is DEPRECATED but still supported)"
                                        ; Runs if-failed
      (is (= "Fail"
             (f/attempt-all [x "O"
                           y (f/fail "Fail")
                           z "!"]
                          (str x y z)
                          (f/if-failed [e]
                            (f/message e))))))

    #?(:clj
       (testing "attempt-all does not catch exceptions automatically"
         (is (= "Caught"
                (try
                  (f/attempt-all [x "O"
                                y (Integer/parseInt "k")]
                               "Ok"
                               "Failed")
                  (catch NumberFormatException e "Caught")))))
       :cljs
       (testing "attempt-all does not catch exceptions automatically"
         (is (= "Caught"
                (try
                  (f/attempt-all [x "O"
                                y (throw (js/Error. "Some error"))]
                               "Ok"
                               "Failed")
                  (catch :default e "Caught"))))))

    (testing "Destructuring in fail cases should return the failure"
      (is (= (f/fail "Fail")
             (f/attempt-all [x "1"
                           {:keys [y]} (f/fail "Fail")]
                          y))))

    (testing "try-all safely catches an exception in the bindings"
      (is (f/failed? (f/try-all [x #?(:clj (/ 4 0) :cljs (throw (js/Error. "Error")))
                                 y (+ 3 4)]
                                (+ x y)))))

    (testing "try-all safely catches an exception in the bindings with else clause"
      (is (= "Divide by zero"
             (f/try-all [x #?(:clj (/ 4 0) :cljs (throw (js/Error. "Divide by zero")))
                         y (+ 3 4)]
                        (+ x y)
                        (f/when-failed [err] (f/message err))))))
    )

                                        ; Test ok-> (and therefore attempt->)
  (testing "ok->"
    (is (= "Ok!"
           (f/ok->
            ""
            (str "O")
            (str "k")
            (str "!"))))

    (is (= (f/fail "Not OK!")
           (f/ok->
            ""
            (str "Not OK!")
            (f/fail)
            (str "kay-O!")
            (reverse))))

                                        ; Ensure the double-eval bug goes away
    (let [a (atom 0)]
      (is (= 2
             (f/ok->
              (swap! a inc)
              inc)))
      (is (= 3
             (f/ok->>
              (swap! a inc)
              inc)))))

  (testing "as-ok->"
    (is (= "Ok!"
           (f/as-ok-> "k" a
                      (str a "!")
                      (str "O" a))))
    (is (= "foo"
           (let [func (constantly (f/fail "foo"))
                 result (f/as-ok-> "k" a
                                   (str a "!")
                                   (func a)
                                   (str "O" a))]
                (f/message result))))
    (is (= "foo"
           (let [func (constantly (f/fail "foo"))
                 result (f/as-ok-> "k" a
                                   (str a "!")
                                   (str "O" a)
                                   (func a))]
                (f/message result)))))

                                        ; Test attempt->>
  (testing "ok->>"
    (is (= "Ok"
          (f/ok->>
            ""
            (str "k")
            (str "O"))))

      (is (= (f/fail "Not OK!")
             (f/ok->>
               ""
               (str "Not OK!")
               (f/fail)
               (str "O")
               (reverse)))))

    (testing "failed?"
      (testing "failed? is valid on nullable"
        (is (false? (f/failed? nil)))
        (is (= "nil" (f/message nil))))

      (testing "failed? is valid on exception"
        (is (true? (f/failed? #?(:clj (Exception. "Failed")
                                 :cljs (js/Error. "Failed")))))
        (is (= "My Message" (f/message #?(:clj (Exception. "My Message")
                                          :cljs (js/Error. "My Message"))))))

      (testing "failed? is valid on failure"
        (is (true? (f/failed? (f/fail "You failed."))))
        (is (= "You failed." (f/message (f/fail "You failed."))))))

    (testing "if-let-ok?"

      (is (= "Hello"
             (f/if-let-ok? [v "Hello"] v)))

      (is (f/failed?
            (f/if-let-ok? [v (f/fail "FAIL")] "OK")))

      (is (= "Hello"
             (f/if-let-ok? [v :ok] "Hello" "Goodbye")))

      (is (= "Goodbye"
             (f/if-let-ok? [v (f/fail "Hello")] "Hello" "Goodbye")))

      (is (= (f/fail "Fail")
             (f/if-let-failed? [{:keys [y]} (f/fail "Something went wrong!")]
                              (f/fail "Fail")))))

    (testing "when-let-ok?"
      (let [result (atom nil)]
        (is (= "Hello"
               (f/when-let-ok? [v "Hello"]
                 (reset! result :ok)
                 v)))
        (is (= :ok @result)))

      (let [result (atom nil)]
        (is (f/failed?
              (f/when-let-ok? [v (f/fail "FAIL")]
                (reset! result :ok)
                "OK")))
        (is (nil? @result))
        )
      )

    (testing "if-let-failed?"

      (is (= "Hello"
             (f/if-let-failed? [v "Hello"] "FAILED" v)))

      (is (f/failed?
            (f/if-let-failed? [v (f/fail "FAIL")] v)))

      (is (f/ok?
            (f/if-let-failed? [v "Didn't fail"] v)))

      (is (= "Goodbye"
             (f/if-let-failed? [v :ok] "Hello" "Goodbye")))

      (is (= "Hello"
             (f/if-let-failed? [v (f/fail "Hello")] "Hello" "Goodbye")))
      )

    (testing "when-let-failed?"
      (let [result (atom nil)]
        (is (= "Hello"
              (f/when-let-failed?
                [v "Hello"]
                (reset! result :ok)
                v)))
        (is (nil? @result)))

      (let [result (atom nil)]
        (is (= "OK"
              (f/when-let-failed?
                [v (f/fail "FAIL")]
                (reset! result :ok)
                "OK")))
        (is (= :ok @result))))

  (testing "Assertions"
    ;; assert-some? basically covers everything
    (testing "Assert some?"
      (is (= (f/fail "msg") (f/assert-some? nil "msg")))
      (is (= "it" (f/assert-some? "it" "msg"))))))




(comment
  (run-tests)
  )
