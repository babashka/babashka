(ns com.rpl.specter.test-helpers
  (:require [clojure.test.check
             [generators :as gen]
             [properties :as prop]]
            [clojure.test])

  (:use [com.rpl.specter :only [select transform]]
        [com.rpl.specter :only [select* transform*]]))


;; it seems like gen/bind and gen/return are a monad (hence the names)
;; this is only for clj (cljs version in different file)
(defmacro for-all+ [bindings & body]
  (let [parts (partition 2 bindings)
        vars (vec (map first parts))
        genned (reduce
                (fn [curr [v code]]
                  `(gen/bind ~code (fn [~v] ~curr)))
                `(gen/return ~vars)
                (reverse parts))]
    `(prop/for-all [~vars ~genned]
                   ~@body)))


(defmacro ic-test [params-decl apath transform-fn data params]
  (let [platform (if (contains? &env :locals) :cljs :clj)
        is-sym (if (= platform :clj) 'clojure.test/is 'cljs.test/is)]
    `(let [icfnsel# (fn [~@params-decl] (select ~apath ~data))
           icfntran# (fn [~@params-decl] (transform ~apath ~transform-fn ~data))
           regfnsel# (fn [~@params-decl] (select* ~apath ~data))
           regfntran# (fn [~@params-decl] (transform* ~apath ~transform-fn ~data))
           params# (if (empty? ~params) [[]] ~params)]
      (dotimes [_# 3]
        (doseq [ps# params#]
          (~is-sym (= (apply icfnsel# ps#) (apply regfnsel# ps#)))
          (~is-sym (= (apply icfntran# ps#) (apply regfntran# ps#))))))))
