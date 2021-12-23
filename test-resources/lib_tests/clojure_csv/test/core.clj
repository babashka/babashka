(ns clojure-csv.test.core
  (:import [java.io StringReader])
  (:use clojure.test
        clojure.java.io
        clojure-csv.core))

(deftest basic-functionality
  (is (= [["a" "b" "c"]] (parse-csv "a,b,c")))
  (is (= [["" ""]] (parse-csv ",")))
  (is (= [["a" "b"]] (parse-csv "a,b\r\n"))) ;; Linebreak on eof won't add line.
  (is (= [] (parse-csv ""))))

(deftest alternate-sources
  (is (= [["a" "b" "c"]] (parse-csv (StringReader. "a,b,c"))))
  (is (= [["" ""]] (parse-csv (StringReader. ","))))
  (is (= [] (parse-csv (StringReader. ""))))
  (is (= [["First", "Second"]] (parse-csv
                                (reader (.toCharArray "First,Second"))))))

(deftest quoting
  (is (= [[""]] (parse-csv "\"")))
  (is (= [["\""]] (parse-csv "\"\"\"")))
  (is (= [["Before", "\"","After"]] (parse-csv "Before,\"\"\"\",After")))
  (is (= [["Before", "", "After"]] (parse-csv "Before,\"\",After")))
  (is (= [["", "start&end", ""]] (parse-csv "\"\",\"start&end\",\"\"")))
  (is (= [[",", "\"", ",,", ",,,"]]
         (parse-csv "\",\",\"\"\"\",\",,\",\",,,\"")))
  (is (= [["quoted", "\",\"", "comma"]]
         (parse-csv "quoted,\"\"\",\"\"\",comma")))
  (is (= [["Hello"]] (parse-csv "\"Hello\"")))
  (is (thrown? Exception (dorun (parse-csv "\"Hello\" \"Hello2\""))))
  (is (thrown? Exception (dorun (parse-csv "\"Hello\" \"Hello2\" \"Hello3\""))))
  (is (thrown? Exception (dorun (parse-csv "\"Hello\",\"Hello2\" \"Hello3\""))))
  (is (= [["Hello\"Hello2"]] (parse-csv "\"Hello\"\"Hello2\"")))
  (is (thrown? Exception (dorun (parse-csv "\"Hello\"Hello2"))))
  (is (= [["Hello"]] (parse-csv "\"Hello"))))

(deftest newlines
  (is (= [["test1","test2"] ["test3","test4"]]
         (parse-csv "test1,test2\ntest3,test4")))
  (is (= [["test1","test2"] ["test3","test4"]]
         (parse-csv "test1,test2\r\ntest3,test4")))
  (is (= [["embedded","line\nbreak"]] (parse-csv "embedded,\"line\nbreak\"")))
  (is (= [["embedded", "line\r\nbreak"]]
         (parse-csv "embedded,\"line\r\nbreak\""))))

(deftest writing
  (is (= "test1,test2\n" (write-csv [["test1" "test2"]])))
  (is (= "test1,test2\ntest3,test4\n"
         (write-csv [["test1" "test2"] ["test3" "test4"]])))
  (is (= "quoted:,\"line\nfeed\"\n"
         (write-csv [["quoted:" "line\nfeed"]])))
  (is (= "quoted:,\"carriage\rreturn\"\n"
         (write-csv [["quoted:" "carriage\rreturn"]])))
  (is (= "quoted:,\"embedded,comma\"\n"
         (write-csv [["quoted:" "embedded,comma"]])))
  (is (= "quoted:,\"escaped\"\"quotes\"\"\"\n"
         (write-csv [["quoted:" "escaped\"quotes\""]]))))

(deftest force-quote-on-output
  (is (= "test1,test2\n" (write-csv [["test1" "test2"]])))
  (is (= "test1,test2\n" (write-csv [["test1" "test2"]] :force-quote false)))
  (is (= "\"test1\",\"test2\"\n" (write-csv [["test1" "test2"]]
                                            :force-quote true)))
  (is (= "stillquoted:,\"needs,quote\"\n"
         (write-csv [["stillquoted:" "needs,quote"]]
                    :force-quote false)))
  (is (= "\"allquoted:\",\"needs,quote\"\n"
         (write-csv [["allquoted:" "needs,quote"]]
                    :force-quote true))))

(deftest alternate-delimiters
  (is (= [["First", "Second"]]
           (parse-csv "First\tSecond" :delimiter \tab)))
  (is (= "First\tSecond\n"
         (write-csv [["First", "Second"]] :delimiter \tab)))
  (is (= "First\tSecond,Third\n"
         (write-csv [["First", "Second,Third"]] :delimiter \tab)))
  (is (= "First\t\"Second\tThird\"\n"
         (write-csv [["First", "Second\tThird"]] :delimiter \tab))))

(deftest alternate-quote-char
  (is (= [["a", "b", "c"]]
           (parse-csv "a,|b|,c" :quote-char \|)))
  (is (= [["a", "b|c", "d"]]
           (parse-csv "a,|b||c|,d" :quote-char \|)))
  (is (= [["a", "b\"\nc", "d"]]
           (parse-csv "a,|b\"\nc|,d" :quote-char \|)))
  (is (= "a,|b||c|,d\n"
         (write-csv [["a", "b|c", "d"]] :quote-char \|)))
  (is (= "a,|b\nc|,d\n"
         (write-csv [["a", "b\nc", "d"]] :quote-char \|)))
  (is (= "a,b\"c,d\n"
         (write-csv [["a", "b\"c", "d"]] :quote-char \|))))

(deftest strictness
  (is (thrown? Exception (dorun (parse-csv "a,b,c,\"d" :strict true))))
  (is (thrown? Exception (dorun (parse-csv "a,b,c,d\"e" :strict true))))
  (is (= [["a","b","c","d"]]
           (parse-csv "a,b,c,\"d" :strict false)))
  (is (= [["a","b","c","d"]]
           (parse-csv "a,b,c,\"d\"" :strict true)))
  (is (= [["a","b","c","d\""]]
           (parse-csv "a,b,c,d\"" :strict false)))
  (is (= [["120030" "BLACK COD FILET MET VEL \"MSC\"" "KG" "0" "1"]]
           (parse-csv "120030;BLACK COD FILET MET VEL \"MSC\";KG;0;1"
                      :strict false :delimiter \;))))

(deftest reader-cases
  ;; reader will be created and closed in with-open, but used outside.
  ;; this is actually a java.io.IOException, but thrown at runtime so...
  ;; BB-TEST-PATCH: bb throws IOException instead of RuntimeException
  (is (thrown? java.io.IOException
               (dorun (with-open [sr (StringReader. "a,b,c")]
                        (parse-csv sr))))))

(deftest custom-eol
    ;; Test the use of this option.
  (is (= [["a" "b"] ["c" "d"]] (parse-csv "a,b\rc,d" :end-of-line "\r")))
  (is (= [["a" "b"] ["c" "d"]] (parse-csv "a,babcc,d" :end-of-line "abc")))
  ;; The presence of an end-of-line option turns off the parsing of \n and \r\n
  ;; as EOLs, so they can appear unquoted in fields when they do not interfere
  ;; with the EOL.
  (is (= [["a" "b\n"] ["c" "d"]] (parse-csv "a,b\n\rc,d" :end-of-line "\r")))
  (is (= [["a" "b"] ["\nc" "d"]] (parse-csv "a,b\r\nc,d" :end-of-line "\r")))
  ;; Custom EOL can still be quoted into a field.
  (is (= [["a" "b\r"] ["c" "d"]] (parse-csv "a,\"b\r\"\rc,d"
                                            :end-of-line "\r")))
  (is (= [["a" "bHELLO"] ["c" "d"]] (parse-csv "a,\"bHELLO\"HELLOc,d"
                                            :end-of-line "HELLO")))
  (is (= [["a" "b\r"] ["c" "d"]] (parse-csv "a,|b\r|\rc,d"
                                            :end-of-line "\r" :quote-char \|))))
