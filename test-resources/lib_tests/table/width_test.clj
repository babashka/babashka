(ns table.width-test
  (:require [clojure.test :refer :all]
            [table.width :refer :all]))

(defn auto-resize [widths]
  (binding [*width* (delay 238)] (auto-resize-widths widths)))

; for max width of 238, 76 is the max-width-per-field for 3 fields
(deftest test-auto-resize-allows-over-max-field-to-get-width-from-under-max-fields
  (is
    (=
      [74 74 80]
      (auto-resize [74 74 88]))))

(deftest test-auto-resize-leaves-under-max-fields-alone
  (is
    (=
      [10 10 15]
      (auto-resize [10 10 15]))))

(deftest test-auto-resize-reduces-all-max-fields
  (is
    (=
      [76 76 76]
      (auto-resize [80 100 94]))))

(deftest ensure-valid-width-when-getting-zero-from-stty-detect
  (is (= 100 (table.width/ensure-valid-width 0))))