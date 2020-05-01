(ns babashka.impl.clojure.pprint
  {:no-doc true}
  (:require [fipp.edn :as fipp]
            [sci.core :as sci]
            [sci.impl.namespaces :refer [copy-var]]
            [sci.impl.vars :as vars]))

(def pprint-ns (vars/->SciNamespace 'clojure.pprint nil))

(def print-right-margin (sci/new-dynamic-var 'print-right-margin 70 {:ns pprint-ns}))

(defn pprint
  "Substitution for clojure.pprint backed by fipp.edn/pprint."
  ([edn]
   (pprint edn @sci/out))
  ([edn writer]
   (fipp/pprint edn {:writer writer
                     :width @print-right-margin})))

(defn print-table
  "Prints a collection of maps in a textual table. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows."
  ([rows] (print-table (keys (first rows)) rows))
  ([ks rows]
   (binding [*out* @sci/out]
     (when (seq rows)
       (let [widths (map
                     (fn [k]
                       (apply max (count (str k)) (map #(count (str (get % k))) rows)))
                     ks)
             spacers (map #(apply str (repeat % "-")) widths)
             fmts (map #(str "%" % "s") widths)
             fmt-row (fn [leader divider trailer row]
                       (str leader
                            (apply str (interpose divider
                                                  (for [[col fmt] (map vector (map #(get row %) ks) fmts)]
                                                    (format fmt (str col)))))
                            trailer))]
         (println)
         (println (fmt-row "| " " | " " |" (zipmap ks ks)))
         (println (fmt-row "|-" "-+-" "-|" (zipmap ks spacers)))
         (doseq [row rows]
           (println (fmt-row "| " " | " " |" row))))))))

(def pprint-namespace
  {'pprint (copy-var pprint pprint-ns)
   'print-table (copy-var print-table pprint-ns)
   '*print-right-margin* print-right-margin})
