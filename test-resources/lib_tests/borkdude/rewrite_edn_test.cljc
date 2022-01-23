(ns borkdude.rewrite-edn-test
  (:require [borkdude.rewrite-edn :as r]
            [clojure.test :as t :refer [deftest testing is]]))

(deftest assoc-test
  (testing "Base case"
    (is (= "{:a 1}"
           (str (r/assoc
                 (r/parse-string "{}")
                 :a 1)))))
  (testing "When there's only one existing, keys are added on a new line"
    (is (= "
{:a 1
 :b 1}"
           (str (r/assoc
                 (r/parse-string "
{:a 1}")
                 :b 1)))))
  (testing "Unless there are already keys on the same line"
    (is (= "{:a 1 :b 2 :c 3}"
           (str (r/assoc
                 (r/parse-string "{:a 1 :b 2}")
                 :c 3)))))
  (testing "when map is already multi-line, new keys are added on new line"
    (is (= "
{:a 1
 :b 2}
;; this is a cool map"
           (str (r/assoc
                 (r/parse-string "
{:a 1}
;; this is a cool map")
                 :b 2)))))
  (testing "Updating existing val"
    (is (= "{:a 2}"
           (str (r/assoc
                 (r/parse-string "{:a 1}")
                 :a 2)))))
  (testing "Something between key and val"
    (is (= "{:a #_:something 2}"
           (str (r/assoc
                 (r/parse-string "{:a #_:something 1}")
                 :a 2)))))
  (testing "Comment at the end"
    (is (= "{:a 2} ;; this is a cool map"
           (str (r/assoc
                 (r/parse-string "{:a 1} ;; this is a cool map")
                 :a 2)))))
  (testing "Vector index assoc"
    (is (= "[9 8 99 7] ;; this is a cool vector"
           (str (r/assoc
                 (r/parse-string "[9 8 3 7] ;; this is a cool vector")
                 2 99)))))
  (testing "Vector last index assoc"
    (is (= "[9 8 3 99] ;; this is a cool vector"
           (str (r/assoc
                 (r/parse-string "[9 8 3 7] ;; this is a cool vector")
                 3 99)))))
  (testing "Vector assoc out of bounds"
    (is (try
          (r/assoc (r/parse-string "[9 8 3 7] ;; this is a cool vector") 9 99)
          false
          (catch java.lang.IndexOutOfBoundsException _ true))))
  (testing "Vector assoc out of bounds with ignored"
    (is (try
          (r/assoc (r/parse-string "[9 8 3 #_99 #_213 7] ;; this is a cool vector") 4 99)
          false
          (catch java.lang.IndexOutOfBoundsException _ true)))))

(deftest update-test
  (is (= "{:a #_:foo 2}"
         (str (r/update
               (r/parse-string "{:a #_:foo 1}")
               :a (fn [node]
                    (inc (r/sexpr node))))))))

(defn qualify-sym-node [sym-node]
  (let [sym (r/sexpr sym-node)]
    (if (or (not (symbol? sym))
            (qualified-symbol? sym))
      sym-node
      (symbol (str sym) (str sym)))))

(deftest map-keys-test
  (is (= "
{foo/foo 1
 bar/bar 2}"
         (str (r/map-keys qualify-sym-node
                          (r/parse-string "
{foo 1
 bar 2}"))))))

(deftest update-deps-test
  (is (= "{:deps {foo/foo {:mvn/version \"0.1.0\"}}}"
         (str (r/update (r/parse-string "{:deps {foo {:mvn/version \"0.1.0\"}}}")
                        :deps
                        (fn [deps-map-node]
                          (r/map-keys qualify-sym-node deps-map-node)))))))

(deftest assoc-in-test
  (is (= "{:a {:b {:c 2}}}"
         (str (r/assoc-in (r/parse-string "{}")
                          [:a :b :c] 2))))
  (is (= "{:a {:b {:c 2}}}"
         (str (r/assoc-in (r/parse-string "nil")
                          [:a :b :c] 2))))
  (is (= "{:deps {foo/foo {:mvn/version \"0.2.0\"}}}"
         (str (r/assoc-in (r/parse-string "{:deps {foo/foo {:mvn/version \"0.1.0\"}}}")
                           [:deps 'foo/foo :mvn/version]
                           "0.2.0"))))
  (is (= "{:a 1 :b {:c 1}}"
         (str (r/assoc-in (r/parse-string "{:a 1}") [:b :c] 1)))))

(deftest update-in-test
  (is (= "{:deps {foo/foo {:mvn/version \"0.2.0\"}}}"
         (str (r/update-in (r/parse-string "{:deps {foo/foo {:mvn/version \"0.1.0\"}}}")
                           [:deps 'foo/foo]
                           #(r/assoc % :mvn/version "0.2.0")))))
  (is (= "{:a {:b {:c 1}}}"
         (str (r/update-in (r/parse-string "{}")
                           [:a :b :c]
                           (comp (fnil inc 0) r/sexpr)))))
  (is (= "{:a {:b {:c 1}}}"
         (str (r/update-in (r/parse-string "nil")
                           [:a :b :c]
                           (comp (fnil inc 0) r/sexpr))))))

(deftest dissoc-test
  (is (= "{}" (str (r/dissoc (r/parse-string "{:a 1}") :a))))
  (is (= "{:a 1}" (str (r/dissoc (r/parse-string "{:a 1 \n\n:b 2}") :b))))
  (is (= "{:a 1\n:c 3}" (str (r/dissoc (r/parse-string "{:a 1\n:b 2\n:c 3}") :b))))
  (is (= "{:deps {foo/bar {}}}" (str (r/update (r/parse-string "{:deps {foo/bar {} foo/baz {}}}")
                                               :deps #(r/dissoc % 'foo/baz))))))
