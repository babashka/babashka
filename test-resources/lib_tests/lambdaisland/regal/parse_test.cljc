(ns lambdaisland.regal.parse-test
  (:require [clojure.test :refer [deftest testing is are]]
            [lambdaisland.regal :as regal]
            [lambdaisland.regal.parse :as parse]))

(deftest parse-whitespace-test
  (is (= [:class " " :tab :newline :vertical-tab :form-feed :return]
         (regal/with-flavor :java
           (parse/parse-pattern "\\s"))))

  (is (= :whitespace
         (regal/with-flavor :ecma
           (parse/parse-pattern "\\s"))))

  (is (= [:not " " :tab :newline :vertical-tab :form-feed :return]
         (regal/with-flavor :java
           (parse/parse-pattern "\\S"))))

  (is (= :non-whitespace
         (regal/with-flavor :ecma
           (parse/parse-pattern "\\S")))))

(deftest ^{:kaocha/pending
           "Needs a special case in the regex generation code"}
  whitespace-round-trip
  (is (= "\\s"
         (regal/with-flavor :java
           (regal/pattern
            (parse/parse-pattern "\\s"))))))
