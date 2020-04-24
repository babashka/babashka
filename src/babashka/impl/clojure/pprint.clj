(ns babashka.impl.clojure.pprint
  {:no-doc true}
  (:require [fipp.edn :as fipp]
            [sci.core :as sci]))

(defn pprint
  ([edn]
   (binding [*out* @sci/out]
     (fipp/pprint edn)))
  ([edn writer]
   (fipp/pprint edn {:writer writer})))

(defn print-table
  "Prints a collection of maps in a textual table. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows."
  {:added "1.3"}
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
           (println (fmt-row "| " " | " " |" row)))))))
  ([rows] (print-table (keys (first rows)) rows)))

(def pprint-namespace
  {'pprint pprint
   'print-table print-table})
