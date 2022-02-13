(ns doric.test.doctest
  (:use [clojure.java.io :only [file]]
        [clojure.test])
  (:import (java.io PushbackReader StringReader)))

(defn fenced-blocks
  "detect and extract github-style fenced blocks in a file"
  [s]
  (map second
       (re-seq #"(?m)(?s)^```clojure\n(.*?)\n^```" s)))

(def prompt
  ;; regex for finding 'foo.bar>' repl prompts
  "(?m)\n*^\\S*>\\s*")

(defn skip?
  "is a result skippable?"
  ;; if it's a comment, the answer is yes
  [s]
  (.startsWith s ";"))

(defn reps
  "given a string of read-eval-print sequences, separate the different
  'r-e-p's from each other"
  [prompt s]
  (rest (.split s prompt)))

(defn markdown-tests
  "extract all the tests from a markdown file"
  [f]
  (->> f
       slurp
       fenced-blocks
       (mapcat (partial reps prompt))))

(defn repl-tests
  "extract all the tests from a repl-session-like file"
  [f]
  (->> f
       slurp
       (reps prompt)))

(defn temp-ns
  "create a temporary ns, and return its name"
  []
  (binding [*ns* *ns*]
    (in-ns (gensym))
    (use 'clojure.core)
    ;; BB-TEST-PATCH: bb can't .getName on ns
    (str *ns*)))

(defn eval-in-ns
  "evaluate a form inside the given ns-name"
  [ns form]
  (binding [*ns* *ns*]
    (in-ns ns)
    (eval form)))

(defn run-doctest
  "run a single doctest, reporting success or failure"
  [file idx ns test]
  (let [r (PushbackReader. (StringReader. test))
        form (read r)
        expected (.trim (slurp r))
        actual (when-not (skip? expected)
                 (.trim (try
                          (with-out-str
                            (pr (eval-in-ns ns form))
                            (flush))
                          (catch Exception _
                            (println _)
                            (.toString (gensym))))))]
    (if (or (skip? expected)
            (= actual expected))
      (report {:type :pass})
      (report {:type :fail
               :file file :line idx
               :expected expected :actual actual}))))

(defn run-doctests
  "use text-extract-fn to get all the tests out of file, and run them
  all, reporting success or failure"
  [test-extract-fn file]
  (let [ns (temp-ns)]
    (doseq [[idx t] (map-indexed vector (test-extract-fn file))]
      (run-doctest file idx ns t))
    (remove-ns ns)))


(comment
  ;; example usage
  (deftest bar-repl
    (run-doctests repl-tests "test/bar.repl")))
