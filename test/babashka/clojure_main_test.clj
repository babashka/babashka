(ns babashka.clojure-main-test
  (:require
   [babashka.fs :as fs]
   [babashka.test-utils :as tu]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- triage-at-repl
  "Feeds forms to clojure.main/repl and returns the ex-triage of every error
  its :caught hook receives, in order."
  [& forms]
  (let [code "(def results (atom []))
              (clojure.main/repl
                :prompt (fn [])
                :caught (fn [e] (swap! results conj (clojure.main/ex-triage (Throwable->map e)))))
              (prn @results)"
        input (str (str/join "\n" forms) "\n:repl/quit\n")]
    (->> (tu/bb input "-e" code)
         str/split-lines
         last
         edn/read-string)))

(defn- sci-internal? [triage]
  (or (some-> (:clojure.error/symbol triage) namespace (str/starts-with? "sci."))
      (some-> (:clojure.error/source triage) (str/ends-with? ".cljc"))))

(deftest ex-triage-repl-test
  (let [lib (fs/file (fs/create-temp-dir) "lib.clj")
        _ (spit lib "(ns my.lib)\n(defn inner [x]\n  (/ x 0))\n")
        [execution unresolved macro :as results]
        (triage-at-repl (pr-str (list 'load-file (str lib)))
                        "(my.lib/inner 1)"
                        "(nope 1)"
                        "(defmacro m [] (throw (ex-info \"in macro\" {})))"
                        "(m)")]
    (testing "an error in a loaded file names the function and its location"
      (is (= #:clojure.error{:phase :execution
                             :class 'java.lang.ArithmeticException
                             :cause "Divide by zero"
                             :symbol 'my.lib/inner
                             :source "lib.clj"
                             :line 3
                             :column 3}
             execution)))
    (testing "an unresolved symbol is a syntax error without an exception class"
      (is (= :compile-syntax-check (:clojure.error/phase unresolved)))
      (is (= "Unable to resolve symbol: nope" (:clojure.error/cause unresolved)))
      (is (not (contains? unresolved :clojure.error/class))))
    (testing "an error thrown by a macro names the macro"
      (is (= :macroexpansion (:clojure.error/phase macro)))
      (is (= 'user/m (:clojure.error/symbol macro)))
      (is (= "in macro" (:clojure.error/cause macro))))
    (testing "no location points into the interpreter"
      (is (not-any? sci-internal? results)))))

(deftest ex-triage-caught-test
  (testing "an exception caught in the script has no location to report"
    (is (= #:clojure.error{:phase :execution
                           :class 'java.lang.ArithmeticException
                           :cause "Divide by zero"}
           (edn/read-string
            (tu/bb nil "-e" "(try (/ 1 0) (catch Exception e (prn (clojure.main/ex-triage (Throwable->map e)))))")))))
  (testing "a reader error is triaged as reading source"
    (let [triage (edn/read-string
                  (tu/bb nil "-e" "(try (load-string \"(foo\") (catch Exception e (prn (clojure.main/ex-triage (Throwable->map e)))))"))]
      (is (= :read-source (:clojure.error/phase triage)))
      (is (str/includes? (:clojure.error/cause triage) "EOF")))))
