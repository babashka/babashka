(ns datalog.parser-test
  (:require #?(:cljs [cljs.test :refer-macros [are deftest]]
               :clj  [clojure.test :refer [are deftest]])
            [datalog.parser :as parser]
            [datalog.parser.test.util]))

(deftest validation
  (are [q result] (= result (parser/parse q))
    '[:find ?e
      :in $ ?fname ?lname
      :keys foo
      :where [?e :user/firstName ?fname]
             [?e :user/lastName ?lname]]
    '#datalog.parser.type.Query{:qfind #datalog.parser.type.FindRel{:elements [#datalog.parser.type.Variable{:symbol ?e}]}, :qwith nil, :qin [#datalog.parser.type.BindScalar{:variable #datalog.parser.type.SrcVar{:symbol $}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?fname}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?lname}}], :qwhere [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/firstName} #datalog.parser.type.Variable{:symbol ?fname}]} #datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/lastName} #datalog.parser.type.Variable{:symbol ?lname}]}], :qlimit nil, :qoffset nil, :qreturnmaps #datalog.parser.type.ReturnMaps{:mapping-type :keys, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key foo})}}

    '[:find ?e
      :in $ ?fname ?lname
      :strs foo
      :where [?e :user/firstName ?fname]
             [?e :user/lastName ?lname]]
    '#datalog.parser.type.Query{:qfind #datalog.parser.type.FindRel{:elements [#datalog.parser.type.Variable{:symbol ?e}]}, :qwith nil, :qin [#datalog.parser.type.BindScalar{:variable #datalog.parser.type.SrcVar{:symbol $}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?fname}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?lname}}], :qwhere [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/firstName} #datalog.parser.type.Variable{:symbol ?fname}]} #datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/lastName} #datalog.parser.type.Variable{:symbol ?lname}]}], :qlimit nil, :qoffset nil, :qreturnmaps #datalog.parser.type.ReturnMaps{:mapping-type :strs, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key foo})}}

    '[:find ?e
      :in $ ?fname ?lname
      :syms foo
      :where [?e :user/firstName ?fname]
             [?e :user/lastName ?lname]]
    '#datalog.parser.type.Query{:qfind #datalog.parser.type.FindRel{:elements [#datalog.parser.type.Variable{:symbol ?e}]}, :qwith nil, :qin [#datalog.parser.type.BindScalar{:variable #datalog.parser.type.SrcVar{:symbol $}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?fname}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?lname}}], :qwhere [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/firstName} #datalog.parser.type.Variable{:symbol ?fname}]} #datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/lastName} #datalog.parser.type.Variable{:symbol ?lname}]}], :qlimit nil, :qoffset nil, :qreturnmaps #datalog.parser.type.ReturnMaps{:mapping-type :syms, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key foo})}}

    '{:find [?e]
      :in [$ ?fname ?lname]
      :keys [foo]
      :where [[?e :user/firstName ?fname]
              [?e :user/lastName ?lname]]}
    '#datalog.parser.type.Query{:qfind #datalog.parser.type.FindRel{:elements [#datalog.parser.type.Variable{:symbol ?e}]}, :qwith nil, :qin [#datalog.parser.type.BindScalar{:variable #datalog.parser.type.SrcVar{:symbol $}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?fname}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?lname}}], :qwhere [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/firstName} #datalog.parser.type.Variable{:symbol ?fname}]} #datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/lastName} #datalog.parser.type.Variable{:symbol ?lname}]}], :qlimit nil, :qoffset nil, :qreturnmaps #datalog.parser.type.ReturnMaps{:mapping-type :keys, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key foo})}}

    '{:find [[?e ?fname]]
      :keys [foo]
      :in [$ ?fname ?lname]
      :where [[?e :user/firstName ?fname]
              [?e :user/lastName ?lname]]}
#datalog.parser.type.Query{:qfind #datalog.parser.type.FindTuple{:elements [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Variable{:symbol ?fname}]}, :qwith nil, :qin [#datalog.parser.type.BindScalar{:variable #datalog.parser.type.SrcVar{:symbol $}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?fname}} #datalog.parser.type.BindScalar{:variable #datalog.parser.type.Variable{:symbol ?lname}}], :qwhere [#datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/firstName} #datalog.parser.type.Variable{:symbol ?fname}]} #datalog.parser.type.Pattern{:source #datalog.parser.type.DefaultSrc{}, :pattern [#datalog.parser.type.Variable{:symbol ?e} #datalog.parser.type.Constant{:value :user/lastName} #datalog.parser.type.Variable{:symbol ?lname}]}], :qlimit nil, :qoffset nil, :qreturnmaps #datalog.parser.type.ReturnMaps{:mapping-type :keys, :mapping-keys (#datalog.parser.type.MappingKey{:mapping-key foo})}}
    ))

(deftest validation-fails
  (are [q msg] (thrown-msg? msg (parser/parse q))
               '[:find ?e :where [?x]]
               "Query for unknown vars: [?e]"

               '[:find ?e :with ?f :where [?e]]
               "Query for unknown vars: [?f]"

               '[:find ?e ?x ?t :in ?x :where [?e]]
               "Query for unknown vars: [?t]"

               '[:find ?x ?e :with ?y ?e :where [?x ?e ?y]]
               ":find and :with should not use same variables: [?e]"

               '[:find ?e :in $ $ ?x :where [?e]]
               "Vars used in :in should be distinct"

               '[:find ?e :in ?x $ ?x :where [?e]]
               "Vars used in :in should be distinct"

               '[:find ?e :in $ % ?x % :where [?e]]
               "Vars used in :in should be distinct"

               '[:find ?n :with ?e ?f ?e :where [?e ?f ?n]]
               "Vars used in :with should be distinct"

               '[:find ?x :where [$1 ?x]]
               "Where uses unknown source vars: [$1]"

               '[:find ?x :in $1 :where [$2 ?x]]
               "Where uses unknown source vars: [$2]"

               '[:find ?e :where (rule ?e)]
               "Missing rules var '%' in :in"

               '[:find ?e :where [?e] :limit [42]]
               "Cannot parse :limit, expected java.lang.Long"

               '[:find ?e :where [?e] :offset [666]]
               "Cannot parse :offset, expected java.lang.Long"

               '[:find ?e :keys foo bar :where [?e] :offset 666]
               "Count of :keys/:strs/:syms must match count of :find"

               '[:find ?e ?f :keys foo :where [?e ?f] :offset 666]
               "Count of :keys/:strs/:syms must match count of :find"

               '[:find [?e ?f] :keys foo bar :where [?e ?f] :offset 666]
               "Count of :keys/:strs/:syms must match count of :find"

               '[:find ?e :strs '(foo bar) :keys '("foo" "bar") :where [?e] :offset 666]
               "Only one of these three options is allowed: :keys :strs :syms"))
