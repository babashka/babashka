(ns babashka.impl.uberscript
  (:require [sci.core :as sci]))

(defn decompose-clause [clause]
  (if (symbol? clause)
    {:ns clause}
    (when (seqable? clause)
      (let [clause (if (= 'quote (first clause))
                     (second clause)
                     clause)
            [ns & tail] clause]
        (loop [parsed {:ns ns}
               tail (seq tail)]
          (if tail
            (let [ftail (first tail)]
              (case ftail
                :as (recur (assoc parsed :as (second tail))
                           (nnext tail))
                :refer
                (let [refer (second tail)]
                  (if (seqable? refer)
                    (recur (assoc parsed :refer (second tail))
                           (nnext tail))
                    (recur parsed (nnext tail))))
                ;; default
                (recur parsed
                       (nnext tail))))
            parsed))))))

(defn recompose-clause [{:keys [:ns :as]}]
  [ns :as as])

(defn process-ns
  [_ctx ns]
  (keep (fn [x]
          (if (seqable? x)
            (let [fx (first x)]
              (when (identical? :require fx)
                (let [decomposed (keep decompose-clause (rest x))
                      recomposed (map recompose-clause decomposed)]
                  (list* :require recomposed))))
            x))
        ns))

(defn keep-quoted [clauses]
  (keep (fn [clause]
          (when (and (seq? clause) (= 'quote (first clause)))
            (second clause)))
        clauses))

(defn process-require [_ctx req]
  (let [quoted (keep-quoted (rest req))
        decomposed (map decompose-clause quoted)]
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

(defn loc [rdr]
  (str (when-let [f @sci/file]
         (str f ":"))
       (sci/get-line-number rdr)
       ":"
       (sci/get-column-number rdr)))

(defn uberscript [{:keys [ctx expressions]}]
  (let [ctx (assoc ctx :reload-all true)]
    (sci/binding [sci/file @sci/file]
      (doseq [expr expressions]
        (let [rdr (sci/reader expr)]
          (loop []
            (let [next-val
                  (try (sci/parse-next ctx rdr)
                       ;; swallow reader error
                       (catch Exception _e
                         (binding [*out* *err*]
                           (println "[babashka]" "Ignoring read error while assembling uberscript near"
                                    (loc rdr)))))]
              ;; (.println System/err (pr-str next-val))
              (when-not (= ::sci/eof next-val)
                (if (seq? next-val)
                  (let [fst (first next-val)
                        expr (cond (= 'ns fst)
                                   (process-ns ctx next-val)
                                   (= 'require fst)
                                   (process-require ctx next-val)
                                   (= 'in-ns fst)
                                   (process-in-ns ctx next-val))]
                    (when expr
                      (try
                        (sci/eval-form ctx expr)
                        ;; swallow exception and continue
                        (catch Exception _e
                          (binding [*out* *err*]
                            (println "[babashka]" "Ignoring expression while assembling uberscript:"
                                     expr "near" (loc rdr))))))
                    (recur))
                  (recur))))))))))
