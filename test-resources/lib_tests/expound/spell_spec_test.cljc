;; copied from
;; https://github.com/bhauman/spell-spec/blob/master/test/spell_spec/expound_test.cljc
;; so I don't break the extension API
(ns expound.spell-spec-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [#?(:clj clojure.spec.alpha
                :cljs cljs.spec.alpha)
             :as s]
            [clojure.string :as string]
            [spell-spec.alpha :as spell :refer [warn-keys strict-keys warn-strict-keys]]
            [expound.alpha :as exp]
            [spell-spec.expound :as sp.ex]))

;; copied from
;; https://github.com/bhauman/spell-spec/blob/48ea2ca544f02b04a73dc42a91aa4876dcc5fc95/src/spell_spec/expound.cljc#L23-L34
;; because test-refresh doesn't refesh libraries if I set explicit paths and
;; if I don't restrict the paths, it tries to reload deps in the CLJS build

(defmethod exp/problem-group-str :spell-spec.alpha/misspelled-key [_type spec-name val path problems opts]
  (sp.ex/exp-formated "Misspelled map key"  _type spec-name val path problems opts))

(defmethod exp/expected-str :spell-spec.alpha/misspelled-key [_type _spec-name _val _path problems _opts]
  (let [{:keys [:spell-spec.alpha/likely-misspelling-of]} (first problems)]
    (str "should probably be" (sp.ex/format-correction-list likely-misspelling-of))))

(defmethod exp/problem-group-str :spell-spec.alpha/unknown-key [_type spec-name val path problems opts]
  (sp.ex/exp-formated "Unknown map key"  _type spec-name val path problems opts))

(defmethod exp/expected-str :spell-spec.alpha/unknown-key [_type _spec-name _val _path problems _opts]
  (str "should be" (sp.ex/format-correction-list (-> problems first :pred))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fetch-warning-output [thunk]
  #?(:clj (binding [*err* (java.io.StringWriter.)]
            (thunk)
            (str *err*))
     :cljs (with-out-str (thunk))))

(deftest check-misspell-test
  (let [spec (spell/keys :opt-un [::hello ::there])
        data {:there 1 :helloo 1 :barabara 1}
        result
        (exp/expound-str spec data)]
    (is (string/includes? result "Misspelled map key"))
    (is (string/includes? result "should probably be"))
    (is (string/includes? result " :hello\n"))))

(deftest check-misspell-with-namespace-test
  (let [spec (spell/keys :opt [::hello ::there])
        data {::there 1 ::helloo 1 :barabara 1}
        result (exp/expound-str spec data)]
    (is (string/includes? result "Misspelled map key"))
    (is (string/includes? result "should probably be"))
    (is (string/includes? result ":expound.spell-spec-test/hello\n"))))

(s/def ::hello integer?)
(s/def ::there integer?)

(deftest other-errors-test
  (let [spec (spell/keys :opt-un [::hello ::there])
        data {:there "1" :helloo 1 :barabara 1}
        result (exp/expound-str spec data)]
    (is (string/includes? result "Misspelled map key"))
    (is (string/includes? result "should probably be"))
    (is (string/includes? result " :hello\n"))

    (is (not (string/includes? result "Spec failed")))
    (is (not (string/includes? result "should satisfy")))
    (is (not (string/includes? result "integer?")))))

(deftest warning-is-valid-test
  (let [spec (warn-keys :opt-un [::hello ::there])
        data {:there 1 :helloo 1 :barabara 1}]
    (testing "expound prints warning to *err*"
      (is (= (fetch-warning-output #(exp/expound-str spec data))
             "SPEC WARNING: possible misspelled map key :helloo should probably be :hello in {:there 1, :helloo 1, :barabara 1}\n")))))

(deftest strict-keys-test
  (let [spec (strict-keys :opt-un [::hello ::there])
        data {:there 1 :barabara 1}
        result (exp/expound-str spec data)]
    (is (string/includes? result "Unknown map key"))
    (is (string/includes? result "should be one of"))
    (is (string/includes? result " :hello, :there\n"))))

(deftest  warn-on-unknown-keys-test
  (let [spec (warn-strict-keys :opt-un [::hello ::there])
        data {:there 1 :barabara 1}]
    (testing "expound prints warning to *err*"
      (is (= (fetch-warning-output #(exp/expound-str spec data))
             "SPEC WARNING: unknown map key :barabara in {:there 1, :barabara 1}\n")))))

(deftest multiple-spelling-matches
  (let [spec (spell/keys :opt-un [::hello1 ::hello2 ::hello3 ::hello4 ::there])
        data {:there 1 :helloo 1 :barabara 1}
        result (exp/expound-str spec data)]
    (is (string/includes? result "Misspelled map key"))
    (is (string/includes? result "should probably be one of"))
    (doseq [k [:hello1 :hello2 :hello3 :hello4]]
      (is (string/includes? result (pr-str k)))))
  (let [spec (spell/keys :opt-un [::hello1 ::hello2 ::hello3 ::there])
        data {:there 1 :helloo 1 :barabara 1}
        result (exp/expound-str spec data)]
    (is (string/includes? result "Misspelled map key"))
    (is (string/includes? result "should probably be one of"))
    (is (not (string/includes? result (pr-str :hello4))))
    (doseq [k [:hello1 :hello2 :hello3]]
      (is (string/includes? result (pr-str k)))))
  (let [spec (spell/keys :opt-un [::hello ::there])
        data {:there 1 :helloo 1 :barabara 1}
        result (exp/expound-str spec data)]
    (is (string/includes? result "Misspelled map key"))
    (is (string/includes? result "should probably be: :hello\n"))))
