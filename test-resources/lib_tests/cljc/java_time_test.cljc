(ns cljc.java-time-test
  (:require [clojure.test :refer [deftest testing is]]
            [cljc.java-time.temporal.chrono-field]
            [cljc.java-time.temporal.chrono-unit]
            [cljc.java-time.temporal.iso-fields]
            [cljc.java-time.temporal.temporal]
            [cljc.java-time.temporal.temporal-accessor]
            [cljc.java-time.temporal.temporal-adjuster]
            [cljc.java-time.temporal.temporal-unit]
            [cljc.java-time.temporal.value-range]
            [cljc.java-time.format.date-time-formatter]
            [cljc.java-time.format.resolver-style]
            [cljc.java-time.format.date-time-formatter-builder]
            [cljc.java-time.format.decimal-style]
            [cljc.java-time.format.sign-style]
            [cljc.java-time.format.text-style]
            [cljc.java-time.clock :as clock]
            [cljc.java-time.day-of-week :as day-of-week]
            [cljc.java-time.duration :as duration]
            [cljc.java-time.instant :as instant]
            [cljc.java-time.local-date :as local-date]
            [cljc.java-time.local-date-time :as local-date-time]
            [cljc.java-time.local-time :as local-time]
            [cljc.java-time.month :as month]
            [cljc.java-time.month-day :as month-day]
            [cljc.java-time.offset-date-time :as offset-date-time]
            [cljc.java-time.offset-time :as offset-time]
            [cljc.java-time.period :as period]
            [cljc.java-time.year :as year]
            [cljc.java-time.year-month :as year-month]
            [cljc.java-time.zone-id :as zone-id]
            [cljc.java-time.zone-offset :as zone-offset]
            [cljc.java-time.zoned-date-time :as zoned-date-time]
            [cljc.java-time.extn.predicates :as predicates]
            
            #?(:cljs [cljs.java-time.extend-eq-and-compare])))

#?(:clj
   (deftest multi-tail-var-args
          (testing "multi-tail var args example"
            (is (let [a (make-array java.time.temporal.TemporalField 1)]
                  (aset a 0 cljc.java-time.temporal.chrono-field/nano-of-second)
                  (cljc.java-time.format.date-time-formatter/with-resolver-fields
                    cljc.java-time.format.date-time-formatter/iso-instant
                    a))))))

(deftest normal-multi-tail
  (is (year-month/of (int 12) (int 12)))
  (is (year-month/of (int 12) month/january)))

(deftest get-longs
  (testing "normal getter"
    (is (year-month/get-year
          (year-month/now))))
  (testing "getLong, which has a different name in jsjoda :-S "
    (is (year-month/get-long
          (year-month/now)
          cljc.java-time.temporal.chrono-field/month-of-year))))

(deftest leap-year 
  (testing "no obv. way to accommodate both isLeap methods. just going with the static one"
    (is (year/is-leap 24))))

(deftest of-works-in-js-and-jvm
  (is (= (local-date-time/of 2011 month/january 3 11 59) (local-date-time/of 2011 month/january 3 11 59))))

(deftest predicates
  (is (true? (predicates/clock? (clock/system-utc))))
  (is (true? (predicates/day-of-week? day-of-week/monday)))
  (is (true? (predicates/duration? (duration/parse "PT1M"))))
  (is (true? (predicates/instant? (instant/now))))
  (is (true? (predicates/local-date? (local-date/now))))
  (is (true? (predicates/local-date-time? (local-date-time/now))))
  (is (true? (predicates/local-time? (local-time/now))))
  (is (true? (predicates/month? month/may)))
  (is (true? (predicates/month-day? (month-day/now))))
  (is (true? (predicates/offset-date-time? (offset-date-time/now))))
  (is (true? (predicates/offset-time? (offset-time/now))))
  (is (true? (predicates/period? (period/parse "P1D"))))
  (is (true? (predicates/year? (year/now))))
  (is (true? (predicates/year-month? (year-month/now))))
  (is (true? (predicates/zone-id? (zone-id/system-default))))
  (is (true? (predicates/zone-offset? zone-offset/utc)))
  (is (true? (predicates/zoned-date-time? (zoned-date-time/now))))

  (is (true? (predicates/date? (local-date/now))))
  (is (true? (predicates/date-time? (local-date-time/now))))
  (is (true? (predicates/time? (local-time/now))))

  (is (false? (predicates/local-date? 16)))
  (is (false? (predicates/month? 16))))

