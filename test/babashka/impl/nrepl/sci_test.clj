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

(defn- candidates [ctx query]
  (let [{:keys [error completions]} (sci-helpers/completions ctx query)]
    (when error (throw error))
    (map (juxt :candidate :type) completions)))

(deftest var-completions-test
  (let [ctx (opts/init {})]
    (sci/eval-string* ctx "(require '[clojure.set :as set])")
    (testing "map-i completes map-indexed and leaves out set/map-invert"
      (is (= [["map-indexed" "function"]] (candidates ctx "map-i"))))
    (testing "whe completes the when macros and leaves out halt-when"
      (is (= #{"when" "when-first" "when-let" "when-not" "when-some"}
             (set (map first (candidates ctx "whe")))))
      (is (every? #(= "macro" (second %)) (candidates ctx "whe"))))
    (testing "set/map-i completes set/map-invert"
      (is (= [["set/map-invert" "function"]] (candidates ctx "set/map-i"))))
    (testing "a var without arglists has type var"
      (sci/eval-string* ctx "(def a-plain-value 1)")
      (is (= [["a-plain-value" "var"]] (candidates ctx "a-plain"))))
    (testing "recu completes the special form recur"
      (is (= [["recur" "special-form"]] (candidates ctx "recu"))))))

(deftest var-completions-key-test
  (let [lib-ns (sci/create-ns 'my.lib nil)
        ctx (opts/init {:namespaces {'my.lib {'enc (sci/new-var 'generate (fn [_]) {:ns lib-ns :arglists '([x])})}}})]
    (sci/eval-string* ctx "(require '[my.lib :as ml :refer [enc]])")
    (testing "ml/en completes ml/enc with type function"
      (is (= [["ml/enc" "function"]] (candidates ctx "ml/en"))))
    (testing "en completes the referred enc with type function"
      (is (some #{["enc" "function"]} (candidates ctx "en"))))))

(deftest namespace-and-class-completions-test
  (let [ctx (opts/init {:classes {'java.lang.String String}})]
    (testing "clojure.str completes clojure.string"
      (is (some #{["clojure.string" "namespace"]} (candidates ctx "clojure.str"))))
    (testing "str leaves out clojure.string"
      (is (not (some #{["clojure.string" "namespace"]} (candidates ctx "str")))))
    (testing "j.l.S completes java.lang.String"
      (is (some #{["java.lang.String" "class"]} (candidates ctx "j.l.S"))))))
