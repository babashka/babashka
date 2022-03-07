(ns cli-matic.utils-v2-test
  (:require [clojure.test :refer [is are deftest testing]]
            #?(:clj [cli-matic.platform-macros :refer [try-catch-all]]
               :cljs [cli-matic.platform-macros :refer-macros [try-catch-all]])
            [cli-matic.optionals :as OPT]
            [cli-matic.utils-v2 :refer [convert-config-v1->v2
                                        walk
                                        can-walk?
                                        as-canonical-path
                                        get-most-specific-value]]))


;
; dummy functions
;


(defn add_numbers [x] x)
(defn subtract_numbers [x] x)

(deftest convert-config-v1->v2-test

  (are [i o]  (= (convert-config-v1->v2 i) o)

    ; ============== TEST 1 ===============
    ;  Input
    {:app         {:command     "toycalc"
                   :description "A command-line toy calculator"
                   :version     "0.0.1"}

     :global-opts [{:option  "base"
                    :as      "The number base for output"
                    :type    :int
                    :default 10}]

     :commands    [{:command     "add"
                    :description "Adds two numbers together"
                    :opts        [{:option "a" :as "Addendum 1" :type :int}
                                  {:option "b" :as "Addendum 2" :type :int :default 0}]
                    :runs        add_numbers}

                   {:command     "sub"
                    :description "Subtracts parameter B from A"
                    :opts        [{:option "a" :as "Parameter A" :type :int :default 0}
                                  {:option "b" :as "Parameter B" :type :int :default 0}]
                    :runs        subtract_numbers}]}

    ; Output
    {:command     "toycalc"
     :description "A command-line toy calculator"
     :version     "0.0.1"
     :opts        [{:as      "The number base for output"
                    :default 10
                    :option  "base"
                    :type    :int}]
     :subcommands [{:command     "add"
                    :description "Adds two numbers together"
                    :opts        [{:as     "Addendum 1"
                                   :option "a"
                                   :type   :int}
                                  {:as      "Addendum 2"
                                   :default 0
                                   :option  "b"
                                   :type    :int}]
                    :runs        add_numbers}
                   {:command     "sub"
                    :description "Subtracts parameter B from A"
                    :opts        [{:as      "Parameter A"
                                   :default 0
                                   :option  "a"
                                   :type    :int}
                                  {:as      "Parameter B"
                                   :default 0
                                   :option  "b"
                                   :type    :int}]
                    :runs        subtract_numbers}]}))

(deftest walk-test

  (let [cfg {:command     "toycalc"
             :description "A command-line toy calculator"
             :version     "0.0.1"
             :opts        [{:as      "The number base for output"
                            :default 10
                            :option  "base"
                            :type    :int}]
             :subcommands [{:command     "add"
                            :description "Adds two numbers together"
                            :opts        [{:as     "Addendum 1"
                                           :option "a"
                                           :type   :int}
                                          {:as      "Addendum 2"
                                           :default 0
                                           :option  "b"
                                           :type    :int}]
                            :runs        add_numbers}
                           {:command     "subc"
                            :description "Subtracts parameter B from A"
                            :opts        [{:as      "Parameter q"
                                           :default 0
                                           :option  "q"
                                           :type    :int}]

                            :subcommands [{:command     "sub"
                                           :description "Subtracts"
                                           :opts        [{:as      "Parameter A"
                                                          :default 0
                                                          :option  "a"
                                                          :type    :int}
                                                         {:as      "Parameter B"
                                                          :default 0
                                                          :option  "b"
                                                          :type    :int}]
                                           :runs        subtract_numbers}]}]}]

    (are [p o]  (=
                 (try-catch-all
                  (as-canonical-path (walk cfg p))
                  (fn [_] :ERR))

                 o)

        ; es 1
      ["toycalc" "add"]
      ["toycalc" "add"]

              ; es 2
      ["toycalc" "subc" "sub"]
      ["toycalc" "subc" "sub"]

              ; not found
      ["toycalc" "addq"]
      :ERR

      ["toycalc" "subc" "xx"]
      :ERR)

    (are [p o]  (= (can-walk? cfg p) o)

      ; es 1
      ["toycalc" "add"]
      true

      ; es 2
      ["toycalc" "subc" "sub"]
      true

      ; not found
      ["toycalc" "addq"]
      false

      ["toycalc" "subc" "xx"]
      false))

  (let [cfg-one {:command     "onlyone"
                 :description "A single subcommand"
                 :version     "0.0.1"
                 :opts        [{:as      "The number base for output"
                                :default 10
                                :option  "base"
                                :type    :int}]
                 :runs        subtract_numbers}]

    (are [p o]  (=
                 (try-catch-all
                  (as-canonical-path (walk cfg-one p))
                  (fn [_] :ERR))

                 o)

      ; es 1
      ["onlyone"]
      ["onlyone"]


      ; Nothing


      []
      ["onlyone"]

      ; notfound
      ["toycalc" "subc" "xx"]
      :ERR)))



; :on-shutdown


(defn shutdown_BASE [] 0)
(defn shutdown_SUB  [] 1)

(deftest get-most-specific-value-test

  (let [cfg {:command     "toycalc"
             :description "A command-line toy calculator"
             :version     "0.0.1"
             :on-shutdown shutdown_BASE
             :opts        []
             :subcommands [{:command     "add"
                            :description "Adds two numbers together"
                            :opts        []
                            :runs        add_numbers}
                           {:command     "subc"
                            :description "Subtracts parameter B from A"
                            :opts        []
                            :subcommands [{:command     "sub"
                                           :description "Subtracts"
                                           :opts        []
                                           :runs        subtract_numbers
                                           :on-shutdown shutdown_SUB}]}]}]

    (are [p o]
         (= (try-catch-all
             (get-most-specific-value cfg p :on-shutdown "-NF-")
             (fn [_] :ERR))
            o)

      ; Definito nella root
      ["toycalc"]
      shutdown_BASE

      ; Sempre da root
      ["toycalc" "add"]
      shutdown_BASE

      ; non definito, quindi uso root
      ["toycalc" "subc"]
      shutdown_BASE

      ; definito specifico
      ["toycalc" "subc" "sub"]
      shutdown_SUB

      ; not found
      ["toycalc" "addq"]
      :ERR

      ["toycalc" "subc" "xx"]
      :ERR)))

(OPT/orchestra-instrument)