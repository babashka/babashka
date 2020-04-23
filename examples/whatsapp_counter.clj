;; USAGE: in whatsapp group chat, export your chat _without_ media, and store somewhere.
;; Then
;; $> cat chatfile.txt | bb -i -f whatsapp_counter.clj

(require '[clojure.string :refer [lower-case includes? trim ] :as string])

(defn parse-line
  "Returns the name of the message, or nil if it can't be found"
  [l]
  (->> (string/replace l #"\p{C}" "")
       trim
       (re-find #"\[.{19}\].(.+?):")
       second))

(defn print-table
  "NOTE: this is a verbatim copy from
   https://github.com/clojure/clojure/blob/93d13d0c0671130b329863570080c72799563ac7/src/clj/clojure/pprint/print_table.clj#L11
   Prints a collection of maps in a textual table. Prints table headings
   ks, and then a line of output for each row, corresponding to the keys
   in ks. If ks are not specified, use the keys of the first item in rows."
  ([ks rows]
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
           (println (fmt-row "| " " | " " |" row))))))
  ([rows] (print-table (keys (first rows)) rows)))

(defn chats-by-user
  ([lines]
   (chats-by-user lines parse-line))
  ([lines filter-fn]
   (let [ histogram (reduce
                     (fn [acc l]
                       (if-let [n (filter-fn l)]
                         (update acc n (fnil inc 0))
                         ;;else ignore
                         acc))
                     {}
                     lines)]
     (->> histogram
          (sort-by second)
          reverse
          (map (fn [[name amount]]
                 {:name   name
                  :amount amount}))
          print-table))))

(chats-by-user *input*)

(System/exit 0)
