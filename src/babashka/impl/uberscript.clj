(ns babashka.impl.uberscript
  (:require [sci.core :as sci]))

;; TODO: rewrite ns form to exclude :refer. Is

;; From grasp

(defn decompose-clause [clause]
  (if (symbol? clause)
    {:ns clause}
    (when (seqable? clause)
      (let [clause (if (= 'quote (first clause))
                     (second clause)
                     clause)
            [ns & tail] clause]
        (loop [parsed {:ns ns}
               tail (clojure.core/seq tail)]
          (if tail
            (let [ftail (first tail)]
              (case ftail
                :as (recur (assoc parsed :as (second tail))
                           (nnext tail))
                (:refer :refer-macros)
                (let [refer (second tail)]
                  (if (seqable? refer)
                    (recur (assoc parsed :refer (second tail))
                           (nnext tail))
                    (recur parsed (nnext tail))))
                ;; default
                (recur parsed
                       (nnext tail))))
            parsed))))))

(defn recompose-clause [{:keys [:ns :as :refer]}]
  [ns :as as :refer refer])

(defn stub-refers [ctx {:keys [:ns :refer]}]
  (when (clojure.core/seq refer)
    (let [ns-obj (sci/create-ns ns nil)
          env (:env ctx)]
      (run! #(swap! env assoc-in [:namespaces ns %]
                    (sci/new-var % nil {:name %
                                        :ns ns-obj}))
            refer))))

(defn process-ns
  [ctx ns]
  (keep (fn [x]
          (if (seqable? x) ;; for some reason pathom has [:require-macros com.wsscode.pathom.connect] in a vector...
            (let [fx (first x)]
              (when (clojure.core/or
                     (identical? :require fx)
                     (identical? :require-macros fx))
                (let [decomposed (keep decompose-clause (rest x))
                      recomposed (map recompose-clause decomposed)]
                  (run! #(stub-refers ctx %) decomposed)
                  (list* :require recomposed))))
            x))
        ns))

(defn keep-quoted [clauses]
  (keep (fn [clause]
          (when (and (seq? clause) (= 'quote (first clause)))
            (second clause)))
        clauses))

(defn process-require [ctx req]
  (let [quoted (keep-quoted (rest req))
        decomposed (map decompose-clause quoted)]
    (run! #(stub-refers ctx %) decomposed)
    (list* 'require (map (fn [clause]
                           (list 'quote (recompose-clause clause)))
                         decomposed))))

(defn process-in-ns [_ctx req]
  (let [quoted (keep-quoted (rest req))
        quoted (map (fn [ns]
                      (list 'quote ns))
                    quoted)]
    (when (clojure.core/seq quoted)
      (list* 'in-ns quoted))))

(defn uberscript [{:keys [ctx expressions]}]
  (let [ctx (assoc ctx :reload-all true)]
    (sci/binding [sci/file @sci/file]
      (doseq [expr expressions]
        (let [rdr (sci/reader expr)]
          (loop []
            (let [next-val (try (sci/parse-next ctx rdr)
                                ;; swallow reader error
                                (catch Exception _e nil))]
              ;; (.println System/err (pr-str next-val))
              (when-not (= ::sci/eof next-val)
                (if (seq? next-val)
                  (let [fst (first next-val)]
                    (try
                      (cond (= 'ns fst)
                            (sci/eval-form ctx (doto (process-ns ctx next-val)
                                                 #_(as-> $ (.println System/err (pr-str $)))))
                            (= 'require fst)
                            (sci/eval-form ctx (process-require ctx next-val))
                            (= 'in-ns fst)
                            (sci/eval-form ctx (process-in-ns ctx next-val)))
                      ;; swallow exception and continue
                      (catch Exception _e nil))
                    (recur))
                  (recur))))))))))
