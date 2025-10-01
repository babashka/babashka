(ns break-iterator-test
  (:import [java.text BreakIterator]))

(defn count-characters
  [^String text]
  (let [it (BreakIterator/getCharacterInstance)]
    (.setText it text)
    (loop [count 0]
      (if (= (.next it) BreakIterator/DONE)
        count
        (recur (inc count))))))

(prn
 (count-characters "🇨🇦"))
