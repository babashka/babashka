(ns babashka.impl.nrepl.sci-test
  (:require
   [babashka.impl.nrepl.sci :as sci-helpers]
   [clojure.test :refer [deftest is testing]]
   [sci.core :as sci]
   [sci.impl.opts :as opts]))

(set! *warn-on-reflection* true)

;; Intern test keywords so they're reliably in the table
(def ^:private test-keywords
  [:bb-test/alpha :bb-test/beta :bb-test/gamma
   :bb-test-unqualified])

(def ^:private test-ctx
  (opts/init {:namespaces {'my.app {'handler identity}}}))

(deftest keyword-completions-test
  (testing "simple keyword prefix"
    (let [candidates (mapv second (sci-helpers/keyword-completions test-ctx ":bb-test"))]
      (is (some #(= ":bb-test/alpha" %) candidates))
      (is (some #(= ":bb-test/beta" %) candidates))
      (is (some #(= ":bb-test-unqualified" %) candidates))))
  (testing "qualified keyword prefix"
    (let [candidates (mapv second (sci-helpers/keyword-completions test-ctx ":bb-test/a"))]
      (is (some #(= ":bb-test/alpha" %) candidates))
      (is (not (some #(= ":bb-test/beta" %) candidates)))))
  (testing ":: resolves to current namespace"
    (let [;; Intern a keyword with the current ns
          _ (keyword (str (sci/eval-string* test-ctx "(ns-name *ns*)")) "ctx-kw")
          candidates (mapv second (sci-helpers/keyword-completions test-ctx "::ctx"))]
      (is (some #(= "::ctx-kw" %) candidates))))
  (testing "::alias/ resolves alias"
    (sci/eval-string* test-ctx "(require '[my.app :as app])")
    (let [_ (keyword "my.app" "aliased-kw")
          candidates (mapv second (sci-helpers/keyword-completions test-ctx "::app/aliased"))]
      (is (some #(= "::app/aliased-kw" %) candidates))))
  (testing "non-keyword query returns nil"
    (is (nil? (sci-helpers/keyword-completions test-ctx "map"))))
  (testing "no matches returns empty"
    (is (empty? (sci-helpers/keyword-completions test-ctx ":zzz-nonexistent-xyzzy")))))

(deftest completions-keyword-test
  (testing "completions returns keyword results for : prefix"
    (let [results (:completions (sci-helpers/completions test-ctx ":bb-test/a"))]
      (is (some #(= ":bb-test/alpha" (:candidate %)) results))
      (is (every? #(= "keyword" (:type %)) results))))
  (testing ":public-class is not returned as a class completion"
    (let [results (:completions (sci-helpers/completions test-ctx "pub"))]
      (is (not (some #(and (= ":public-class" (:candidate %))
                           (= "class" (:type %))) results)))))
  (testing "symbol completions still work"
    (let [results (:completions (sci-helpers/completions test-ctx "map"))]
      (is (some #(= "map" (:candidate %)) results)))))

(deftest deftype-completions-test
  (let [ctx (opts/init {})]
    (sci/eval-string* ctx "(defrecord MyRec [x y])")
    (testing "defrecord type name completes"
      (let [results (:completions (sci-helpers/completions ctx "MyR"))]
        (is (some #(= "MyRec" (:candidate %)) results))))
    (testing "defrecord constructor completes"
      (let [results (:completions (sci-helpers/completions ctx "->My"))]
        (is (some #(= "->MyRec" (:candidate %)) results)))))
  (let [ctx (opts/init {})]
    (sci/eval-string* ctx "(deftype MyType [x])")
    (testing "deftype type name completes"
      (let [results (:completions (sci-helpers/completions ctx "MyT"))]
        (is (some #(= "MyType" (:candidate %)) results))))
    (testing "deftype constructor completes"
      (let [results (:completions (sci-helpers/completions ctx "->My"))]
        (is (some #(= "->MyType" (:candidate %)) results))))))
