(ns babashka.impl.uberscript
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sci.core :as sci]))

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
          (if (seq? next-form)
            (let [form (cond (= 'ns (first  next-form))
                             (rewrite-ns next-form))]
              (sci/eval-form *ctx* form))
            ;; look for more ns forms
            (recur)))))))

(defn find-source [namespace extensions resource-fn]
  (let [base (str/replace namespace "." "/")]
    (some (fn [ext] (some-> (str base "." ext)
                            resource-fn
                            slurp))
          extensions)))

(defn uberscript [init-expr {:keys [:skip-namespaces :extensions :resource-fn
                                    :out]
                             :or {extensions ["clj" "cljc"]
                                  resource-fn io/resource
                                  out *out*}}]
  (let [uberscript-sources (atom (list init-expr))
        load-fn (fn [{:keys [:namespace]}]
                  (if (contains? skip-namespaces namespace)
                    ""
                    (when-let [res (find-source namespace extensions resource-fn)]
                      (swap! uberscript-sources conj res)
                      res)))
        ctx (sci/init {:load-fn load-fn
                       :features #{:bb :clj}})]
    ;; establish a thread-local bindings to allow set!
    (sci/with-bindings {sci/ns @sci/ns}
      (binding [*ctx* ctx]
        (process-source init-expr))
      (io/copy (str/join "\n" (distinct @uberscript-sources)) out))))

;;;; Scratch

(comment
  ;;do
  (defn test-uberscript []
    (uberscript "(ns foo (:require [clojure.string] :reload))"
                {:out (io/file "/tmp/uberscript.clj")
                 :extensions ["bb" "clj" "cljc"]}))

  (test-uberscript))
