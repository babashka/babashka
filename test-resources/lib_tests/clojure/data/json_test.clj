(ns clojure.data.json-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [clojure.string :as str]))

(deftest read-from-pushback-reader
  (let [s (java.io.PushbackReader. (java.io.StringReader. "42"))]
    (is (= 42 (json/read s)))))

(deftest read-from-reader
  (let [s (java.io.StringReader. "42")]
    (is (= 42 (json/read s)))))

(deftest read-numbers
  (is (= 42 (json/read-str "42")))
  (is (= -3 (json/read-str "-3")))
  (is (= 3.14159 (json/read-str "3.14159")))
  (is (= 6.022e23 (json/read-str "6.022e23"))))

(deftest read-bigint
  (is (= 123456789012345678901234567890N
         (json/read-str "123456789012345678901234567890"))))

(deftest write-bigint
  (is (= "123456789012345678901234567890"
         (json/write-str 123456789012345678901234567890N))))

(deftest read-bigdec
  (is (= 3.14159M (json/read-str "3.14159" :bigdec true))))

(deftest write-bigdec
  (is (= "3.14159" (json/write-str 3.14159M))))

(deftest read-null
  (is (= nil (json/read-str "null"))))

(deftest read-strings
  (is (= "Hello, World!" (json/read-str "\"Hello, World!\""))))

(deftest escaped-slashes-in-strings
  (is (= "/foo/bar" (json/read-str "\"\\/foo\\/bar\""))))

(deftest unicode-escapes
  (is (= " \u0beb " (json/read-str "\" \\u0bEb \""))))

(deftest unicode-outside-bmp
  (is (= "\"smiling face: \uD83D\uDE03\""
         (json/write-str "smiling face: \uD83D\uDE03" :escape-unicode false)))
  (is (= "\"smiling face: \\ud83d\\ude03\""
         (json/write-str "smiling face: \uD83D\uDE03" :escape-unicode true))))

(deftest escaped-whitespace
  (is (= "foo\nbar" (json/read-str "\"foo\\nbar\"")))
  (is (= "foo\rbar" (json/read-str "\"foo\\rbar\"")))
  (is (= "foo\tbar" (json/read-str "\"foo\\tbar\""))))

(deftest read-booleans
  (is (= true (json/read-str "true")))
  (is (= false (json/read-str "false"))))

(deftest ignore-whitespace
  (is (= nil (json/read-str "\r\n   null"))))

(deftest read-arrays
  (is (= (vec (range 35))
         (json/read-str "[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34]")))
  (is (= ["Ole" "Lena"] (json/read-str "[\"Ole\", \r\n \"Lena\"]"))))

(deftest read-objects
  (is (= {:k1 1, :k2 2, :k3 3, :k4 4, :k5 5, :k6 6, :k7 7, :k8 8
          :k9 9, :k10 10, :k11 11, :k12 12, :k13 13, :k14 14, :k15 15, :k16 16}
         (json/read-str "{\"k1\": 1, \"k2\": 2, \"k3\": 3, \"k4\": 4,
                          \"k5\": 5, \"k6\": 6, \"k7\": 7, \"k8\": 8,
                          \"k9\": 9, \"k10\": 10, \"k11\": 11, \"k12\": 12,
                          \"k13\": 13, \"k14\": 14, \"k15\": 15, \"k16\": 16}"
                        :key-fn keyword))))

(deftest read-nested-structures
  (is (= {:a [1 2 {:b [3 "four"]} 5.5]}
         (json/read-str "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}"
                        :key-fn keyword))))

(deftest read-nested-structures-stream
  (is (= {:a [1 2 {:b [3 "four"]} 5.5]}
         (json/read (java.io.StringReader. "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}")
                    :key-fn keyword))))

(deftest reads-long-string-correctly
  (let [long-string (str/join "" (take 100 (cycle "abcde")))]
    (is (= long-string (json/read-str (str "\"" long-string "\""))))))

(deftest disallows-non-string-keys
  (is (thrown? Exception (json/read-str "{26:\"z\""))))

(deftest disallows-barewords
  (is (thrown? Exception (json/read-str "  foo  "))))

(deftest disallows-unclosed-arrays
  (is (thrown? Exception (json/read-str "[1, 2,  "))))

(deftest disallows-unclosed-objects
  (is (thrown? Exception (json/read-str "{\"a\":1,  "))))

(deftest disallows-empty-entry-in-object
  (is (thrown? Exception (json/read-str "{\"a\":1,}")))
  (is (thrown? Exception (json/read-str "{\"a\":1, }")))
  (is (thrown? Exception (json/read-str "{\"a\":1,,,,}")))
  (is (thrown? Exception (json/read-str "{\"a\":1,,\"b\":2}"))))

(deftest get-string-keys
  (is (= {"a" [1 2 {"b" [3 "four"]} 5.5]}
         (json/read-str "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}"))))

(deftest keywordize-keys
  (is (= {:a [1 2 {:b [3 "four"]} 5.5]}
         (json/read-str "{\"a\":[1,2,{\"b\":[3,\"four\"]},5.5]}"
                    :key-fn keyword))))

(deftest convert-values
  (is (= {:number 42 :date (java.sql.Date. 55 6 12)}
         (json/read-str "{\"number\": 42, \"date\": \"1955-07-12\"}"
                    :key-fn keyword
                    :value-fn (fn [k v]
                                (if (= :date k)
                                  (java.sql.Date/valueOf v)
                                  v))))))

(deftest omit-values
  (is (= {:number 42}
         (json/read-str "{\"number\": 42, \"date\": \"1955-07-12\"}"
                    :key-fn keyword
                    :value-fn (fn thisfn [k v]
                                (if (= :date k)
                                  thisfn
                                  v)))))
  (is (= "{\"c\":1,\"e\":2}"
         (json/write-str (sorted-map :a nil, :b nil, :c 1, :d nil, :e 2, :f nil)
                         :value-fn (fn remove-nils [k v]
                                     (if (nil? v)
                                       remove-nils
                                       v))))))

(declare pass1-string)

(deftest pass1-test
  (let [input (json/read-str pass1-string)]
    (is (= "JSON Test Pattern pass1" (first input)))
    (is (= "array with 1 element" (get-in input [1 "object with 1 member" 0])))
    (is (= 1234567890 (get-in input [8 "integer"])))
    (is (= "rosebud" (last input)))))

; from http://www.json.org/JSON_checker/test/pass1.json
(def pass1-string
     "[
    \"JSON Test Pattern pass1\",
    {\"object with 1 member\":[\"array with 1 element\"]},
    {},
    [],
    -42,
    true,
    false,
    null,
    {
        \"integer\": 1234567890,
        \"real\": -9876.543210,
        \"e\": 0.123456789e-12,
        \"E\": 1.234567890E+34,
        \"\":  23456789012E66,
        \"zero\": 0,
        \"one\": 1,
        \"space\": \" \",
        \"quote\": \"\\\"\",
        \"backslash\": \"\\\\\",
        \"controls\": \"\\b\\f\\n\\r\\t\",
        \"slash\": \"/ & \\/\",
        \"alpha\": \"abcdefghijklmnopqrstuvwyz\",
        \"ALPHA\": \"ABCDEFGHIJKLMNOPQRSTUVWYZ\",
        \"digit\": \"0123456789\",
        \"0123456789\": \"digit\",
        \"special\": \"`1~!@#$%^&*()_+-={':[,]}|;.</>?\",
        \"hex\": \"\\u0123\\u4567\\u89AB\\uCDEF\\uabcd\\uef4A\",
        \"true\": true,
        \"false\": false,
        \"null\": null,
        \"array\":[  ],
        \"object\":{  },
        \"address\": \"50 St. James Street\",
        \"url\": \"http://www.JSON.org/\",
        \"comment\": \"// /* <!-- --\",
        \"# -- --> */\": \" \",
        \" s p a c e d \" :[1,2 , 3

,

4 , 5        ,          6           ,7        ],\"compact\":[1,2,3,4,5,6,7],
        \"jsontext\": \"{\\\"object with 1 member\\\":[\\\"array with 1 element\\\"]}\",
        \"quotes\": \"&#34; \\u0022 %22 0x22 034 &#x22;\",
        \"\\/\\\\\\\"\\uCAFE\\uBABE\\uAB98\\uFCDE\\ubcda\\uef4A\\b\\f\\n\\r\\t`1~!@#$%^&*()_+-=[]{}|;:',./<>?\"
: \"A key can be any string\"
    },
    0.5 ,98.6
,
99.44
,

1066,
1e1,
0.1e1,
1e-1,
1e00,2e+00,2e-00
,\"rosebud\"]")


(deftest print-json-strings
  (is (= "\"Hello, World!\"" (json/write-str "Hello, World!")))
  (is (= "\"\\\"Embedded\\\" Quotes\"" (json/write-str "\"Embedded\" Quotes"))))

(deftest print-unicode
  (is (= "\"\\u1234\\u4567\"" (json/write-str "\u1234\u4567"))))

(deftest print-nonescaped-unicode
  (is (= "\"\\u0000\\t\\u001f \"" (json/write-str "\u0000\u0009\u001f\u0020" :escape-unicode true)))
  (is (= "\"\\u0000\\t\\u001f \"" (json/write-str "\u0000\u0009\u001f\u0020" :escape-unicode false)))
  (is (= "\"\u1234\u4567\"" (json/write-str "\u1234\u4567" :escape-unicode false))))

(deftest escape-special-separators
  (is (= "\"\\u2028\\u2029\"" (json/write-str "\u2028\u2029" :escape-unicode false)))
  (is (= "\"\u2028\u2029\"" (json/write-str "\u2028\u2029" :escape-js-separators false))))

(deftest print-json-null
  (is (= "null" (json/write-str nil))))

(deftest print-ratios-as-doubles
  (is (= "0.75" (json/write-str 3/4))))

(deftest print-bigints
  (is (= "12345678901234567890" (json/write-str 12345678901234567890))))

(deftest print-uuids
  (let [uid (java.util.UUID/randomUUID)
        roundtripped  (java.util.UUID/fromString (json/read-str (json/write-str uid)))]
    (is (= uid roundtripped))))

;; BB-TEST-PATCH: bb doesn't have SimpleDateFormat
#_(def ^java.text.SimpleDateFormat date-format
  (doto (java.text.SimpleDateFormat. "dd-MM-yyyy hh:mm:ss")
    (.setTimeZone  (java.util.TimeZone/getDefault))))

#_(deftest print-util-date
  (let [date (.parse date-format "24-03-2006 15:49:00")
        epoch-millis (.getTime date)]
    (is (= epoch-millis (-> date
                            json/write-str
                            json/read-str
                            java.time.Instant/parse
                            .toEpochMilli)))))

#_(deftest print-sql-date
  (let [date (.parse date-format "24-03-2006 15:49:00")
        sql-date (java.sql.Date. (.getTime date))
        epoch-millis-start-of-day (.getTime (.getTime (doto (java.util.Calendar/getInstance)
                                                        (.setTime date)
                                                        (.set java.util.Calendar/HOUR_OF_DAY 0)
                                                        (.set java.util.Calendar/MINUTE 0)
                                                        (.set java.util.Calendar/SECOND 0)
                                                        (.set java.util.Calendar/MILLISECOND 0))))]
    (is (= epoch-millis-start-of-day (-> sql-date
                                         json/write-str
                                         json/read-str
                                         java.time.Instant/parse
                                         .toEpochMilli)))))

(deftest print-time
  (let [time (java.time.Instant/parse "2006-03-24T15:49:00.000Z")]
    (is (= time (java.time.Instant/parse (json/read-str (json/write-str time)))))))


#_(deftest print-time-supports-format
  (let [formatter (.withZone java.time.format.DateTimeFormatter/ISO_ZONED_DATE_TIME
                             (java.time.ZoneId/systemDefault))
        date (.parse date-format "24-03-2006 15:49:00")
        time (.toInstant (.atZone (java.time.LocalDateTime/parse
                                   "2006-03-24T15:49:00.000Z"
                                   formatter)
                                 (java.time.ZoneId/systemDefault)))]
    (is (= time (->> (json/write-str date :date-formatter formatter)
                     json/read-str
                     (.parse formatter)
                     (java.time.Instant/from))))))

(deftest error-on-NaN
  (is (thrown? Exception (json/write-str Float/NaN)))
  (is (thrown? Exception (json/write-str Double/NaN))))

(deftest error-on-infinity
  (is (thrown? Exception (json/write-str Float/POSITIVE_INFINITY)))
  (is (thrown? Exception (json/write-str Float/NEGATIVE_INFINITY)))
  (is (thrown? Exception (json/write-str Double/POSITIVE_INFINITY)))
  (is (thrown? Exception (json/write-str Double/NEGATIVE_INFINITY))))

(defn- double-value [_ v]
  (if (and (instance? Double v)
           (or (.isNaN ^Double v)
               (.isInfinite ^Double v)))
    (str v)
    v))

(deftest special-handler-for-double-NaN
  (is (= "{\"double\":\"NaN\"}"
         (json/write-str {:double Double/NaN}
                         :value-fn double-value))))

(deftest special-handler-for-double-infinity
  (is (= "{\"double\":\"Infinity\"}"
         (json/write-str {:double Double/POSITIVE_INFINITY}
                         :value-fn double-value)))
  (is (= "{\"double\":\"-Infinity\"}"
         (json/write-str {:double Double/NEGATIVE_INFINITY}
                         :value-fn double-value))))

(deftest print-json-arrays
  (is (= "[1,2,3]" (json/write-str [1 2 3])))
  (is (= "[1,2,3]" (json/write-str (list 1 2 3))))
  (is (= "[1,2,3]" (json/write-str (sorted-set 1 2 3))))
  (is (= "[1,2,3]" (json/write-str (seq [1 2 3])))))

(deftest print-java-arrays
 (is (= "[1,2,3]" (json/write-str (into-array [1 2 3])))))

(deftest print-empty-arrays
  (is (= "[]" (json/write-str [])))
  (is (= "[]" (json/write-str (list))))
  (is (= "[]" (json/write-str #{}))))

(deftest print-json-objects
  (is (= "{\"a\":1,\"b\":2}" (json/write-str (sorted-map :a 1 :b 2)))))

(deftest object-keys-must-be-strings
  (is (= "{\"1\":1,\"2\":2}" (json/write-str (sorted-map 1 1 2 2)))))

(deftest print-empty-objects
  (is (= "{}" (json/write-str {}))))

(deftest accept-sequence-of-nils
  (is (= "[null,null,null]" (json/write-str [nil nil nil]))))

(deftest error-on-nil-keys
  (is (thrown? Exception (json/write-str {nil 1}))))

(deftest characters-in-symbols-are-escaped
  (is (= "\"foo\\u1b1b\"" (json/write-str (symbol "foo\u1b1b")))))

(deftest default-throws-on-eof
  (is (thrown? java.io.EOFException (json/read-str ""))))

(deftest throws-eof-in-unterminated-array
  (is (thrown? java.io.EOFException
        (json/read-str "[1, "))))

(deftest throws-eof-in-unterminated-string
  (is (thrown? java.io.EOFException
        (json/read-str "\"missing end quote"))))

(deftest throws-eof-in-escaped-chars
  (is (thrown? java.io.EOFException
        (json/read-str "\"\\"))))

(deftest accept-eof
  (is (= ::eof (json/read-str "" :eof-error? false :eof-value ::eof))))

(deftest characters-in-map-keys-are-escaped
  (is (= "{\"\\\"\":42}" (json/write-str {"\"" 42}))))

;;; Indent

(deftest print-json-arrays-indent
  (is (= "[\n  1,\n  2,\n  3\n]" (json/write-str [1 2 3] :indent true)))
  (is (= "[\n  1,\n  2,\n  3\n]" (json/write-str (list 1 2 3) :indent true)))
  (is (= "[\n  1,\n  2,\n  3\n]" (json/write-str (sorted-set 1 2 3) :indent true)))
  (is (= "[\n  1,\n  2,\n  3\n]" (json/write-str (seq [1 2 3]) :indent true))))

(deftest print-java-arrays-indent
 (is (= "[\n  1,\n  2,\n  3\n]" (json/write-str (into-array [1 2 3]) :indent true))))

(deftest print-empty-arrays-indent
  (is (= "[]" (json/write-str [] :indent true)))
  (is (= "[]" (json/write-str (list) :indent true)))
  (is (= "[]" (json/write-str #{} :indent true))))

(deftest print-json-objects-indent
  (is (= "{\n  \"a\": 1,\n  \"b\": 2\n}" (json/write-str (sorted-map :a 1 :b 2) :indent true))))

(deftest print-empty-objects-indent
  (is (= "{}" (json/write-str {} :indent true))))

(deftest print-json-nested-indent
  (is (=
"{
  \"a\": {
    \"b\": [
      1,
      2
    ],
    \"c\": [],
    \"d\": {}
  }
}" (json/write-str {:a (sorted-map :b [1 2] :c [] :d {})} :indent true))))


;;; Pretty-printer

(deftest pretty-printing
  (let [x (json/read-str pass1-string)]
    (is (= x (json/read-str (with-out-str (json/pprint x)))))))

(deftest pretty-print-nonescaped-unicode
  (is (= "\"\u1234\u4567\"\n"
         (with-out-str
           (json/pprint "\u1234\u4567" :escape-unicode false)))))

(defn benchmark []
  (dotimes [_ 8]
    (time
     (dotimes [_ 1000]
       (assert (= (json/read-str pass1-string)
                  (json/read-str (json/write-str (json/read-str pass1-string)))))))))
