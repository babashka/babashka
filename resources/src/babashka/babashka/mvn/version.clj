(ns babashka.mvn.version
  "Maven version ordering, a port of GenericVersion from maven-resolver-util
  1.9.27, the scheme tools.deps compares with. Items are maps of :kind and
  :value."
  (:require [clojure.string :as str]))

;; Item kinds, ordered. Min and max are the "min" and "max" tokens.
(def ^:private kind-min 0)
(def ^:private kind-qualifier 2)
(def ^:private kind-string 3)
(def ^:private kind-int 4)
(def ^:private kind-bigint 5)
(def ^:private kind-max 8)

(def ^:private qualifiers
  {"alpha" -5 "beta" -4 "milestone" -3 "cr" -2 "rc" -2 "snapshot" -1
   "ga" 0 "final" 0 "release" 0 "" 0 "sp" 1})

(defn- number-kind? [{:keys [kind]}]
  (zero? (bit-and kind 2)))

(defn- item [kind value]
  {:kind kind :value value})

(defn- number-item [token]
  (if (< (count token) 10)
    (item kind-int (parse-long token))
    (item kind-bigint (bigint token))))

(defn- text-item [token at-end? terminated-by-number?]
  (let [lower (str/lower-case token)]
    (cond
      (and at-end? (= "min" lower)) (item kind-min "min")
      (and at-end? (= "max" lower)) (item kind-max "max")
      (and terminated-by-number? (= 1 (count token)) (#{"a" "b" "m"} lower))
      (item kind-qualifier (get qualifiers (case lower "a" "alpha" "b" "beta" "milestone")))
      (contains? qualifiers lower) (item kind-qualifier (get qualifiers lower))
      :else (item kind-string lower))))

(defn- tokenize
  "The items of a version string, before padding is trimmed."
  [version]
  (let [s (if (= "" version) "0" version)
        n (count s)]
    (loop [index 0 items []]
      (if (>= index n)
        items
        ;; state: -2 start, -1 in text, 0 in leading zeros, 1 in a number
        (let [[index start end state terminated-by-number?]
              (loop [i index start index end n state -2]
                (if (>= i n)
                  [i start end state false]
                  (let [c (.charAt s i)]
                    (if (or (= \. c) (= \- c) (= \_ c))
                      [(inc i) start i state false]
                      (let [digit (Character/digit c 10)]
                        (if (>= digit 0)
                          (cond
                            (= -1 state) [i start i state true]
                            :else (recur (inc i)
                                         (if (= 0 state) (inc start) start)
                                         end
                                         (if (or (> state 0) (> digit 0)) 1 0)))
                          (if (>= state 0)
                            [i start i state false]
                            (recur (inc i) start end -1))))))))
              token (if (> (- end start) 0) (subs s start end) "0")
              number? (if (> (- end start) 0) (>= state 0) true)
              it (if number?
                   (number-item token)
                   (text-item token (>= index n) terminated-by-number?))]
          (recur index (conj items it)))))))

(defn- compare-to-nil [{:keys [kind value]}]
  (cond
    (= kind kind-min) -1
    (or (= kind kind-max) (= kind kind-bigint) (= kind kind-string)) 1
    :else value))

(defn- compare-items [{ka :kind va :value} {kb :kind vb :value}]
  (let [rel (- ka kb)]
    (if (zero? rel)
      (cond
        (or (= ka kind-max) (= ka kind-min)) 0
        (= ka kind-string) (compare (str/lower-case va) (str/lower-case vb))
        :else (compare va vb))
      rel)))

(defn- remove-at [v i]
  (into (subvec v 0 i) (subvec v (inc i))))

(defn- trim-padding [items]
  (loop [items items
         i (dec (count items))
         end (dec (count items))
         number nil]
    (if (<= i 0)
      items
      (let [it (nth items i)
            n? (number-kind? it)
            [end number] (if (not= n? number) [i n?] [end number])]
        (if (and (= end i)
                 (or (= i (dec (count items)))
                     (= (number-kind? (nth items (dec i))) n?))
                 (zero? (compare-to-nil it)))
          (recur (remove-at items i) (dec i) (dec end) number)
          (recur items (dec i) end number))))))

(defn parse
  "The items of a version string."
  [version]
  (trim-padding (tokenize version)))

(defn- compare-padding [items index number]
  (loop [i index]
    (if (>= i (count items))
      0
      (let [it (nth items i)]
        (if (and (some? number) (not= number (number-kind? it)))
          (recur (inc i))
          (let [rel (compare-to-nil it)]
            (if (zero? rel) (recur (inc i)) rel)))))))

(defn- compare-parsed [these those]
  (loop [index 0 number true]
    (cond
      (and (>= index (count these)) (>= index (count those))) 0
      (>= index (count these)) (- (compare-padding those index nil))
      (>= index (count those)) (compare-padding these index nil)
      :else
      (let [a (nth these index)
            b (nth those index)]
        (if (not= (number-kind? a) (number-kind? b))
          (if (= number (number-kind? a))
            (compare-padding these index number)
            (- (compare-padding those index number)))
          (let [rel (compare-items a b)]
            (if (zero? rel)
              (recur (inc index) (number-kind? a))
              rel)))))))

(defn compare-versions
  "Negative, zero or positive, as tools.deps orders a before b."
  [a b]
  (let [r (compare-parsed (parse a) (parse b))]
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
