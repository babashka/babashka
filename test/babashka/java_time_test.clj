(ns babashka.java-time-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [expr]
  (edn/read-string (apply test-utils/bb nil [(str expr)])))

(deftest java-time-test
  (is (= "2019-12-18" (bb '(str (java.time.LocalDate/of 2019 12 18)))))
  (is (= "2019-12-01" (bb '(str
                            (-> (java.time.LocalDate/of 2019 12 18)
                                (.minusDays 17))))))
  (is (= "MONDAY" (bb '(str java.time.DayOfWeek/MONDAY))))
  (is (= "18-12-2019 16:01:41"
         (bb '(.format
               (java.time.LocalDateTime/parse "2019-12-18T16:01:41.485")
               (java.time.format.DateTimeFormatter/ofPattern "dd-MM-yyyy HH:mm:ss")))))
  (is (number? (bb "
(let [x (java.time.LocalDateTime/parse \"2019-12-18T16:01:41.485\")
      y (java.time.LocalDateTime/now)]
  (.between java.time.temporal.ChronoUnit/MINUTES x y))"))))
