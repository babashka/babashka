(ns cli-matic.utils-test
  (:require [clojure.test :refer [is are deftest testing]]
            [cli-matic.utils :refer [asString asStrVec
                                     indent-string indent
                                     pad deep-merge
                                     all-subcommands-aliases
                                     all-subcommands
                                     canonicalize-subcommand
                                     mk-cli-option
                                     exit! exception-info]]
            [cli-matic.platform-macros :refer [try-catch-all]]
            [cli-matic.platform :as P]
            [cli-matic.presets :as PR]))

(deftest asString-test

  (are [i o]  (= (asString i) o)

    ; a string
    "x" "x"

    ; a vector of strings
    ["a" "b"] "a\nb"

    ; a vector of vectors
    ["a" ["b" "c"] "d"]  "a\nb\nc\nd"



    ; add more cases.....
    ))

(deftest asStrVec-test

  (are [i o]
       (= (asStrVec i) o)

              ; a string
    "x" ["x"]

              ; a vector of strings
    nil []

              ; a vector of vectors
    ["a" ["b" "c"] "d"]  ["a" ["b" "c"] "d"]



              ; add more cases.....
    ))

(deftest indent-string-test
  (are [i o]  (= (indent-string i) o)
    "a" " a"))

(deftest indent-test
  (are [i o]  (= (indent i) o)

              ; a string

    "a"  " a"

              ; a vector
    ["a" "b"] [" a" " b"]))

(deftest pad-test
  (are [s s1 t o]  (= (pad s s1 t) o)

      ; shorter
    "pippo" nil 3   "pip"

      ; longer
    "pippo" nil 7   "pippo  "

      ; with merged string
    "pippo" "pluto" 10 "pippo, plu"))

(deftest deep-merge-test

  (is (=
       {:one 4 :two {:three 6}}

       (deep-merge {:one 1 :two {:three 3}}
                   {:one 2 :two {:three 4}}
                   {:one 3 :two {:three 5}}
                   {:one 4 :two {:three 6}}))))


;
; cli-matic specific
;


(defn cmd_foo [& opts])
(defn cmd_bar [& opts])
(defn cmd_returnstructure [opts]
  {:myopts opts
   :somedata "hiyo"})

(def SIMPLE-SUBCOMMAND-CFG
  {:app         {:command     "dummy"
                 :description "I am some command"
                 :version     "0.1.2"}
   :global-opts [{:option "aa" :as "A" :type :int}
                 {:option "bb" :as "B" :type :int}]
   :commands    [{:command     "foo" :short       "f"
                  :description "I am function foo"
                  :opts        [{:option "cc" :as "C" :type :int}
                                {:option "dd" :as "D" :type :int}]
                  :runs        cmd_foo}

                 ; another one
                 {:command     "bar"
                  :description "I am function bar"
                  :opts        [{:option "ee" :as "E" :type :int}
                                {:option "ff" :as "F" :type :int}]
                  :runs        cmd_bar}

                 ; this one to check return structs
                 {:command     "rets"
                  :description "I return a structure"
                  :opts        []
                  :runs        cmd_returnstructure}]})

(deftest subcommands-and-aliases
  (testing "Subcommands and aliases"
    (is (= (all-subcommands-aliases SIMPLE-SUBCOMMAND-CFG)
           {"bar" "bar"
            "f"   "foo"
            "foo" "foo"
            "rets" "rets"})))

  (testing "All subcommands"
    (is (= (all-subcommands SIMPLE-SUBCOMMAND-CFG)
           #{"bar"
             "f"
             "foo"
             "rets"})))

  (testing "Canonicalize-subcommand"
    (is (= (canonicalize-subcommand SIMPLE-SUBCOMMAND-CFG "foo")
           "foo"))
    (is (= (canonicalize-subcommand SIMPLE-SUBCOMMAND-CFG "f")
           "foo"))
    (is (= (canonicalize-subcommand SIMPLE-SUBCOMMAND-CFG "bar")
           "bar"))))

(deftest make-option
  (testing "Build a tools.cli option"
    (are [i o]
         (= o (mk-cli-option i))

      ; simplest example
      {:option "extra" :short "x" :as "Port number" :type :int}
      ["-x" "--extra N" "Port number"
       :parse-fn P/parseInt]

      ; no shorthand
      {:option "extra"  :as "Port number" :type :int}
      [nil "--extra N" "Port number"
       :parse-fn P/parseInt]

      ;  with a default
      {:option "extra"  :as "Port number" :type :int :default 13}
      [nil "--extra N" "Port number"
       :parse-fn P/parseInt :default 13]

      ;  :present means there is no default
      {:option "extra"  :as "Port number" :type :int :default :present}
      [nil "--extra N*" "Port number"
       :parse-fn P/parseInt]

      ; with-flag option
      {:option "true" :short "t" :as "A with-flag option" :type :with-flag :default false}
      ["-t" "--[no-]true" "A with-flag option"
       :default false]

      ; flag option
      {:option "flag" :short "f" :as "A flag option" :type :flag :default false}
      ["-f" "--flag F" "A flag option"
       :parse-fn PR/parseFlag
       :default false])))

(deftest test-exit!

  (is (= ["Ciao" 23]
         (try-catch-all
          (exit! "Ciao" 23)
          (fn [t]
            (exception-info t)))))

  (is (= -1
         (last (try-catch-all
                (/ 10 0)
                (fn [t]
                  (exception-info t)))))))

