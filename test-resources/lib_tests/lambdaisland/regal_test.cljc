(ns lambdaisland.regal-test
  (:require [lambdaisland.regal :as regal]
            [lambdaisland.regal.spec-alpha]
            [lambdaisland.regal.generator :as regal-gen]
            [lambdaisland.regal.test-util :as test-util]
            ;; BB-TEST-PATCH: bb can't load ns
            #_[lambdaisland.regal.parse :as parse]
            [clojure.spec.test.alpha :as stest]
            [clojure.test :refer [deftest testing is are]]
            [clojure.spec.alpha :as s]))

(stest/instrument `regal/regex)

(deftest regex-test
  (is (= "abc"
         (regal/pattern [:cat "a" "b" "c"])))

  (is (= "a|b|c"
         (regal/pattern [:alt "a" "b" "c"])))

  (is (= "a*"
         (regal/pattern [:* "a"])))

  (is (= "(?:ab)*"
         (regal/pattern [:* "ab"])))

  (is (= "(?:ab)*"
         (regal/pattern [:* "a" "b"])))

  (is (= "(?:a|b)*"
         (regal/pattern [:* [:alt "a" "b"]])))

  (is (= "a*?"
         (regal/pattern [:*? "a"])))

  (is (= "(?:ab)*?"
         (regal/pattern [:*? "ab"])))

  (is (= "(?:ab)*?"
         (regal/pattern [:*? "a" "b"])))

  (is (= "(?:a|b)*?"
         (regal/pattern [:*? [:alt "a" "b"]])))

  (is (= "a+"
         (regal/pattern [:+ "a"])))

  (is (= "a+?"
         (regal/pattern [:+? "a"])))

  (is (= "a?"
         (regal/pattern [:? "a"])))

  (is (= "a??"
         (regal/pattern [:?? "a"])))

  (is (= "[a-z0-9_\\-]"
         (regal/pattern [:class [\a \z] [\0 \9] \_ \-])))

  (is (= "[^a-z0-9_\\-]"
         (regal/pattern [:not [\a \z] [\0 \9] \_ \-])))

  (is (= "a{3,5}"
         (regal/pattern [:repeat \a 3 5])))

  (regal/with-flavor :ecma
    (is (= "^a$"
           (regal/pattern [:cat :start \a :end]))))

  (regal/with-flavor :java
    (is (= "^a$"
           (regal/pattern [:cat :start \a :end]))))

  (is (= "a(?:b|c)"
         (regal/pattern [:cat "a" [:alt "b" "c"]])))

  (is (= "(abc)"
         (regal/pattern [:capture "abc"])))

  (is (= "a(b|c)"
         (regal/pattern [:cat "a" [:capture [:alt "b" "c"]]]))))

(deftest escape-test
  (are [in out] (= out (regal/escape in))
    "$" "\\$"
    "(" "\\("
    ")" "\\)"
    "*" "\\*"
    "+" "\\+"
    "." "\\."
    "?" "\\?"
    "[" "\\["
    "]" "\\]"
    "\\" "\\\\"
    "^" "\\^"
    "{" "\\{"
    "|" "\\|"
    "}" "\\}"))


(def flavors [:java8 :java9 :ecma :re2])

(def parseable-flavor? #{:java8 :java9 :ecma})

(deftest data-based-tests
  (doseq [{:keys [id cases]} (test-util/test-cases)
          {:keys [form pattern equivalent tests] :as test-case} cases
          :let [skip? (set (when (map? pattern)
                            (for [flavor flavors
                                  :when (= (get pattern flavor :skip) :skip)]
                              flavor)))
                throws? (set (when (map? pattern)
                               (for [[flavor p] pattern
                                     :when (and (vector? p) (= (first p) :throws))]
                                 flavor)))]]

    (testing (str (pr-str form) " -> " (pr-str pattern))
      (is (s/valid? ::regal/form form))

      (doseq [flavor flavors
              :when (not (skip? flavor))
              :let [pattern (if (map? pattern)
                              (some pattern (test-util/flavor-parents flavor))
                              pattern)]]
        (if (throws? flavor)
          (testing (str "Generating pattern throws (" (name id) ") " (pr-str form) " (" flavor ")")
            (if-some [msg (second pattern)]
              (is (thrown-with-msg? #?(:clj  Exception
                                             :cljs js/Error) (re-pattern msg)
                                    (regal/with-flavor flavor
                                      (regal/pattern form))))
              (is (thrown? #?(:clj  Exception
                                    :cljs js/Error)
                           (regal/with-flavor flavor
                             (regal/pattern form))))))
          (testing (str "Generated pattern is correct (" (name id) ") " (pr-str form) " (" flavor ")")
            (regal/with-flavor flavor
              (is (= pattern (regal/pattern form))))))

        ;; BB-TEST-PATCH: Uses ns that can't load
        #_(when (and (parseable-flavor? flavor)
                     (not-any? (comp :no-parse meta) [test-case cases]))
            (testing (str "Pattern parses correctly (" (name id) ") " (pr-str pattern) " (" flavor ")")
              (regal/with-flavor flavor
                (is (= form (parse/parse-pattern pattern)))))))

      (doseq [[input match] tests]
        (testing (str "Test case " (pr-str form) " matches " (pr-str input))

          (testing "Generated pattern matches"
            (is (= match (re-find (regal/regex form) input))))
          ;; BB-TEST-PATCH: Uses ns that can't load
          #_(:clj
             (when-not (or (skip? :re2) (throws? :re2))
               (testing "Generated pattern matches (re2)"
                 (is (= match (test-util/re2-find (regal/with-flavor :re2
                                          (test-util/re2-compile
                                           (regal/pattern form)))
                                        input))))))

          (doseq [pattern (if (map? equivalent)
                            (some equivalent (test-util/flavor-parents (regal/runtime-flavor)))
                            equivalent)]
            (testing (str "Alternative equivalent pattern " (pr-str pattern) " matches")
              (is (= match (re-find (regal/compile pattern) input)))))))

      (testing (str "creating a generator does not throw exception " (pr-str form))
        (is (regal-gen/gen form)))

      ;; We should do this with proper properties so we get shrinking, just a
      ;; basic check for now
      (testing (str "generated strings match the given pattern " (pr-str form))
        (doseq [s (regal-gen/sample form)]
          (is (re-find (regal/regex form) s)))))))
