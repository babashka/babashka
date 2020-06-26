(require '[clojure.string :as str])

(def babashka-version (System/getProperty "babashka.version"))
;; e.g. 0.1.3-SNAPSHOT

(defn compare-version [v]
  (nat-int? (compare
             (mapv #(Integer. %)
                   (take 3 (str/split babashka-version  #"[\.\-]"))) v)))

(prn (compare-version [0 1 2])) ;; true
(prn (compare-version [0 1 3])) ;; true
(prn (compare-version [0 1 4])) ;; false
