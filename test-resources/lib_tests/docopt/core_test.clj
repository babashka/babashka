(ns docopt.core-test
  (:require [cheshire.core :as json]
            [clojure.string :as s]
            [clojure.test :refer :all]
            [docopt.core :as d]
            [docopt.match :as m]))

(def doc-block-regex
  (let [doc-begin  "r\\\"{3}"
        doc-body   "((?:\\\"{0,2}[^\\\"]+)*)"
        separator  "\\\"{3}\n+"
        tests      "((?:[^r]|r(?!\\\"{3}))*)"]
    (re-pattern (str doc-begin doc-body separator tests))))

(def test-block-regex
  (let [input-begin "(?:\\A|\\n+)\\s*\\$\\s*prog"
        input-body  "(.*)"
        separator   "\\n"
        tests       "((?:.+\\n)*)"]
    (re-pattern (str input-begin input-body separator tests))))

(defn load-test-cases
  "Loads language-agnostic docopt tests from file (such as testcases.docopt)."
  [path]
  (into [] (mapcat (fn [[_ doc tests]]
                   (map (fn [[_ args result]]
                          [doc (into [] (filter seq (s/split (or args "") #"\s+"))) (json/parse-string result)])
                        (re-seq test-block-regex tests)))
                 (re-seq doc-block-regex (s/replace (slurp path) #"#.*" "")))))

(defn test-case-error-report
  "Returns a report of all failed test cases"
  [doc in out]
  (let [docinfo (try (d/parse doc)
                  (catch Exception e (.getMessage e)))]
    (if (string? docinfo)
      (str "\n" (s/trim-newline doc) "\n" docinfo)
      (let [result (or (m/match-argv docinfo in) "user-error")]
        (when (not= result out)
          (str "\n" (s/trim-newline doc)
               "\n$ prog " (s/join " " in)
               "\nexpected: " (json/generate-string out)
               "\nobtained: " (json/generate-string result) "\n\n"))))))

(defn valid?
  "Validates all test cases found in the file named 'test-cases-file-name'."
  [test-cases-file-name]
  (let [test-cases (load-test-cases test-cases-file-name)]
    (when-let [eseq (seq (remove nil? (map (partial apply test-case-error-report) test-cases)))]
      (println "Failed" (count eseq) "/" (count test-cases) "tests loaded from '" test-cases-file-name "'.\n")
      (throw (Exception. (apply str eseq))))
    (println "Successfully passed" (count test-cases) "tests loaded from '" test-cases-file-name "'.\n")
    true))

(deftest docopt-test
  (testing "2-arity version"
    (is (= {"<foo>" "a"}
           (d/docopt "usage: prog <foo>" ["a"]))))

  (testing "3-arity version"
    (is (= "a"
           (d/docopt "usage: prog <foo>" ["a"] #(get % "<foo>")))))

  (testing "4-arity version"
    (is (= "usage: prog <foo>"
           (d/docopt "usage: prog <foo>" [] identity identity))))

  ;; Adding this test here since it seems testcases file doesn't support quoted args
  (testing "should parse quoted args correctly"
    (is (= {"-f" "a b"}
           (d/docopt "usage: prog [options]\noptions: -f <foo>" ["-f" "a b"])))
    (is (= {"--foo" "a\nb"}
           (d/docopt "usage: prog [options]\noptions: --foo <foo>" ["--foo" "a\nb"])))
    (is (= {"<foo>" "a b  c "}
           (d/docopt "usage: prog <foo>" ["a b  c "])))
    (is (= {"<foo>" "a\tb\nc"}
           (d/docopt "usage: prog <foo>" ["a\tb\nc"])))
    (binding [docopt.match/*sep-table* {\          "FOO"
                                        \newline   "BAR"
                                        \tab       "QUX"
                                        \backspace "QUZ"}]
      (is (= {"<foo>" "a b\nc\td\b"}
             (d/docopt "usage: prog <foo>" ["aFOObBARcQUXdQUZ"]))))))

(deftest language-agnostic-test
  (is (valid? "https://raw.githubusercontent.com/docopt/docopt/511d1c57b59cd2ed663a9f9e181b5160ce97e728/testcases.docopt"))
  ;; BB-TEST-PATCH: Modified test path
  (is (valid? "test-resources/lib_tests/docopt/extra_testcases.docopt")))
