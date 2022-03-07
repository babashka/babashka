(ns cli-matic.core-test
  (:require [clojure.test :refer [is are deftest testing]]
            [cli-matic.platform :as P]
            [cli-matic.platform-macros :refer [try-catch-all]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cli-matic.core :refer [parse-command-line
                                    run-cmd*
                                    ->RV
                                    assert-unique-values
                                    assert-cfg-sanity
                                    parse-cmds-with-defaults]]
            [cli-matic.utils-v2 :as U2]))

(defn cmd_foo [& opts] nil)
(defn cmd_bar [& opts] nil)
(defn cmd_save_opts [& opts]
  ;(prn "Called" opts)
  opts)

(defn cmd_returnstructure [opts]
  {:myopts opts
   :somedata "hiyo"})

(def cli-options
  [;; First three strings describe a short-option, long-option with optional
   ;; example argument description, and a description. All three are optional
   ;; and positional.
   ["-p" "--port PORT" "Port number"
    :default 80
    :parse-fn #(P/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]
   ["-H" "--hostname HOST" "Remote host"
    :default 0
    ;; Specify a string to output in the default column in the options summary
    ;; if the default value's string representation is very ugly
    :default-desc "localhost"
    :parse-fn #(P/parseInt %)]
   ;; If no required argument description is given, the option is assumed to
   ;; be a boolean option defaulting to nil
   [nil "--detach" "Detach from controlling process"]
   ["-v" nil "Verbosity level; may be specified multiple times to increase value"
    ;; If no long-option is specified, an option :id must be given
    :id :verbosity
    :default 0
    ;; Use assoc-fn to create non-idempotent options
    :assoc-fn (fn [m k _] (update-in m [k] inc))]
   ;; A boolean option that can explicitly be set to false
   ["-d" "--[no-]daemon" "Daemonize the process" :default true]
   ["-h" "--help"]])

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

(def SIMPLE-SUBCOMMAND-CFG-v2
  (U2/convert-config-v1->v2 SIMPLE-SUBCOMMAND-CFG))

(deftest simple-subcommand
  (testing "A simple subcommand - v2"

    ;; Normal subcomand
    (is (= (parse-command-line
            ["--bb" "1" "foo" "--cc" "2" "--dd" "3"]
            SIMPLE-SUBCOMMAND-CFG-v2)

           {:commandline    {:bb 1 :cc 2 :dd 3 :_arguments []}
            :subcommand     "dummy foo"
            :subcommand-path     ["dummy" "foo"]
            :parse-errors   :NONE
            :error-text     ""
            :subcommand-def {:command     "foo"
                             :short       "f"
                             :description "I am function foo"
                             :opts        [{:as     "C"
                                            :option "cc"
                                            :type   :int}
                                           {:as     "D"
                                            :option "dd"
                                            :type   :int}]
                             :runs        cmd_foo}}))



    ;; short subcommand


    (is (= (parse-command-line
            ["--bb" "1" "f" "--cc" "2" "--dd" "3"]
            SIMPLE-SUBCOMMAND-CFG-v2)

           {:commandline    {:bb 1 :cc 2 :dd 3 :_arguments []}
            :subcommand     "dummy foo"
            :subcommand-path     ["dummy" "foo"]
            :parse-errors   :NONE
            :error-text     ""
            :subcommand-def {:command     "foo"
                             :short       "f"
                             :description "I am function foo"
                             :opts        [{:as     "C"
                                            :option "cc"
                                            :type   :int}
                                           {:as     "D"
                                            :option "dd"
                                            :type   :int}]
                             :runs        cmd_foo}}))

    ;; unknown subcommand
    (is (= (parse-command-line
            ["--bb" "1" "unknown" "--cc" "2" "--dd" "3"]
            SIMPLE-SUBCOMMAND-CFG-v2)

           {:commandline    {}
            :error-text     "Unknown sub-command: 'dummy unknown'."
            :parse-errors   :ERR-UNKNOWN-SUBCMD
            :subcommand     "dummy unknown"
            :subcommand-path ["dummy" "unknown"]
            :subcommand-def nil}))))

(deftest run-examples
  (testing "Some real-life behavior for our SIMPLE case - v2"
    (are [i o]
         (= (run-cmd* SIMPLE-SUBCOMMAND-CFG-v2 i) o)

      ; no parameters - displays cmd help
      []
      (->RV -1 :ERR-NO-SUBCMD :HELP-GLOBAL ["dummy"] "No sub-command specified.")

      ["x"]
      (->RV -1 :ERR-UNKNOWN-SUBCMD :HELP-GLOBAL ["dummy" "x"] "Unknown sub-command: 'dummy x'.")

      ["--lippa" "foo"]
      (->RV -1 :ERR-PARMS-GLOBAL :HELP-GLOBAL ["dummy"] "Global option error: Unknown option: \"--lippa\"")

      ; help globale
      ["-?"]
      (->RV 0 :OK :HELP-GLOBAL ["dummy"] nil)

      ["--help"]
      (->RV 0 :OK :HELP-GLOBAL ["dummy"] nil)

      ; help sub-commands (incl short version)
      ["foo"  "-?"]
      (->RV 0 :OK :HELP-SUBCMD ["dummy" "foo"] nil)

      ["bar" "--help"]
      (->RV 0 :OK :HELP-SUBCMD ["dummy" "bar"] nil)

      ["f"  "-?"]
      (->RV 0 :OK :HELP-SUBCMD ["dummy" "foo"] nil)

      ["rets"]
      (->RV 0 :OK nil nil nil))))

(def MANDATORY-SUBCOMMAND-CFG
  {:app         {:command     "dummy"
                 :description "I am some command"
                 :version     "0.1.2"}
   :global-opts [{:option "aa" :as "A" :type :int :default :present}
                 {:option "bb" :as "B" :type :int}]
   :commands    [{:command     "foo" :short       "f"
                  :description "I am function foo"
                  :opts        [{:option "cc" :as "C" :type :int :default :present}
                                {:option "dd" :as "D" :type :int}]
                  :runs        cmd_foo}]})

(def MANDATORY-SUBCOMMAND-CFG-v2
  (U2/convert-config-v1->v2 MANDATORY-SUBCOMMAND-CFG))

(deftest check-mandatory-options
  (testing "Some real-life behavior with mandatory options"
    (are [i o]
         (= (run-cmd* MANDATORY-SUBCOMMAND-CFG-v2 i) o)

      ; no parameters - displays cmd help
      []
      (->RV -1 :ERR-NO-SUBCMD :HELP-GLOBAL ["dummy"] "No sub-command specified.")

      ["x"]
      (->RV -1 :ERR-PARMS-GLOBAL :HELP-GLOBAL ["dummy"] "Global option error: Missing option: aa")

      ["--lippa" "foo"]
      (->RV -1 :ERR-PARMS-GLOBAL :HELP-GLOBAL ["dummy"] "Global option error: Unknown option: \"--lippa\"")

      ; help globale
      ["-?"]
      (->RV 0 :OK :HELP-GLOBAL ["dummy"] nil)

      ; help sub-commands (incl short version)
      ["--aa" "1" "foo" "-?"]
      (->RV 0 :OK :HELP-SUBCMD ["dummy" "foo"] nil)

      ; error no global cmd
      ["foo" "--cc" "1"]
      (->RV -1 :ERR-PARMS-GLOBAL :HELP-GLOBAL ["dummy"] "Global option error: Missing option: aa")

      ; error no sub cmd
      ["--aa" "1" "foo" "--dd" "1"]
      (->RV -1 :ERR-PARMS-SUBCMD :HELP-SUBCMD ["dummy" "foo"] "Option error: Missing option: cc")

      ; works
      ["--aa" "1" "foo" "--cc" "1"]
      (->RV 0 :OK nil nil nil))))


; Problems
; --------
;
; Types
;
; lein run -m cli-matic.toycalc -- add --a x
; ** ERROR: **
; Error:
; and nothing else


;; VALIDATION OF CONFIGURATION
;;


(deftest check-unique-options
  (testing "Unique options"
    (are [i o]
         (= (try-catch-all
             (apply assert-unique-values i)
             (fn [_] :ERR))
            o)

      ; empty
      ["a" [] :x]
      nil

      ; ok
      ["pippo"
       [{:option "a" :as "Parameter A" :type :int :default 0}
        {:option "b" :as "Parameter B" :type :int :default 0}]
       :option]
      nil

      ; dupe
      ["pippo"
       [{:option "a" :as "Parameter A" :type :int :default 0}
        {:option "a" :as "Parameter B" :type :int :default 0}]
       :option]
      :ERR)))

(comment
  ;;; TO DO

  (deftest check-cfg-format
    (testing "Cfg format"
      (are [i o]
           (= o
              (try-catch-all
               (-> i
                   U2/add-setup-defaults-v1
                   assert-cfg-sanity)
               (fn [_]
               ;(prn e)
                 :ERR)))

        ;; OK
        {:app         {:command "toycalc" :description "A" :version "0.0.1"}

         :global-opts [{:option "base" :as "T" :type :int :default 10}]

         :commands    [{:command "add" :description "Adds" :runs identity
                        :opts    [{:option "a" :as "Addendum 1" :type :int}
                                  {:option "b" :as "Addendum 2" :type :int :default 0}]}]}
        nil

        ;; No global options - still OK (bug #35)
        {:app      {:command "toycalc" :description "A" :version "0.0.1"}
         :commands [{:command "add" :description "Adds" :runs identity
                     :opts    [{:option "a" :as "Addendum 1" :type :int}
                               {:option "b" :as "Addendum 2" :type :int :default 0}]}]}
        nil

        ;; double in global
        {:app         {:command "toycalc" :description "A" :version "0.0.1"}

         :global-opts [{:option "base" :as "T" :type :int :default 10}
                       {:option "base" :as "X" :type :int :default 10}]

         :commands    [{:command "add" :description "Adds" :runs identity
                        :opts    [{:option "a" :as "Addendum 1" :type :int}
                                  {:option "b" :as "Addendum 2" :type :int :default 0}]}]}
        :ERR

        ;; double in specific
        {:app         {:command "toycalc" :description "A" :version "0.0.1"}

         :global-opts [{:option "base" :as "T" :type :int :default 10}]

         :commands    [{:command "add" :description "Adds" :runs identity
                        :opts    [{:option "a" :short "q" :as "Addendum 1" :type :int}
                                  {:option "b" :short "q" :as "Addendum 2" :type :int :default 0}]}]}
        :ERR

        ;; positional subcmds in global opts
        {:app         {:command "toycalc" :description "A" :version "0.0.1"}

         :global-opts [{:option "base" :short 0 :as "T" :type :int :default 10}]

         :commands    [{:command "add" :description "Adds" :runs identity
                        :opts    [{:option "a" :short "q" :as "Addendum 1" :type :int}
                                  {:option "b" :short "d" :as "Addendum 2" :type :int :default 0}]}]}
        :ERR)))
  ;;;;
  )

(def POSITIONAL-SUBCOMMAND-CFG
  {:app         {:command     "dummy"
                 :description "I am some command"
                 :version     "0.1.2"}
   :global-opts [{:option "aa" :as "A" :type :int :default :present}
                 {:option "bb" :as "B" :type :int}]
   :commands    [{:command     "foo" :short       "f"
                  :description "I am function foo"
                  :opts        [{:option "cc" :short 0 :as "C" :type :int :default :present}
                                {:option "dd" :as "D" :type :int}
                                {:option "ee"  :short 1 :as "E" :type :int}]
                  :runs        cmd_save_opts}]})

(def POSITIONAL-SUBCOMMAND-CFG-v2
  (U2/convert-config-v1->v2 POSITIONAL-SUBCOMMAND-CFG))

(deftest check-positional-options
  (testing "Some real-life behavior with mandatory options"
    (are [i o]
         (= (select-keys
             (parse-command-line i POSITIONAL-SUBCOMMAND-CFG-v2)
             [:commandline :error-text]) o)

        ;; a simple case
      ["--aa" "10" "foo" "1" "2"]
      {:commandline {:_arguments ["1" "2"]
                     :aa         10
                     :cc         1
                     :ee         2}
       :error-text  ""}

        ;; positional arg does not exist but is default present
      ["--aa" "10" "foo"]
      {:commandline {}
       :error-text  "Missing option: cc"}

        ;; positional arg does not exist and it is not default present
      ["--aa" "10" "foo" "1"]
      {:commandline {:_arguments ["1"]
                     :aa         10
                     :cc         1}
       :error-text  ""})))

(defn env-helper [s]
  (get {"VARA" "10"
        "VARB" "HELLO"} s))

(deftest check-environmental-vars
  (testing "Parsing with env - global opts"
    (are [opts cmdline result]
         (= (dissoc (parse-cmds-with-defaults opts cmdline true env-helper) :summary) result)

      ;; a simple case - no env vars
      [{:option "cc" :short 0 :as "C" :type :int :default :present}
       {:option "dd" :as "D" :type :string}]

      ["--cc" "0" "pippo" "pluto"]

      {:arguments ["pippo" "pluto"]
       :errors    nil
       :options   {:cc 0}}

      ;; a simple case - absent, with env set, integer
      [{:option "cc" :short 0 :as "C" :type :int :default :present}
       {:option "dd" :as "D" :type :int :env "VARA"}]

      ["--cc" "0" "pippo" "pluto"]

      {:arguments ["pippo" "pluto"]
       :errors    nil
       :options   {:cc 0 :dd 10}}

      ;; present, with env set, integer
      [{:option "cc" :short 0 :as "C" :type :int :default :present}
       {:option "dd" :as "D" :type :int :env "VARA"}]

      ["--cc" "0" "--dd" "23" "pippo" "pluto"]

      {:arguments ["pippo" "pluto"]
       :errors    nil
       :options   {:cc 0 :dd 23}}

      ;; absent, with env missing, integer
      [{:option "cc" :short 0 :as "C" :type :int :default :present}
       {:option "dd" :as "D" :type :int :env "NO-VARA"}]

      ["--cc" "0"  "pippo" "pluto"]

      {:arguments ["pippo" "pluto"]
       :errors    nil
       :options   {:cc 0}})))

; =======================================================================
; ========                    S P E C S                        ==========
; =======================================================================

; We add a stupid spec check
; Specs are checked after parsing, both on parameters and globally.
; if presents, specs are checked

(s/def ::ODD-NUMBER odd?)

(s/def ::GENERAL-SPEC-FOO #(= 99 (:ee %)))

(def SPEC-CFG
  {:app         {:command     "dummy"
                 :description "I am some command"
                 :version     "0.1.2"}
   :global-opts [{:option "aa" :as "A" :type :int :default :present :spec ::ODD-NUMBER}
                 {:option "bb" :as "B" :type :int :spec ::ODD-NUMBER}]
   :commands    [{:command     "foo"
                  :short       "f"
                  :description "I am function foo"
                  :opts        [{:option "cc" :short 0 :as "C" :type :int :default :present}
                                {:option "dd" :as "D" :type :int :spec ::ODD-NUMBER}
                                {:option "ee"  :short 1 :as "E" :type :int :spec ::ODD-NUMBER}]
                  :spec        ::GENERAL-SPEC-FOO
                  :runs        cmd_save_opts}]})
(def SPEC-CFG-v2
  (U2/convert-config-v1->v2 SPEC-CFG))

(defn keep-1st-line-stderr
  "To avoid issues with expound changing messages, we remove all
  but the first line in stderr for testing."
  [{:keys [stderr] :as all}]

  (let [nv (if (and (vector? stderr) (pos? (count stderr)))
             (let [lines (str/split-lines (first stderr))
                   vec-of-fline [(first lines)]]

               vec-of-fline) stderr)]

    (assoc all
           :stderr nv)))

;; ------------


(deftest check-specs
  (are [i o]
       (= (keep-1st-line-stderr (run-cmd* SPEC-CFG-v2 i)) o)

    ; all of the should pass
    ["--aa" "3" "--bb" "7" "foo" "--cc" "2" "--dd" "3" "--ee" "99"]
    (->RV 0 :OK nil nil [])

    ; aa (global) not odd
    ["--aa" "2" "--bb" "7" "foo" "--cc" "2" "--dd" "3" "--ee" "99"]
    (->RV -1 :ERR-PARMS-GLOBAL :HELP-GLOBAL ["dummy"] ["Global option error: Spec failure for global option 'aa'"])

    ; bb does not exist but it's not mandatory
    ["--aa" "3" "foo" "--cc" "2" "--dd" "3" "--ee" "99"]
    (->RV 0 :OK nil nil nil)

    ; dd (local)
    ["--aa" "3" "--bb" "7" "foo" "--cc" "2" "--dd" "4" "--ee" "99"]
    (->RV -1 :ERR-PARMS-SUBCMD :HELP-SUBCMD ["dummy" "foo"] ["Option error: Spec failure for option 'dd'"])

    ; dd is missing - spec not checked - bug #105
    ["--aa" "3" "--bb" "7" "foo" "--cc" "2"  "--ee" "99"]
    (->RV 0 :OK nil nil nil)

    ; ee non 99 (validazione globale subcmd)
    ["--aa" "3" "--bb" "7" "foo" "--cc" "2" "--dd" "5" "--ee" "97"]
    (->RV -1 :ERR-PARMS-SUBCMD :HELP-SUBCMD ["dummy" "foo"] ["Option error: Spec failure for subcommand 'dummy foo'"])))

  ;;;;;


; =================================================================
;
; =================================================================


(def SETS-CFG
  {:app         {:command     "dummy"
                 :description "I am some command"
                 :version     "0.1.2"}
   :global-opts []
   :commands    [{:command     "foo" :short       "f"
                  :description "I am function foo"
                  :opts        [{:option "kw" :as "blabla" :type #{:a :b :zebrafuffa}}]
                  :runs        cmd_save_opts}]})

(def SETS-CFG-v2
  (U2/convert-config-v1->v2 SETS-CFG))

(deftest check-sets
  (are [i o]
       (= (run-cmd* SETS-CFG-v2 i) o)

      ; all of the should pass
    ["foo" "--kw" "a"]
    (->RV 0 :OK nil nil [])

    ["foo" "--kw" "B"]
    (->RV 0 :OK nil nil [])

    ["foo" "--kw" "zebrafufa"]
    {:help   :HELP-SUBCMD
     :retval -1
     :status :ERR-PARMS-SUBCMD
     :stderr ["Option error: Error while parsing option \"--kw zebrafufa\": clojure.lang.ExceptionInfo: Value 'zebrafufa' not allowed. Did you mean ':zebrafuffa'? {}"]
     :subcmd ["dummy" "foo"]}))


; =================================================================
;
; =================================================================


(def FLAGS-CFG
  {:app         {:command     "dummy"
                 :description "I am some command"
                 :version     "0.1.2"}
   :global-opts []
   :commands    [{:command     "foo" :short       "f"
                  :description "I am function foo"
                  :opts        [{:option "bar" :as "bar" :type :with-flag :default false}
                                {:option "flag" :as "flag" :type :flag :default false}]
                  :runs        cmd_save_opts}]})

(def FLAGS-CFG-v2
  (U2/convert-config-v1->v2 FLAGS-CFG))

(deftest check-flags
  (are [input expected]
       (= expected (run-cmd* FLAGS-CFG-v2 input))

    ["foo" "--bar"]
    (->RV 0 :OK nil nil [])

    ["foo" "--no-bar"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "Y"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "Yes"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "On"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "T"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "True"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "1"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "N"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "No"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "Off"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "F"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "False"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "false"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "0"]
    (->RV 0 :OK nil nil [])

    ["foo" "--flag" "2"]
    (->RV -1 :ERR-PARMS-SUBCMD :HELP-SUBCMD ["dummy" "foo"]
          "Option error: Error while parsing option \"--flag 2\": clojure.lang.ExceptionInfo: Unsupported flag value {:flag \"2\"}")

    ["foo" "--bar" "--flag" "Y"]
    (->RV 0 :OK nil nil [])

    ["foo" "--no-bar" "--flag" "0"]
    (->RV 0 :OK nil nil [])))

(deftest check-flags-more-complex
  (are [input expected]
       (= expected (parse-command-line input FLAGS-CFG-v2))

    ["foo" "--bar"]
    {:commandline    {:_arguments [] :bar true :flag false}
     :error-text     ""
     :parse-errors   :NONE
     :subcommand      "dummy foo"
     :subcommand-path     ["dummy" "foo"]
     :subcommand-def {:command     "foo"
                      :description "I am function foo"
                      :opts        [{:as      "bar"
                                     :default false
                                     :option  "bar"
                                     :type    :with-flag}
                                    {:as      "flag"
                                     :default false
                                     :option  "flag"
                                     :type    :flag}]
                      :runs        cmd_save_opts
                      :short       "f"}}

    ["foo" "--no-bar"]
    {:commandline    {:_arguments [] :bar false :flag false}
     :error-text     ""
     :parse-errors   :NONE
     :subcommand      "dummy foo"
     :subcommand-path     ["dummy" "foo"]
     :subcommand-def {:command     "foo"
                      :description "I am function foo"
                      :opts        [{:as      "bar"
                                     :default false
                                     :option  "bar"
                                     :type    :with-flag}
                                    {:as      "flag"
                                     :default false
                                     :option  "flag"
                                     :type    :flag}]
                      :runs        cmd_save_opts
                      :short       "f"}}

    ["foo" "--no-bar" "--flag" "Y"]
    {:commandline    {:_arguments [] :bar false :flag true}
     :error-text     ""
     :parse-errors   :NONE
     :subcommand      "dummy foo"
     :subcommand-path     ["dummy" "foo"]
     :subcommand-def {:command     "foo"
                      :description "I am function foo"
                      :opts        [{:as      "bar"
                                     :default false
                                     :option  "bar"
                                     :type    :with-flag}
                                    {:as      "flag"
                                     :default false
                                     :option  "flag"
                                     :type    :flag}]
                      :runs        cmd_save_opts
                      :short       "f"}}

    ["foo" "--no-bar" "--flag" "Off"]
    {:commandline    {:_arguments [] :bar false :flag false}
     :error-text     ""
     :parse-errors   :NONE
     :subcommand      "dummy foo"
     :subcommand-path     ["dummy" "foo"]
     :subcommand-def {:command     "foo"
                      :description "I am function foo"
                      :opts        [{:as      "bar"
                                     :default false
                                     :option  "bar"
                                     :type    :with-flag}
                                    {:as      "flag"
                                     :default false
                                     :option  "flag"
                                     :type    :flag}]
                      :runs        cmd_save_opts
                      :short       "f"}}))

(deftest spec-on-options-bug105
  (let [CFG {:command     "toycalc"
             :description "A command-line toy adder"
             :version     "0.0.1"
             :opts        [{:as      "Parameter A"
                            :default 0
                            :option  "a"
                            :type    :int}
                           {:as      "Parameter B"
                            :default 0
                            :option  "b"
                            :type    :int}]
             :runs        cmd_foo}]))



