(ns babashka.stacktrace-test
  (:require
   [babashka.test-utils :as tu]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]))

(deftest stacktrace-test
  (try (tu/bb nil (.getPath (io/file "test" "babashka" "scripts" "divide_by_zero.bb")))
       (catch Exception e
         (let [msg (ex-message e)
               lines (filterv #(str/starts-with? % "  ")
                              (str/split-lines msg))
               matches [#"clojure\.core/"
                        #"user/foo .*divide_by_zero.bb:2:3"
                        #"user/foo .*divide_by_zero.bb:1:1"
                        #"user/bar .*divide_by_zero.bb:5:3"
                        #"user/bar .*divide_by_zero.bb:4:1"
                        #"user .*divide_by_zero.bb:7:1"]]
           (doseq [[l m] (map vector lines matches)]
             (is (re-find m l)))))))
