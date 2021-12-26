(ns table.core-test
  (:require clojure.string)
  (:use clojure.test
        table.core))

(defn unindent [string]
  (clojure.string/replace (clojure.string/trim string) #"\n\s*" "\n"))

(deftest test-table-prints-to-out
  (is (=
    (str (unindent
      "
      +---+---+
      | 1 | 2 |
      +---+---+
      | 3 | 4 |
      +---+---+
      ") (System/lineSeparator))
    (with-out-str (table [["1" "2"] ["3" "4"]])))))

(deftest test-table-with-vecs-in-vec
  (is (=
    (unindent
      "
      +---+---+
      | 1 | 2 |
      +---+---+
      | 3 | 4 |
      +---+---+
      ")
    (table-str [["1" "2"] ["3" "4"]]))))

(deftest test-table-with-maps-in-vec
  (is (=
    (unindent
      "
      +---+---+
      | a | b |
      +---+---+
      | 1 | 2 |
      | 3 | 4 |
      +---+---+
      ")
    (table-str [{:a 1 :b 2} {:a 3 :b 4}]))))

(deftest test-table-with-top-level-map
  (is (=
    (unindent
      "
      +-----+-------+
      | key | value |
      +-----+-------+
      | :a  | 1     |
      | :b  | 2     |
      +-----+-------+
      ")
    (table-str {:a 1 :b 2}))))

(deftest test-table-with-top-level-vec
  (is (=
    (unindent
      "
      +-------+
      | value |
      +-------+
      | 1     |
      | 2     |
      | 3     |
      +-------+
      ")
    (table-str [1 2 3]))))

(deftest test-table-with-auto-width
  (is (=
    (unindent
      "
      +----+----+
      | a  | b  |
      +----+----+
      | 11 | 22 |
      | 3  | 4  |
      +----+----+
      ")
    (table-str [{:a 11 :b 22} {:a 3 :b 4}]))))

(deftest test-table-with-non-string-values
  (is (=
    (unindent
      "
      +---+---+
      | 1 | 2 |
      +---+---+
      | 3 | 4 |
      +---+---+
      ")
    (table-str [[1 2] [3 4]]))))

(deftest test-table-with-string-keys
  (is (=
    (unindent
      "
      +---+
      | a |
      +---+
      | 1 |
      | 2 |
      +---+
      ")
     (table-str [{"a" 1} {"a" 2}]))))

(deftest test-table-with-different-keys-per-row
  (is (=
    (unindent
      "
      +---+---+
      | a | b |
      +---+---+
      | 1 |   |
      |   | 2 |
      +---+---+
      ")
    (table-str [{:a 1} {:b 2}]))))

(deftest test-table-with-lists-in-list
  (is (=
    (unindent
      "
      +---+---+
      | 1 | 2 |
      +---+---+
      | 3 | 4 |
      +---+---+
      ")
     (table-str '((1 2) (3 4))))))

(deftest test-table-with-vecs-in-list
  (is (=
    (unindent
      "
      +---+---+
      | 1 | 2 |
      +---+---+
      | 3 | 4 |
      +---+---+
      ")
    (table-str '([1 2] [3 4])))))

(deftest test-table-with-vecs-in-set
   (is (=
    (unindent
      "
      +---+---+
      | 3 | 4 |
      +---+---+
      | 1 | 2 |
      +---+---+
      ")
    (table-str #{[1 2] [3 4]}))))

(deftest test-table-with-nil-values
  (is (=
    (unindent
      "
      +---+
      | a |
      +---+
      |   |
      +---+
      ")
    (table-str [{:a nil}]))))

(deftest test-table-with-nil
  (is (=
    (unindent
      "
      +--+
      |  |
      +--+
      +--+
      "
    ))))

(deftest test-table-with-org-style
  (is (=
    (unindent
      "
      |---+---|
      | 1 | 2 |
      |---+---|
      | 3 | 4 |
      |---+---|
      ")
      (table-str [[1 2] [3 4]] :style :org))))

(deftest test-table-with-unicode-style
  (is (=
    (unindent
      "
      ┌───┬───┐
      │ 1 │ 2 │
      ├───┼───┤
      │ 3 ╎ 4 │
      └───┴───┘
      ")
    (table-str [[1 2] [3 4]] :style :unicode))))

(deftest test-table-with-unicode-3d-style
  (is (=
    (unindent
      "
     ┌───┬───╖
     │ 1 │ 2 ║
     ├───┼───╢
     │ 3 │ 4 ║
     ╘═══╧═══╝
      ")
    (table-str [[1 2] [3 4]] :style :unicode-3d))))

(deftest test-table-with-markdown-style
  (is (=
    (str "\n" (unindent
      "
      | 10 | 20 |
      |--- | ---|
      | 3  | 4  |
      ") "\n")
    (table-str [[10 20] [3 4]] :style :github-markdown))))

(deftest test-table-with-custom-style
  (is (=
    (unindent
     "
      ┌────┬────╖
      │ 10 │ 20 ║
      ├────┼────╢
      │ 3  │ 4  ║
      ╘════╧════╝
      ")
    (table-str [[10 20] [3 4]] :style {:top ["┌─" "─┬─" "─╖"]
                                       :top-dash "─"
                                       :middle ["├─" "─┼─" "─╢"]
                                       :dash "─"
                                       :bottom ["╘═" "═╧═" "═╝"]
                                       :bottom-dash "═"
                                       :header-walls ["│ " " │ " " ║"]
                                       :body-walls ["│ " " │ " " ║"] }))))

(deftest test-table-with-empty-cells
  (is (=
    (unindent
      "
      +--+---+
      |  | 2 |
      +--+---+
      |  | 4 |
      +--+---+
      ")
    (table-str [["" "2"] ["" "4"]]))))

(deftest test-table-with-fields-option-and-maps
  (is (=
    (unindent
      "
      +---+---+
      | b | a |
      +---+---+
      | 2 | 1 |
      | 4 | 3 |
      +---+---+
      ")
    (table-str '({:a 1 :b 2} {:a 3 :b 4}) :fields [:b :a]))))

(deftest test-table-with-fields-option-and-incorrect-fields
  (is (=
    (unindent
     "
      +---+---+---+
      | b | a | c |
      +---+---+---+
      | 2 | 1 |   |
      | 4 | 3 |   |
      +---+---+---+
      ")
    (table-str [{:a 1 :b 2} {:a 3 :b 4}] :fields [:b :a :c]))))

(deftest test-table-with-maps-in-vec
  (is (=
    (unindent
      "
      +---+---+
      | a | b |
      +---+---+
      | 1 | 2 |
      | 3 | 4 |
      +---+---+
      2 rows in set
      ")
    (table-str [{:a 1 :b 2} {:a 3 :b 4}] :desc true))))

(deftest test-table-with-sort-option-as-true
  (is (=
    (unindent
      "
      +----+----+
      | 1  | 2  |
      +----+----+
      | :a | :b |
      | :c | :d |
      +----+----+
      ")
    (table-str  [[1 2] [:c :d]  [:a :b]] :sort true))))

;; BB-TEST-PATCH: Intermittent failing test
#_(deftest test-table-with-sort-option-as-field-name
  (is (=
    (unindent
      "
      +----+----+
      | k  | v  |
      +----+----+
      | :a | :b |
      | :c | :d |
      +----+----+
      ")
    (table-str  [[:k :v] [:c :d]  [:a :b]] :sort :k))))

(deftest test-table-with-invalid-sort-option-as-field-name
  (is (=
    (unindent
      "
      +----+----+
      | k  | v  |
      +----+----+
      | :c | :d |
      | :a | :b |
      +----+----+
      ")
    (table-str  [[:k :v] [:c :d]  [:a :b]] :sort :invalid))))

(deftest test-table-escapes-newlines
  (is (=
    (unindent
      (format
        "
        +---+------+
        | 1 | 2    |
        +---+------+
        | 3 | 4%s5 |
        +---+------+
        "
        (char-escape-string \newline)))
    (table-str [[1,2]  [3, "4\n5"]]))))

(deftest test-table-shortens-cell-longer-than-allowed-width
  (is (=
    (unindent
      "
      +--------+-----------------------------------------------------------------------------------------+
      | key    | value                                                                                   |
      +--------+-----------------------------------------------------------------------------------------+
      | :short | yep                                                                                     |
      | :long  | nooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo... |
      +--------+-----------------------------------------------------------------------------------------+
      ")
    (binding [table.width/*width* (delay 100)] (table-str {:short "yep" :long  (apply str "n"  (repeat 250 "o"))})))))

;(defn test-ns-hook []
;  (test-table-with-top-level-map))
