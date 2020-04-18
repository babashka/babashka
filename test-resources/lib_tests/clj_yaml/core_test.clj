(ns clj-yaml.core-test
  (:require [clojure.test :refer (deftest testing is)]
            [clojure.string :as string]
            [clj-yaml.core :refer [parse-string unmark generate-string]])
  (:import [java.util Date]))

(def nested-hash-yaml
  "root:\n  childa: a\n  childb: \n    grandchild: \n      greatgrandchild: bar\n")

(def list-yaml
  "--- # Favorite Movies\n- Casablanca\n- North by Northwest\n- The Man Who Wasn't There")

(def hashes-lists-yaml "
items:
  - part_no:   A4786
    descrip:   Water Bucket (Filled)
    price:     1.47
    quantity:  4

  - part_no:   E1628
    descrip:   High Heeled \"Ruby\" Slippers
    price:     100.27
    quantity:  1
    owners:
      - Dorthy
      - Wicked Witch of the East
")

(def inline-list-yaml
"--- # Shopping list
[milk, pumpkin pie, eggs, juice]
")

(def inline-hash-yaml
  "{name: John Smith, age: 33}")

(def list-of-hashes-yaml "
- {name: John Smith, age: 33}
- name: Mary Smith
  age: 27
")

(def hashes-of-lists-yaml "
men: [John Smith, Bill Jones]
women:
  - Mary Smith
  - Susan Williams
")

(def typed-data-yaml "
the-bin: !!binary 0101")

(def io-file-typed-data-yaml "
!!java.io.File")

(def set-yaml "
--- !!set
? Mark McGwire
? Sammy Sosa
? Ken Griff")

(deftest parse-hash
  (let [parsed (parse-string "foo: bar")]
    (is (= "bar" (parsed :foo)))))

(deftest parse-hash-with-numeric-key
  (let [parsed (parse-string "123: 456")]
    (is (= 456 (parsed 123)))))

(deftest parse-hash-with-complex-key
  (let [parsed (parse-string "[1, 2]: 3")]
    (is (= 3 (parsed [1, 2])))))

(deftest parse-nested-hash
  (let [parsed (parse-string nested-hash-yaml)]
    (is (= "a"   ((parsed :root) :childa)))
    (is (= "bar" ((((parsed :root) :childb) :grandchild) :greatgrandchild)))))

(deftest parse-list
  (let [parsed (parse-string list-yaml)]
    (is (= "Casablanca"               (first parsed)))
    (is (= "North by Northwest"       (nth parsed 1)))
    (is (= "The Man Who Wasn't There" (nth parsed 2)))))

(deftest parse-nested-hash-and-list
  (let [parsed (parse-string hashes-lists-yaml)]
    (is (= "A4786"  ((first (parsed :items)) :part_no)))
    (is (= "Dorthy" (first ((nth (parsed :items) 1) :owners))))))

(deftest parse-inline-list
  (let [parsed (parse-string inline-list-yaml)]
    (is (= "milk"        (first parsed)))
    (is (= "pumpkin pie" (nth   parsed 1)))
    (is (= "eggs"        (nth   parsed 2)))
    (is (= "juice"       (last  parsed)))))

(deftest parse-inline-hash
  (let [parsed (parse-string inline-hash-yaml)]
    (is (= "John Smith" (parsed :name)))
    (is (= 33           (parsed :age)))))

(deftest parse-list-of-hashes
  (let [parsed (parse-string list-of-hashes-yaml)]
    (is (= "John Smith" ((first parsed) :name)))
    (is (= 33           ((first parsed) :age)))
    (is (= "Mary Smith" ((nth parsed 1) :name)))
    (is (= 27           ((nth parsed 1) :age)))))

(deftest hashes-of-lists
  (let [parsed (parse-string hashes-of-lists-yaml)]
    (is (= "John Smith"     (first (parsed :men))))
    (is (= "Bill Jones"     (last  (parsed :men))))
    (is (= "Mary Smith"     (first (parsed :women))))
    (is (= "Susan Williams" (last  (parsed :women))))))

(deftest h-set
  (is (= #{"Mark McGwire" "Ken Griff" "Sammy Sosa"}
         (parse-string set-yaml))))

(deftest typed-data
  (let [parsed (parse-string typed-data-yaml)]
    (is (= (Class/forName "[B") (type (:the-bin parsed))))))

(deftest disallow-arbitrary-typed-data
  (is (thrown? org.yaml.snakeyaml.error.YAMLException
               (parse-string io-file-typed-data-yaml))))

(deftest keywordized
  (is (= "items"
         (-> hashes-lists-yaml
             (parse-string :keywords false)
             ffirst))))

(deftest not-keywordized-in-lists
  (is (every? string?
              (-> "[{b: c, c: d}]"
                  (parse-string :keywords false)
                  first
                  keys))))

(deftest marking-source-position-works
  (let [parsed (parse-string inline-list-yaml :mark true)]
    ;; The list starts at the beginning of line 1.
    (is (= 1 (-> parsed :start :line)))
    (is (= 0 (-> parsed :start :column)))
    ;; The first item starts at the second character of line 1.
    (is (= 1 (-> parsed unmark first :start :line)))
    (is (= 1 (-> parsed unmark first :start :column)))
    ;; The first item ends at the fifth character of line 1.
    (is (= 1 (-> parsed unmark first :end :line)))
    (is (= 5 (-> parsed unmark first :end :column)))))

(deftest text-wrapping
  (let [data
        {:description
         "Big-picture diagram showing how our top-level systems and stakeholders interact"}]
    (testing "long lines of text should not be wrapped"
      ;; clj-yaml 0.5.6 used SnakeYAML 1.13 which by default did *not* split long lines.
      ;; clj-yaml 0.6.0 upgraded to SnakeYAML 1.23 which by default *did* split long lines.
      ;; This test ensures that generate-string uses the older behavior by default, for the sake
      ;; of stability, i.e. backwards compatibility.
      (is
        (= "{description: Big-picture diagram showing how our top-level systems and stakeholders interact}\n"
           (generate-string data))))))

(deftest dump-opts
  (let [data [{:age 33 :name "jon"} {:age 44 :name "boo"}]]
    (is (= "- age: 33\n  name: jon\n- age: 44\n  name: boo\n"
           (generate-string data :dumper-options {:flow-style :block})))
    (is (= "[{age: 33, name: jon}, {age: 44, name: boo}]\n"
           (generate-string data :dumper-options {:flow-style :flow})))))

;; TODO: this test is failing in GraalVM
;; Could be related to https://github.com/oracle/graal/issues/2234
#_(deftest parse-time
  (testing "clj-time parses timestamps with more than millisecond precision correctly."
    (let [timestamp "2001-11-23 15:02:31.123456 -04:00"
          expected 1006542151123]
      (is (= (.getTime ^Date (parse-string timestamp)) expected)))))

(deftest maps-are-ordered
  (let [parsed (parse-string hashes-lists-yaml)
        [first second] (:items parsed)]
    (is (= (keys first) '(:part_no :descrip :price :quantity)))
    (is (= (keys second)'(:part_no :descrip :price :quantity :owners)))))


(deftest nulls-are-fine
  (testing "nil does not blow up"
    (let [res (parse-string "- f:")]
      (is (= [{:f nil}] res))
      (is (str res)))))

(deftest emoji-can-be-parsed
  (let [yaml "{emoji: 💣}"]
    (is (= yaml (-> yaml
                    (generate-string)
                    (parse-string)
                    (string/trim)))))

  (testing "emoji in comments are OK too"
    (let [yaml "# 💣 emoji in a comment\n42"]
      (is (= 42 (parse-string yaml))))))
