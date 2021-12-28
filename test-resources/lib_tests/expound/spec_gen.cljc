(ns expound.spec-gen
  (:require [clojure.spec.alpha :as s]
            [com.stuartsierra.dependency :as deps]
            [clojure.test.check.generators :as gen]
            [expound.alpha :as expound]))

;; I want to do something like
;; (s/def :specs.coll-of/into #{[] '() #{}})
;; but Clojure (not Clojurescript) won't allow
;; this. As a workaround, I'll just use vectors instead
;; of vectors and lists.
;; FIXME - force a specific type of into/kind one for each test
;; (one for vectors, one for lists, etc)

(s/def :specs.coll-of/into #{[] #{}})
(s/def :specs.coll-of/kind #{vector? list? set?})
(s/def :specs.coll-of/count pos-int?)
(s/def :specs.coll-of/max-count pos-int?)
(s/def :specs.coll-of/min-count pos-int?)
(s/def :specs.coll-of/distinct boolean?)

(s/def :specs/every-args
  (s/keys :req-un
          [:specs.coll-of/into
           :specs.coll-of/kind
           :specs.coll-of/count
           :specs.coll-of/max-count
           :specs.coll-of/min-count
           :specs.coll-of/distinct]))

(defn apply-coll-of [spec {:keys [into max-count min-count distinct]}]
  (s/coll-of spec :into into :min-count min-count :max-count max-count :distinct distinct))

(defn apply-map-of [spec1 spec2 {:keys [into max-count min-count distinct _gen-max]}]
  (s/map-of spec1 spec2 :into into :min-count min-count :max-count max-count :distinct distinct))

;; Since CLJS prints out entire source of a function when
;; it pretty-prints a failure, the output becomes much nicer if
;; we wrap each function in a simple spec
(expound/def :specs/string string? "should be a string")
(expound/def :specs/vector vector? "should be a vector")
(s/def :specs/int int?)
(s/def :specs/boolean boolean?)
(expound/def :specs/keyword keyword? "should be a keyword")
(s/def :specs/map map?)
(s/def :specs/symbol symbol?)
(s/def :specs/pos-int pos-int?)
(s/def :specs/neg-int neg-int?)
(s/def :specs/zero #(and (number? %) (zero? %)))
(s/def :specs/keys (s/keys
                    :req-un [:specs/string]
                    :req [:specs/map]
                    :opt-un [:specs/vector]
                    :opt [:specs/int]))

(def simple-spec-gen (gen/one-of
                      [(gen/elements [:specs/string
                                      :specs/vector
                                      :specs/int
                                      :specs/boolean
                                      :specs/keyword
                                      :specs/map
                                      :specs/symbol
                                      :specs/pos-int
                                      :specs/neg-int
                                      :specs/zero
                                      :specs/keys])
                       (gen/set gen/simple-type-printable)]))

(defn spec-dependencies [spec]
  (->> spec
       s/form
       (tree-seq coll? seq)
       (filter #(and (s/get-spec %) (not= spec %)))
       distinct))

(defn topo-sort [specs]
  (deps/topo-sort
   (reduce
    (fn [gr spec]
      (reduce
       (fn [g d]
         ;; If this creates a circular reference, then
         ;; just skip it.
         (if (deps/depends? g d spec)
           g
           (deps/depend g spec d)))
       gr
       (spec-dependencies spec)))
    (deps/graph)
    specs)))

#?(:clj
   (def spec-gen (gen/elements (->> (s/registry)
                                    (map key)
                                    topo-sort
                                    (filter keyword?)))))
