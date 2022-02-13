(ns lambdaisland.regal.test-util
  (:require [lambdaisland.regal :as regal])
  #?(:cljs (:require-macros [lambdaisland.regal.test-util :refer [inline-resource]])
           :clj (:require [clojure.java.io :as io]
                          [clojure.test.check.generators :as gen]
                          [lambdaisland.regal.generator :as regal-gen]
                          ;; BB-TEST-PATCH: Don't have this dependency
                          #_[com.gfredericks.test.chuck.regexes.charsets :as charsets])))

#?(:clj
   (defmacro inline-resource [resource-path]
     (read-string (slurp (io/resource resource-path)))))

(defn read-test-cases []
  #? (:clj (read-string (slurp (io/resource "lambdaisland/regal/test_cases.edn")))
      :cljs (inline-resource "lambdaisland/regal/test_cases.edn")))

(defn flavor-parents [flavor]
  (->> flavor
       (iterate (comp first (partial parents regal/flavor-hierarchy)))
       (take-while identity)))

(defn format-cases [cases]
  (for [[form pattern & tests :as case] cases
        :let [[props tests] (if (map? (first tests))
                              [(first tests) (rest tests)]
                              [{} tests])]]
    (with-meta (merge
                {:pattern   pattern
                 :form      form
                 :tests     tests}
                props)
      (meta case))))

(defn test-cases
  ([]
   (let [cases (read-test-cases)]
     (loop [[id & cases] cases
            result []]
       (if id
         (recur (drop-while vector? cases)
                (conj result
                      {:id id
                       :cases (format-cases (take-while vector? cases))}))
         result)))))

;; BB-TEST-PATCH: bb doesn't have Pattern class
#_(:clj
   (do
     (defn re2-compile ^com.google.re2j.Pattern [s]
       (com.google.re2j.Pattern/compile s))
     (defn re2-groups
       [^com.google.re2j.Matcher m]
       (let [gc  (. m (groupCount))]
         (if (zero? gc)
           (. m (group))
           (loop [ret [] c 0]
             (if (<= c gc)
               (recur (conj ret (. m (group c))) (inc c))
               ret)))))
     (defn re2-find
       ([^com.google.re2j.Matcher m]
        (when (. m (find))
          (re2-groups m)))
       ([^com.google.re2j.Pattern re s]
        (let [m (.matcher re s)]
          (re2-find m))))))
;; BB-TEST-PATCH: Uses ns that can't load
#_(:clj
   (do
     ;; Implementation for generating classes using test.chuck's charsets.
     ;; This should eventually be moved to lambdaisland.regal.generator
     ;; when we have our own charset implementation
     (def token->charset-map
       (let [whitespace-charset (apply charsets/union
                                       (map (comp charsets/singleton str char) regal/whitespace-char-codes))]
         {:any charsets/all-unicode-but-line-terminators
          :digit (charsets/predefined-regex-classes \d)
          :non-digit (charsets/predefined-regex-classes \D)
          :word (charsets/predefined-regex-classes \w)
          :non-word (charsets/predefined-regex-classes \W)
          :whitespace whitespace-charset
          :non-whitespace (charsets/difference
                           (charsets/intersection charsets/all-unicode
                                                  (charsets/range "\u0000" "\uFFFF"))
                           whitespace-charset)
          :newline (charsets/singleton "\n")
          :return (charsets/singleton "\r")
          :tab (charsets/singleton "\t")
          :form-feed (charsets/singleton "\f")
          :alert (charsets/singleton "\u0007")
          :escape (charsets/singleton "\u001B")
          :vertical-whitespace (charsets/predefined-regex-classes \v)
          :vertical-tab (charsets/singleton "\u000B")
          :null (charsets/singleton "\u0000")}))

     (defn token->charset [token]
       (or (get token->charset-map token)
           (throw (ex-info "Unknown token type" {:token token}))))

     (defn class->charset [cls]
       (reduce charsets/union*
               charsets/empty
               (for [c cls]
                 (try
                   (cond
                     (vector? c)
                     (let [[start end] (map str c)]
                       (assert (>= 0 (compare start end)))
                       (charsets/range start end))

                     (simple-keyword? c)
                     (token->charset c)

                     (string? c)
                     (reduce charsets/union*
                             (map (comp charsets/singleton str) c))

                     (char? c)
                     (charsets/singleton (str c)))
                   (catch Exception e
                     (throw (ex-info "Failed to translate class element into charset"
                                     {:cls cls
                                      :element c}
                                     e)))))))

     (defn class->gen [[op & elts :as expr]]
       (let [cls (class->charset elts)
             cls (case op
                   :not (charsets/difference charsets/all-unicode cls)
                   :class cls

                   (throw (ex-info "Unknown character class op" {:op op})))]
         (if (nat-int? (charsets/size cls))
           (gen/fmap #(charsets/nth cls %) (gen/choose 0 (dec (charsets/size cls))))
           (throw (ex-info "Can't generate empty class" {:expr expr})))))

     (defmethod regal-gen/-generator :not
       [r _opts]
       (class->gen r))

     (defmethod regal-gen/-generator :class
       [r _opts]
       (class->gen r))))
#_
(test-cases)
