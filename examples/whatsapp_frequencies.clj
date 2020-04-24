;; USAGE: in whatsapp group chat, export your chat _without_ media, and store somewhere.
;; Then
;; $ cat chatfile.txt | bb -i -f whatsapp_frequencies.clj

(ns whatsapp-frequencies
  {:author "Marten Sytema"}
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [print-table]]
            [clojure.string :refer [trim] :as string]))

(defn parse-line
  "Returns the name of the message, or nil if it can't be found"
  [l]
  (->> (string/replace l #"\p{C}" "")
       trim
       (re-find #"\[.*\] (.+?):")
       second))

(defn chats-by-user
  ([lines]
   (chats-by-user lines parse-line))
  ([lines keep-fn]
   (->> lines
        (keep keep-fn)
        frequencies
        (sort-by second >)
        (map (fn [[name amount]]
               {:name   name
                :amount amount}))
        (print-table [:name :amount]))))

(chats-by-user (line-seq (io/reader *in*)))
