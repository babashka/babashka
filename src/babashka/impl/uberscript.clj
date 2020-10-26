(ns babashka.impl.uberscript
  (:require [sci.core :as sci]))

(defn rewrite-ns
  "Rewrites ns form :require clauses into symbols + :reload only."
  [ns]
  (keep (fn [x]
          (if (seq? x)
            (cond (= :require-macros (first x)) nil ;; ignore
                  (= :require (first x))
                  (let [nss (keep (fn [x]
                                    (cond (seqable? x) (first x)
                                          (symbol? x) x))
                                 (rest x))]
                    (cons :require (interleave nss (repeat :reload))))) ;; force reload
            x))
          ns))

(def ^:dynamic *ctx* nil)
(def debug true)

(defn process-source [expr]
  (let [source-reader (sci/reader expr)]
    (loop []
      (let [next-form (sci/parse-next *ctx* source-reader)]
        (when-not (= ::sci/eof next-form)
          (if (and (seq? next-form)
                   (= 'ns (first  next-form)))
            (let [ns (rewrite-ns next-form)]
              (prn :ns ns)
              (sci/eval-form *ctx* ns))
            ;; look for more ns forms
            (recur)))))))

(defn uberscript [init-expr skip-namespaces resource-fn]
  (let [uberscript-sources (atom ())
        load-fn (fn [{:keys [:namespace]}]
                  (when resource-fn
                    (if (contains? skip-namespaces namespace)
                      ""
                      (let [res (resource-fn namespace)]
                        (swap! uberscript-sources conj res)
                        res))))
        ctx (sci/init {:load-fn load-fn
                       :features #{:bb :clj}})]
    ;; establish a thread-local bindings to allow set!
    (sci/with-bindings {sci/ns @sci/ns}
      (binding [*ctx* ctx]
        (process-source init-expr))
      (prn (count @uberscript-sources)))))

;;;; Scratch

(comment #_do
  (require '[clojure.java.io :as io])
  (require '[clojure.string :as str])

  (defn test-uberscript []
    (uberscript "(ns foo (:require [clojure.string] :reload))"
                #{}
                (fn [ns] (some-> (str (str/replace ns "." "/") ".clj" )
                                 (io/resource)
                                 slurp))))

  (test-uberscript))
