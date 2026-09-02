(ns babashka.mvn.version
  "Maven version ordering, a port of ComparableVersion from maven-artifact
  3.9.16. Items are numbers, qualifier strings, or vectors for the parts
  after a dash."
  (:require [clojure.string :as str]))

(def ^:private qualifiers ["alpha" "beta" "milestone" "rc" "snapshot" "" "sp"])
(def ^:private aliases {"ga" "" "final" "" "release" "" "cr" "rc"})
(def ^:private release-index (str (.indexOf ^java.util.List qualifiers "")))

(defn- comparable-qualifier [q]
  (let [i (.indexOf ^java.util.List qualifiers q)]
    (if (neg? i)
      (str (count qualifiers) "-" q)
      (str i))))

(defn- string-item [s followed-by-digit?]
  (let [s (if (and followed-by-digit? (= 1 (count s)))
            (case s "a" "alpha" "b" "beta" "m" "milestone" s)
            s)]
    (get aliases s s)))

(defn- number-item [s]
  (let [s (str/replace s #"^0+(?=\d)" "")]
    (if (<= (count s) 18)
      (parse-long s)
      (bigint s))))

(defn- parse-item [digit? s]
  (if digit? (number-item s) (string-item s false)))

(defn- null-item? [item]
  (cond
    (number? item) (zero? item)
    (string? item) (= release-index (comparable-qualifier item))
    :else (empty? item)))

(defn- normalize
  "Drops trailing null items, looking past non-null sublists as Maven does."
  [items]
  (loop [items items
         i (dec (count items))]
    (if (neg? i)
      items
      (let [item (nth items i)]
        (cond
          (null-item? item) (recur (into (subvec items 0 i) (subvec items (inc i))) (dec i))
          (vector? item) (recur items (dec i))
          :else items)))))

(defn parse
  "The item tree for a version string."
  [version]
  (let [s (str/lower-case version)
        n (count s)]
    (loop [i 0
           start 0
           digit? false
           ;; the stack of open lists, deepest last, each a vector of items
           stack [[]]]
      (let [add (fn [stack item] (update stack (dec (count stack)) conj item))
            push (fn [stack] (conj stack []))]
        (if (< i n)
          (let [c (.charAt s i)]
            (cond
              (= \. c)
              (recur (inc i) (inc i) digit?
                     (add stack (if (= i start) 0 (parse-item digit? (subs s start i)))))

              (= \- c)
              (recur (inc i) (inc i) digit?
                     (push (add stack (if (= i start) 0 (parse-item digit? (subs s start i))))))

              (Character/isDigit c)
              (if (and (not digit?) (> i start))
                (let [stack (if (seq (peek stack)) (push stack) stack)
                      stack (add stack (string-item (subs s start i) true))]
                  (recur (inc i) i true (push stack)))
                (recur (inc i) start true stack))

              :else
              (if (and digit? (> i start))
                (recur (inc i) i false
                       (push (add stack (parse-item true (subs s start i)))))
                (recur (inc i) start false stack))))
          (let [stack (if (> n start)
                        (let [stack (if (and (not digit?) (seq (peek stack))) (push stack) stack)]
                          (add stack (parse-item digit? (subs s start))))
                        stack)]
            ;; close the lists, deepest first, normalizing each
            (loop [stack stack]
              (if (= 1 (count stack))
                (normalize (peek stack))
                (let [child (normalize (peek stack))
                      stack (pop stack)]
                  (recur (update stack (dec (count stack)) conj child)))))))))))

(declare compare-items)

(defn- compare-to-nil [item]
  (cond
    (number? item) (if (zero? item) 0 1)
    (string? item) (compare (comparable-qualifier item) release-index)
    :else (or (some #(let [r (compare-to-nil %)] (when-not (zero? r) r)) item) 0)))

(defn- compare-items [a b]
  (cond
    (nil? b) (compare-to-nil a)
    (number? a) (cond (number? b) (compare a b)
                      :else 1)
    (string? a) (cond (string? b) (compare (comparable-qualifier a) (comparable-qualifier b))
                      :else -1)
    :else (cond (number? b) -1
                (string? b) 1
                :else (loop [l (seq a) r (seq b)]
                        (if (or l r)
                          (let [x (first l) y (first r)
                                result (if (nil? x)
                                         (- (compare-items y nil))
                                         (compare-items x y))]
                            (if (zero? result)
                              (recur (next l) (next r))
                              result))
                          0)))))

(defn compare-versions
  "Negative, zero or positive, as Maven orders a before b."
  [a b]
  (let [r (compare-items (parse a) (parse b))]
    (cond (neg? r) -1 (pos? r) 1 :else 0)))

(defn- parse-bound
  "[low,high) style restriction into {:low :low-inclusive :high :high-inclusive}."
  [s]
  (let [s (str/trim s)
        low-inclusive (str/starts-with? s "[")
        high-inclusive (str/ends-with? s "]")
        inner (subs s 1 (dec (count s)))
        [low high] (if (str/includes? inner ",")
                     (map str/trim (str/split inner #"," 2))
                     [inner inner])]
    {:low (when-not (str/blank? low) low)
     :low-inclusive low-inclusive
     :high (when-not (str/blank? high) high)
     :high-inclusive high-inclusive}))

(defn parse-range
  "The restrictions of a Maven version range such as [1.0,2.0) or
  [1.0],[2.0,). A plain version is a single soft restriction."
  [s]
  (if (re-find #"^[\[(]" s)
    (mapv parse-bound (re-seq #"[\[(][^\]\)]*[\]\)]" s))
    [{:low s :low-inclusive true :high s :high-inclusive true}]))

(defn- in-bound? [version {:keys [low low-inclusive high high-inclusive]}]
  (and (or (nil? low)
           (let [c (compare-versions version low)]
             (or (pos? c) (and low-inclusive (zero? c)))))
       (or (nil? high)
           (let [c (compare-versions version high)]
             (or (neg? c) (and high-inclusive (zero? c)))))))

(defn in-range?
  "Whether version satisfies the range."
  [version range]
  (boolean (some #(in-bound? version %) (parse-range range))))
