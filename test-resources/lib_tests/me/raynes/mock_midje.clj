;; BB-TEST-PATCH: add this file to mock the midje 'fact' macro

(ns me.raynes.mock-midje
  (:require [rewrite-clj.paredit :as rcp]
            [rewrite-clj.zip :as rcz]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- up-until-nil [z]
  (if-let [new-z (rcz/up z)]
    (recur new-z)
    z))

(defn- replace-arrow [form-zipper-at-arrow]
  (let [lhs (-> form-zipper-at-arrow rcz/left rcz/node)
        rhs (-> form-zipper-at-arrow rcz/right rcz/node)]
    (-> form-zipper-at-arrow
      (rcp/wrap-around :list)
      rcp/slurp-backward
      rcp/slurp-forward
      rcz/up
      (rcz/replace `(is (= ~lhs ~rhs)))
      up-until-nil)))

(defn- replace-arrows [form-zipper]
  (if-let [next-arrow (rcz/find-next-value form-zipper rcz/next '=>)]
    (let [rd (replace-arrow next-arrow)]
      (recur rd))
    (rcz/sexpr form-zipper)))

(defmacro fact
  "mockup of midje's fact that just transforms `=>` into `is` checks and uses deftest"
  [& _] ;operating on &form
  (let [[nameish body] (if (string? (second &form))
                      [(str/replace (second &form) #"[^\w\d]" "-") (nnext &form)]
                      ["test" (next &form)])
        transformed-body (replace-arrows (rcz/of-string (pr-str body)))]
    `(deftest ~(gensym nameish) ~@transformed-body)))
