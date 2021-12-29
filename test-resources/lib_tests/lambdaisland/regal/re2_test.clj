(ns lambdaisland.regal.re2-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [com.gfredericks.test.chuck.properties :as prop']
            [lambdaisland.regal :as regal]
            [lambdaisland.regal.generator :as regal-gen]
            [lambdaisland.regal.test-util :refer [re2-find re2-compile]]
            [lambdaisland.regal.spec-alpha :as regal-spec]))

(defn gen-carefully [fgen else-gen]
  (try
    (let [gen (fgen)]
      (gen/->Generator
       (fn [rnd size]
         (try
           (gen/call-gen gen rnd size)
           (catch Exception _
             (gen/call-gen else-gen rnd size))))))
    (catch Exception _
      else-gen)))

(defn can-generate? [regal]
  (try
    (gen/sample (regal-gen/gen regal))
    true
    (catch Exception _
      false)))

(defspec re2-matches-like-java 10
  (with-redefs [regal-spec/token-gen #(s/gen (disj regal-spec/known-tokens :line-break :start :end))]
    (prop'/for-all [regal (s/gen ::regal/form)
                    :when (can-generate? regal)
                    s (gen-carefully #(regal-gen/gen regal)
                                     gen/string)
                    :let [java-result
                          (try (re-find (regal/regex regal) s)
                               (catch Exception _
                                 :fail))]
                    :when (not= :fail java-result)]
                   (is (= java-result
                          (re2-find (regal/with-flavor :re2
                                      (re2-compile (regal/pattern regal)))
                                    s))))))
