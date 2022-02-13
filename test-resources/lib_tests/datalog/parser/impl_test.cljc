(ns datalog.parser.impl-test
  (:require #?(:cljs [cljs.test :refer-macros [is are deftest testing]]
               :clj  [clojure.test :refer     [is are deftest testing]])
            [datalog.parser.impl :as dp]
            [datalog.parser.type :as t]
            [datalog.parser.test.util])
  (:import [clojure.lang ExceptionInfo]))

(deftest bindings
  (are [form res] (= (dp/parse-binding form) res)
    '?x
    (t/->BindScalar (t/->Variable '?x))

    '_
    (t/->BindIgnore)

    '[?x ...]
    (t/->BindColl (t/->BindScalar (t/->Variable '?x)))

    '[?x]
    (t/->BindTuple [(t/->BindScalar (t/->Variable '?x))])

    '[?x ?y]
    (t/->BindTuple [(t/->BindScalar (t/->Variable '?x)) (t/->BindScalar (t/->Variable '?y))])

    '[_ ?y]
    (t/->BindTuple [(t/->BindIgnore) (t/->BindScalar (t/->Variable '?y))])

    '[[_ [?x ...]] ...]
    (t/->BindColl
     (t/->BindTuple [(t/->BindIgnore)
                     (t/->BindColl
                      (t/->BindScalar (t/->Variable '?x)))]))

    '[[?a ?b ?c]]
    (t/->BindColl
     (t/->BindTuple [(t/->BindScalar (t/->Variable '?a))
                     (t/->BindScalar (t/->Variable '?b))
                     (t/->BindScalar (t/->Variable '?c))])))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse binding"
        (dp/parse-binding :key))))

(deftest in
  (are [form res] (= (dp/parse-in form) res)
    '[?x]
    [(t/->BindScalar (t/->Variable '?x))]

    '[$ $1 % _ ?x]
    [(t/->BindScalar (t/->SrcVar '$))
     (t/->BindScalar (t/->SrcVar '$1))
     (t/->BindScalar (t/->RulesVar))
     (t/->BindIgnore)
     (t/->BindScalar (t/->Variable '?x))]

    '[$ [[_ [?x ...]] ...]]
    [(t/->BindScalar (t/->SrcVar '$))
     (t/->BindColl
      (t/->BindTuple [(t/->BindIgnore)
                      (t/->BindColl
                       (t/->BindScalar (t/->Variable '?x)))]))])

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse binding"
        (dp/parse-in ['?x :key]))))

(deftest with
  (is (= (dp/parse-with '[?x ?y])
         [(t/->Variable '?x) (t/->Variable '?y)]))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse :with clause"
        (dp/parse-with '[?x _]))))

(deftest test-parse-find
  (is (= (dp/parse-find '[?a ?b])
         (t/->FindRel [(t/->Variable '?a) (t/->Variable '?b)])))
  (is (= (dp/parse-find '[[?a ...]])
         (t/->FindColl (t/->Variable '?a))))
  (is (= (dp/parse-find '[?a .])
         (t/->FindScalar (t/->Variable '?a))))
  (is (= (dp/parse-find '[[?a ?b]])
         (t/->FindTuple [(t/->Variable '?a) (t/->Variable '?b)]))))

(deftest test-parse-aggregate
  (is (= (dp/parse-find '[?a (count ?b)])
         (t/->FindRel [(t/->Variable '?a) (t/->Aggregate (t/->PlainSymbol 'count) [(t/->Variable '?b)])])))
  (is (= (dp/parse-find '[[(count ?a) ...]])
         (t/->FindColl (t/->Aggregate (t/->PlainSymbol 'count) [(t/->Variable '?a)]))))
  (is (= (dp/parse-find '[(count ?a) .])
         (t/->FindScalar (t/->Aggregate (t/->PlainSymbol 'count) [(t/->Variable '?a)]))))
  (is (= (dp/parse-find '[[(count ?a) ?b]])
         (t/->FindTuple [(t/->Aggregate (t/->PlainSymbol 'count) [(t/->Variable '?a)]) (t/->Variable '?b)]))))

(deftest test-parse-custom-aggregates
  (is (= (dp/parse-find '[(aggregate ?f ?a)])
         (t/->FindRel [(t/->Aggregate (t/->Variable '?f) [(t/->Variable '?a)])])))
  (is (= (dp/parse-find '[?a (aggregate ?f ?b)])
         (t/->FindRel [(t/->Variable '?a) (t/->Aggregate (t/->Variable '?f) [(t/->Variable '?b)])])))
  (is (= (dp/parse-find '[[(aggregate ?f ?a) ...]])
         (t/->FindColl (t/->Aggregate (t/->Variable '?f) [(t/->Variable '?a)]))))
  (is (= (dp/parse-find '[(aggregate ?f ?a) .])
         (t/->FindScalar (t/->Aggregate (t/->Variable '?f) [(t/->Variable '?a)]))))
  (is (= (dp/parse-find '[[(aggregate ?f ?a) ?b]])
         (t/->FindTuple [(t/->Aggregate (t/->Variable '?f) [(t/->Variable '?a)]) (t/->Variable '?b)]))))

(deftest test-parse-find-elements
  (is (= (dp/parse-find '[(count ?b 1 $x) .])
         (t/->FindScalar (t/->Aggregate (t/->PlainSymbol 'count)
                                        [(t/->Variable '?b)
                                         (t/->Constant 1)
                                         (t/->SrcVar '$x)])))))

(deftest clauses
  (are [form res] (= (set (dp/parse-rules form)) res)
    '[[(rule ?x)
       [?x :name _]]]
    #{(t/->Rule
       (t/->PlainSymbol 'rule)
       [(t/->RuleBranch
         (t/->RuleVars nil [(t/->Variable '?x)])
         [(t/->Pattern
           (t/->DefaultSrc)
           [(t/->Variable '?x) (t/->Constant :name) (t/->Placeholder)])])])})
  (is (thrown-with-msg? ExceptionInfo #"Reference to the unknown variable"
        (dp/parse-rules '[[(rule ?x) [?x :name ?y]]]))))

(deftest rule-vars
  (are [form res] (= (set (dp/parse-rules form)) res)
    '[[(rule [?x] ?y)
       [_]]]
    #{(t/->Rule
       (t/->PlainSymbol 'rule)
       [(t/->RuleBranch
         (t/->RuleVars [(t/->Variable '?x)] [(t/->Variable '?y)])
         [(t/->Pattern (t/->DefaultSrc) [(t/->Placeholder)])])])}

    '[[(rule [?x ?y] ?a ?b)
       [_]]]
    #{(t/->Rule
       (t/->PlainSymbol 'rule)

       [(t/->RuleBranch
         (t/->RuleVars [(t/->Variable '?x) (t/->Variable '?y)]
                       [(t/->Variable '?a) (t/->Variable '?b)])
         [(t/->Pattern (t/->DefaultSrc) [(t/->Placeholder)])])])}

    '[[(rule [?x])
       [_]]]
    #{(t/->Rule
       (t/->PlainSymbol 'rule)
       [(t/->RuleBranch
         (t/->RuleVars [(t/->Variable '?x)] nil)
         [(t/->Pattern (t/->DefaultSrc) [(t/->Placeholder)])])])})

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse rule-vars"
        (dp/parse-rules '[[(rule) [_]]])))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse rule-vars"
        (dp/parse-rules '[[(rule []) [_]]])))

  (is (thrown-with-msg? ExceptionInfo #"Rule variables should be distinct"
        (dp/parse-rules '[[(rule ?x ?y ?x) [_]]])))

  (is (thrown-with-msg? ExceptionInfo #"Rule variables should be distinct"
        (dp/parse-rules '[[(rule [?x ?y] ?z ?x) [_]]]))))

(deftest branches
  (are [form res] (= (set (dp/parse-rules form)) res)
    '[[(rule ?x)
       [:a]
       [:b]]
      [(rule ?x)
       [:c]]]
    #{(t/->Rule
       (t/->PlainSymbol 'rule)
       [(t/->RuleBranch
         (t/->RuleVars nil [(t/->Variable '?x)])
         [(t/->Pattern (t/->DefaultSrc) [(t/->Constant :a)])
          (t/->Pattern (t/->DefaultSrc) [(t/->Constant :b)])])
        (t/->RuleBranch
         (t/->RuleVars nil [(t/->Variable '?x)])
         [(t/->Pattern (t/->DefaultSrc) [(t/->Constant :c)])])])}

    '[[(rule ?x)
       [:a]
       [:b]]
      [(other ?x)
       [:c]]]
    #{(t/->Rule
       (t/->PlainSymbol 'rule)
       [(t/->RuleBranch
         (t/->RuleVars nil [(t/->Variable '?x)])
         [(t/->Pattern (t/->DefaultSrc) [(t/->Constant :a)])
          (t/->Pattern (t/->DefaultSrc) [(t/->Constant :b)])])])
      (t/->Rule
       (t/->PlainSymbol 'other)
       [(t/->RuleBranch
         (t/->RuleVars nil [(t/->Variable '?x)])
         [(t/->Pattern (t/->DefaultSrc) [(t/->Constant :c)])])])})

  (is (thrown-with-msg? ExceptionInfo #"Rule branch should have clauses"
        (dp/parse-rules '[[(rule ?x)]])))

  (is (thrown-with-msg? ExceptionInfo #"Arity mismatch"
        (dp/parse-rules '[[(rule ?x) [_]]
                          [(rule ?x ?y) [_]]])))

  (is (thrown-with-msg? ExceptionInfo #"Arity mismatch"
        (dp/parse-rules '[[(rule ?x) [_]]
                          [(rule [?x]) [_]]]))))

(deftest pattern
  (are [clause pattern] (= (dp/parse-clause clause) pattern)
    '[?e ?a ?v]
    (t/->Pattern (t/->DefaultSrc) [(t/->Variable '?e) (t/->Variable '?a) (t/->Variable '?v)])

    '[_ ?a _ _]
    (t/->Pattern (t/->DefaultSrc) [(t/->Placeholder) (t/->Variable '?a) (t/->Placeholder) (t/->Placeholder)])

    '[$x _ ?a _ _]
    (t/->Pattern (t/->SrcVar '$x) [(t/->Placeholder) (t/->Variable '?a) (t/->Placeholder) (t/->Placeholder)])

    '[$x _ :name ?v]
    (t/->Pattern (t/->SrcVar '$x) [(t/->Placeholder) (t/->Constant :name) (t/->Variable '?v)])
    
    '[$x _ sym ?v]
    (t/->Pattern (t/->SrcVar '$x) [(t/->Placeholder) (t/->Constant 'sym) (t/->Variable '?v)])

    '[$x _ $src-sym ?v]
    (t/->Pattern (t/->SrcVar '$x) [(t/->Placeholder) (t/->Constant '$src-sym) (t/->Variable '?v)]))

  (is (thrown-with-msg? ExceptionInfo #"Pattern could not be empty"
        (dp/parse-clause '[]))))

(deftest test-pred
  (are [clause res] (= (dp/parse-clause clause) res)
    '[(pred ?a 1)]
    (t/->Predicate (t/->PlainSymbol 'pred) [(t/->Variable '?a) (t/->Constant 1)])

    '[(pred)]
    (t/->Predicate (t/->PlainSymbol 'pred) [])

    '[(?custom-pred ?a)]
    (t/->Predicate (t/->Variable '?custom-pred) [(t/->Variable '?a)])))

(deftest test-fn
  (are [clause res] (= (dp/parse-clause clause) res)
    '[(fn ?a 1) ?x]
    (t/->Function (t/->PlainSymbol 'fn) [(t/->Variable '?a) (t/->Constant 1)] (t/->BindScalar (t/->Variable '?x)))

    '[(fn) ?x]
    (t/->Function (t/->PlainSymbol 'fn) [] (t/->BindScalar (t/->Variable '?x)))

    '[(?custom-fn) ?x]
    (t/->Function (t/->Variable '?custom-fn) [] (t/->BindScalar (t/->Variable '?x)))

    '[(?custom-fn ?arg) ?x]
    (t/->Function (t/->Variable '?custom-fn) [(t/->Variable '?arg)] (t/->BindScalar (t/->Variable '?x)))))

(deftest rule-expr
  (are [clause res] (= (dp/parse-clause clause) res)
    '(friends ?x ?y)
    (t/->RuleExpr (t/->DefaultSrc) (t/->PlainSymbol 'friends) [(t/->Variable '?x) (t/->Variable '?y)])

    '(friends "Ivan" _)
    (t/->RuleExpr (t/->DefaultSrc) (t/->PlainSymbol 'friends) [(t/->Constant "Ivan") (t/->Placeholder)])

    '($1 friends ?x ?y)
    (t/->RuleExpr (t/->SrcVar '$1) (t/->PlainSymbol 'friends) [(t/->Variable '?x) (t/->Variable '?y)])
    
    '(friends something)
    (t/->RuleExpr (t/->DefaultSrc) (t/->PlainSymbol 'friends) [(t/->Constant 'something)]))

  (is (thrown-with-msg? ExceptionInfo #"rule-expr requires at least one argument"
        (dp/parse-clause '(friends)))))

(deftest not-clause
  (are [clause res] (= (dp/parse-clause clause) res)
    '(not [?e :follows ?x])
    (t/->Not
     (t/->DefaultSrc)
     [(t/->Variable '?e) (t/->Variable '?x)]
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])])

    '(not
      [?e :follows ?x]
      [?x _ ?y])
    (t/->Not
     (t/->DefaultSrc)
     [(t/->Variable '?e) (t/->Variable '?x) (t/->Variable '?y)]
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])
      (t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?x) (t/->Placeholder) (t/->Variable '?y)])])

    '($1 not [?x])
    (t/->Not
     (t/->SrcVar '$1)
     [(t/->Variable '?x)]
     [(t/->Pattern (t/->DefaultSrc) [(t/->Variable '?x)])])

    '(not-join [?e ?y]
               [?e :follows ?x]
               [?x _ ?y])
    (t/->Not
     (t/->DefaultSrc)
     [(t/->Variable '?e) (t/->Variable '?y)]
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])
      (t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?x) (t/->Placeholder) (t/->Variable '?y)])])

    '($1 not-join [?e] [?e :follows ?x])
    (t/->Not
     (t/->SrcVar '$1)
     [(t/->Variable '?e)]
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])]))

  (is (thrown-with-msg? ExceptionInfo #"Join variable not declared inside clauses: \[\?x\]"
        (dp/parse-clause '(not-join [?x] [?y]))))

  (is (thrown-with-msg? ExceptionInfo #"Join variables should not be empty"
        (dp/parse-clause '(not-join [] [?y]))))

  (is (thrown-with-msg? ExceptionInfo #"Join variables should not be empty"
        (dp/parse-clause '(not [_]))))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse 'not-join' clause"
        (dp/parse-clause '(not-join [?x]))))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse 'not' clause"
        (dp/parse-clause '(not))))

  (is (thrown-with-msg? ExceptionInfo #"Join variable not declared inside clauses: \[\?y\]"
        (dp/parse-clause '(not-join [?y]
                                    (not-join [?x]
                                              [?x :follows ?y]))))))

(deftest or-clause
  (are [clause res] (= (dp/parse-clause clause) res)
    '(or [?e :follows ?x])
    (t/->Or
     (t/->DefaultSrc)
     (t/->RuleVars nil [(t/->Variable '?e) (t/->Variable '?x)])
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])])

    '(or
      [?e :follows ?x]
      [?e :friend ?x])
    (t/->Or
     (t/->DefaultSrc)
     (t/->RuleVars nil [(t/->Variable '?e) (t/->Variable '?x)])
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])
      (t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :friend) (t/->Variable '?x)])])

    '(or
      [?e :follows ?x]
      (and
       [?e :friend ?x]
       [?x :friend ?e]))
    (t/->Or
     (t/->DefaultSrc)
     (t/->RuleVars nil [(t/->Variable '?e) (t/->Variable '?x)])
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])
      (t/->And
       [(t/->Pattern
         (t/->DefaultSrc)
         [(t/->Variable '?e) (t/->Constant :friend) (t/->Variable '?x)])
        (t/->Pattern
         (t/->DefaultSrc)
         [(t/->Variable '?x) (t/->Constant :friend) (t/->Variable '?e)])])])

    '($1 or [?x])
    (t/->Or
     (t/->SrcVar '$1)
     (t/->RuleVars nil [(t/->Variable '?x)])
     [(t/->Pattern (t/->DefaultSrc) [(t/->Variable '?x)])])

    '(or-join [?e]
              [?e :follows ?x]
              [?e :friend ?y])
    (t/->Or
     (t/->DefaultSrc)
     (t/->RuleVars nil [(t/->Variable '?e)])
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])
      (t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :friend) (t/->Variable '?y)])])

    '(or-join [[?e]]
              (and [?e :follows ?x]
                   [?e :friend ?y]))
    (t/->Or
     (t/->DefaultSrc)
     (t/->RuleVars [(t/->Variable '?e)] nil)
     [(t/->And
       [(t/->Pattern
         (t/->DefaultSrc)
         [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])
        (t/->Pattern
         (t/->DefaultSrc)
         [(t/->Variable '?e) (t/->Constant :friend) (t/->Variable '?y)])])])

    '($1 or-join [[?e] ?x]
         [?e :follows ?x])
    (t/->Or
     (t/->SrcVar '$1)
     (t/->RuleVars [(t/->Variable '?e)] [(t/->Variable '?x)])
     [(t/->Pattern
       (t/->DefaultSrc)
       [(t/->Variable '?e) (t/->Constant :follows) (t/->Variable '?x)])]))

  ;; These tests reflect the or-join semantics of Datomic Datalog, https://docs.datomic.com/on-prem/query.html
  ;; TODO use record constructors instead of wordy literals as for rest in this buffer
  (is (= (dp/parse-clause '(or-join [?x] [?y]))
         '#datalog.parser.type.Or{:source #datalog.parser.type.DefaultSrc{},
                                  :rule-vars #datalog.parser.type.RuleVars{:required nil,
                                                                           :free [#datalog.parser.type.Variable{:symbol ?x}]},
                                  :clauses [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{},
                                                                         :pattern [#datalog.parser.type.Variable{:symbol ?y}]}]}))
  (is (= (dp/parse-clause '(or-join [?x ?y] [?x ?y] [?y]))
         '#datalog.parser.type.Or{:source #datalog.parser.type.DefaultSrc{},
                                  :rule-vars #datalog.parser.type.RuleVars{:required nil,
                                                                           :free [#datalog.parser.type.Variable{:symbol ?x}
                                                                                  #datalog.parser.type.Variable{:symbol ?y}]},
                                  :clauses [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{},
                                                                         :pattern [#datalog.parser.type.Variable{:symbol ?x}
                                                                                   #datalog.parser.type.Variable{:symbol ?y}]}
                                            #datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{},
                                                                         :pattern [#datalog.parser.type.Variable{:symbol ?y}]}]}))

  (is (= (dp/parse-clause '(or-join [?y]
                                    (or-join [?x]
                                             [?x :follows ?y])))
         '#datalog.parser.type.Or{:source #datalog.parser.type.DefaultSrc{},
                                  :rule-vars #datalog.parser.type.RuleVars{:required nil,
                                                                           :free [#datalog.parser.type.Variable{:symbol ?y}]},
                                  :clauses [#datalog.parser.type.Or{:source #datalog.parser.type.DefaultSrc{},
                                                                    :rule-vars #datalog.parser.type.RuleVars{:required nil,
                                                                                                             :free [#datalog.parser.type.Variable{:symbol ?x}]},
                                                                    :clauses [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{},
                                                                                                           :pattern [#datalog.parser.type.Variable{:symbol ?x}
                                                                                                                                                  #datalog.parser.type.Constant{:value :follows} #datalog.parser.type.Variable{:symbol ?y}]}]}]}))


  (is (thrown-with-msg? ExceptionInfo #"Join variable not declared inside clauses: \[\?y\]"
        (dp/parse-clause '(or [?x] [?x ?y]))))

  (is (thrown-with-msg? ExceptionInfo #"Join variable not declared inside clauses: \[\?y\]"
        (dp/parse-clause '(or [?x] [?y]))))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse rule-vars"
        (dp/parse-clause '(or-join [] [?y]))))

  (is (thrown-with-msg? ExceptionInfo #"Join variables should not be empty"
        (dp/parse-clause '(or [_]))))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse 'or-join' clause"
        (dp/parse-clause '(or-join [?x]))))

  (is (thrown-with-msg? ExceptionInfo #"Cannot parse 'or' clause"
        (dp/parse-clause '(or)))))


(deftest test-parse-return-maps
  (testing "failed parsing"
    (is (thrown-with-msg? ExceptionInfo #"Only one of these three options is allowed: :keys :strs :syms"
          (dp/parse-return-maps {:keys '("keys" "strs" "syms") :syms '("keys" "strs" "syms")}))))
  (testing "parsing correct options"
    (is (= #datalog.parser.type.ReturnMaps{:mapping-type :keys, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key "keys"} #datalog.parser.type.MappingKey{:mapping-key "strs"} #datalog.parser.type.MappingKey{:mapping-key "syms"})}
           (dp/parse-return-maps {:keys '("keys" "strs" "syms")})))
    (is (= #datalog.parser.type.ReturnMaps{:mapping-type :strs, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key "keys"} #datalog.parser.type.MappingKey{:mapping-key "strs"} #datalog.parser.type.MappingKey{:mapping-key "syms"})}
           (dp/parse-return-maps {:strs '("keys" "strs" "syms")})))
    (is (= #datalog.parser.type.ReturnMaps{:mapping-type :syms, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key "keys"} #datalog.parser.type.MappingKey{:mapping-key "strs"} #datalog.parser.type.MappingKey{:mapping-key "syms"})}
           (dp/parse-return-maps {:syms '("keys" "strs" "syms")})))))
