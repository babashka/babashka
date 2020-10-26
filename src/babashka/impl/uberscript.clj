(ns babashka.impl.uberscript
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
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
                    (cons :require (interpose :reload nss)))) ;; force reload
            x))
          ns))

(defn ns->files [dir ns]
  (let [extensions ["clj" "cljs" "cljc"]
        path (-> ns munge (str/replace "." java.io.File/separator))
        files (map #(io/file dir (str path "." %)) extensions)]
    (filter #(.exists ^java.io.File %) files)))

(def ^:dynamic *ctx* nil)
(def ^:dynamic *ns-path* nil)
(def debug true)

(defn process-source [file]
  (let [file-reader (io/reader (io/file file))
        source-reader (sci/reader file-reader)]
    (loop []
      (let [next-form (sci/parse-next *ctx* source-reader)]
        (when-not (= ::sci/eof next-form)
          (if (and (seq? next-form)
                   (= 'ns (first  next-form)))
            (let [ns (rewrite-ns next-form)]
              (sci/eval-form *ctx* ns))
            ;; look for more ns forms
            (recur)))))))

(defn uberscript [init-code namespace skip-namespaces resource-fn]
  (let [uberscript-sources (atom ())
        load-fn (fn [{:keys [:namespace :reload]}]
                  (when resource-fn
                    (if ;; ignore built-in namespaces when uberscripting, unless with :reload
                        (and uberscript
                             (not reload)
                             (not (contains? skip-namespaces namespace)))
                      ""
                      (let [res (resource-fn namespace)]
                        (when uberscript (swap! uberscript-sources conj (:source res)))
                        res))))
        namespace (symbol namespace)
        results (atom {namespace nil})
        ctx (sci/init {:load-fn load-fn
                       :features #{:bb :clj}})]
    ;; establish a thread-local bindings to allow set!
    (sci/with-bindings {sci/ns @sci/ns}
      (binding [*ctx* ctx
                *ns-path* [namespace]]
        (process-source init-code)))))

