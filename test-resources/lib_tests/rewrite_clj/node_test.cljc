(ns rewrite-clj.node-test
  "This test namespace originated from rewrite-cljs."
  (:require [clojure.test :refer [deftest is are testing]]
            [rewrite-clj.node :as n]
            ;; [rewrite-clj.node.protocols :as proto]
            [rewrite-clj.parser :as p]))

(deftest nodes-convert-to-strings-and-sexpr-ability
  (testing "easily parseable"
    (are [?in ?expected-tag #_#_?expected-type ?expected-sexpr-able?]
      (let [n (p/parse-string ?in)]
          (is (= ?in (str n)))
          (is (= ?expected-tag (n/tag n)))
          #_(is (= ?expected-type (proto/node-type n)))
          #_(is (= ?expected-sexpr-able? (n/sexpr-able? n))))
      ","               :comma          ;;:comma              false
      "; comment"       :comment        ;;:comment            false
      "#! comment"      :comment        ;;:comment            false
      "@deref"          :deref          ;;:deref              true
      "#(fn %1)"        :fn             ;;:fn                 true
      ":my-kw"          :token          ;;:keyword            true
      "^:meta b"        :meta           ;;:meta               true
      "#:prefix {:a 1}" :namespaced-map ;;:namespaced-map     true
      "\n"              :newline        ;;:newline            false
      "'quoted"         :quote          ;;:quote              true
      "#booya 32"       :reader-macro   ;;:reader-macro       true
      "#'myvar"         :var            ;;:reader             true
      "#\"regex\""      :regex          ;;:regex              true
      "[1 2 3]"         :vector         ;;:seq                true
      "\"string\""      :token          ;;:string             true
      "symbol"          :token          ;;:symbol             true
      "43"              :token          ;;:token              true
      "#_ nope"         :uneval         ;;:uneval             false
      "  "              :whitespace     ;;:whitespace         false
      )
    )
  (testing "map qualifier"
    (are [?auto-resolved ?prefix ?expected-str]
      (let [n (n/map-qualifier-node ?auto-resolved ?prefix)]
        (is (= ?expected-str (str n)))
        (is (= :map-qualifier (n/tag n)))
        #_(is (= :map-qualifier (proto/node-type n)))
        (is (= true (n/sexpr-able? n))))
      false "prefix"  ":prefix"
      true  nil       "::"
      true  "nsalias" "::nsalias"))
  (testing "integer"
    (let [n (n/integer-node 42)]
      (is (= :token (n/tag n)))
      #_(is (= :int (proto/node-type n)))
      (is (= "42" (str n)))
      (is (n/sexpr-able? n))))
  (testing "forms node"
    (let [n (p/parse-string-all "5 7")]
      (is (= "5 7" (str n)))
      (is (n/sexpr-able? n)))))


(deftest namespaced-keyword
  (is (= ":dill/dall"
         (n/string (n/keyword-node :dill/dall)))))

(deftest funky-keywords
  (is (= ":%dummy.*"
         (n/string (n/keyword-node :%dummy.*)))))

(deftest regex-node
  (let [sample "(re-find #\"(?i)RUN\" s)"
        sample2 "(re-find #\"(?m)^rss\\s+(\\d+)$\")"
        sample3 "(->> (str/split container-name #\"/\"))"]
    (is (= sample (-> sample p/parse-string n/string)))
    (is (= sample2 (-> sample2 p/parse-string n/string)))
    (is (= sample3 (-> sample3 p/parse-string n/string)))))

(deftest regex-with-newlines
  (let [sample "(re-find #\"Hello
        \\nJalla\")"]
    (is (= sample (-> sample p/parse-string n/string)))))

(deftest reader-conditionals
  (testing "Simple reader conditional"
    (let [sample "#?(:clj bar)"
          res (p/parse-string sample)]
      (is (= sample (n/string res)))
      (is (= :reader-macro (n/tag res)))
      (is (= [:token :list] (map n/tag (n/children res))))))

  (testing "Reader conditional with space before list"
    (let [sample "#? (:clj bar)"
          sample2 "#?@ (:clj bar)"]
      (is (= sample (-> sample p/parse-string n/string)))
      (is (= sample2 (-> sample2 p/parse-string n/string)))))


  (testing "Reader conditional with splice"
    (let [sample
"(:require [clojure.string :as s]
           #?@(:clj  [[clj-time.format :as tf]
                      [clj-time.coerce :as tc]]
               :cljs [[cljs-time.coerce :as tc]
                      [cljs-time.format :as tf]]))"
          res (p/parse-string sample)]
      (is (= sample (n/string res))))))

(deftest t-node?
  (is (not (n/node? nil)))
  (is (not (n/node? 42)))
  (is (not (n/node? "just a string")))
  (is (not (n/node? {:a 1})))
  (is (not (n/node? (first {:a 1}))))
  (is (n/node? (n/list-node (list 1 2 3))))
  (is (n/node? (n/string-node "123"))))

(deftest t-sexpr-on-map-qualifiable-nodes
  (let [opts {:auto-resolve (fn [alias]
                              (if (= :current alias)
                                'my.current.ns
                                (get {'my-alias 'my.aliased.ns
                                      'nsmap-alias 'nsmap.aliased.ns}
                                     alias
                                     (symbol (str alias "-unresolved")))))}
        sexpr-default n/sexpr
        sexpr-custom #(n/sexpr % opts)
        map-qualifier (n/map-qualifier-node false "nsmap-prefix")
        map-qualifier-current-ns (n/map-qualifier-node true nil)
        map-qualifier-ns-alias (n/map-qualifier-node true "nsmap-alias")]
    (testing "qualified nodes are unaffected by resolver"
      (are [?result ?node]
          (do
            (is (= ?result (-> ?node sexpr-default)))
            (is (= ?result (-> ?node sexpr-custom))))
        :my-kw            (n/keyword-node :my-kw)
        'my-sym           (n/token-node 'my-sym)
        :_/my-kw          (n/keyword-node :_/my-kw)
        '_/my-sym         (n/token-node '_/my-sym)
        :my-prefix/my-kw  (n/keyword-node :my-prefix/my-kw)
        'my-prefix/my-sym (n/token-node 'my-prefix/my-sym)))
    (testing "auto-resolve qualified key nodes are affected by resolver"
      (are [?result-default ?result-custom ?node]
          (do
            (is (= ?result-default (-> ?node sexpr-default)))
            (is (= ?result-custom (-> ?node sexpr-custom))))
        :?_current-ns_?/my-kw :my.current.ns/my-kw   (n/keyword-node :my-kw true)
        :??_my-alias_??/my-kw :my.aliased.ns/my-kw   (n/keyword-node :my-alias/my-kw true)))
    (testing "map qualified nodes can be affected by resolver"
      (are [?result ?node]
          (do
            (is (= ?result (-> ?node (n/map-context-apply map-qualifier) sexpr-default)))
            (is (= ?result (-> ?node (n/map-context-apply map-qualifier) sexpr-custom))) )
        :nsmap-prefix/my-kw   (n/keyword-node :my-kw)
        'nsmap-prefix/my-sym  (n/token-node 'my-sym)))
    (testing "map qualified auto-resolve current-ns nodes can be affected by resolver"
      (are [?result-default ?result-custom ?node]
          (do
            (is (= ?result-default (-> ?node (n/map-context-apply map-qualifier-current-ns) sexpr-default)))
            (is (= ?result-custom (-> ?node (n/map-context-apply map-qualifier-current-ns) sexpr-custom))))
        :?_current-ns_?/my-kw  :my.current.ns/my-kw  (n/keyword-node :my-kw)
        '?_current-ns_?/my-sym 'my.current.ns/my-sym (n/token-node 'my-sym)))
    (testing "map qualified auto-resolve ns-alias nodes can be affected by resolver"
      (are [?result-default ?result-custom ?node]
          (do
            (is (= ?result-default (-> ?node (n/map-context-apply map-qualifier-ns-alias) sexpr-default)))
            (is (= ?result-custom (-> ?node (n/map-context-apply map-qualifier-ns-alias) sexpr-custom))))
        :??_nsmap-alias_??/my-kw  :nsmap.aliased.ns/my-kw  (n/keyword-node :my-kw)
        '??_nsmap-alias_??/my-sym 'nsmap.aliased.ns/my-sym (n/token-node 'my-sym)))
    (testing "map qualified nodes that are unaffected by resolver"
      (are [?result ?node]
          (do
            (is (= ?result (-> ?node (n/map-context-apply map-qualifier) sexpr-default)))
            (is (= ?result (-> ?node (n/map-context-apply map-qualifier) sexpr-custom)))

            (is (= ?result (-> ?node (n/map-context-apply map-qualifier-current-ns) sexpr-default)))
            (is (= ?result (-> ?node (n/map-context-apply map-qualifier-current-ns) sexpr-custom)))

            (is (= ?result (-> ?node (n/map-context-apply map-qualifier-ns-alias) sexpr-default)))
            (is (= ?result (-> ?node (n/map-context-apply map-qualifier-ns-alias) sexpr-custom)))  )
        :my-kw    (n/keyword-node :_/my-kw)
        'my-sym   (n/token-node '_/my-sym)
        :my-prefix/my-kw  (n/keyword-node :my-prefix/my-kw)
        'my-prefix/my-sym (n/token-node 'my-prefix/my-sym)))
    (testing "when auto-resolver returns nil, bare or already qualified kw is returned"
      (let [opts {:auto-resolve (fn [_alias])}]
        (is (= :my-kw (-> (n/keyword-node :my-kw true) (n/sexpr opts))))
        (is (= :my-kw (-> (n/keyword-node :my-alias/my-kw true) (n/sexpr opts))))
        (is (= :my-kw
               (-> (n/keyword-node :foo/my-kw true)
                   (assoc :map-qualifier {:auto-resolved? true :prefix "nsmap-alias"})
                   (n/sexpr opts))))
        (is (= :foo/my-kw
               (-> (n/keyword-node :foo/my-kw false)
                   (assoc :map-qualifier {:auto-resolved? true :prefix "nsmap-alias"})
                   (n/sexpr opts)))) ))))

(deftest t-sexpr-on-map-qualifier-node
  (testing "with default auto-resolve"
    (let [default-mqn-sexpr (fn [s] (-> s p/parse-string n/children first n/sexpr))]
      (is (= 'prefix (default-mqn-sexpr "#:prefix {:a 1 :b 2}")))
      (is (= '?_current-ns_? (default-mqn-sexpr "#:: {:a 1 :b 2}")))
      (is (= '??_my-ns-alias_?? (default-mqn-sexpr "#::my-ns-alias {:a 1 :b 2}")))))
  (testing "with custom auto-resolve"
    (let [opts {:auto-resolve (fn [alias]
                                (if (= :current alias)
                                  'my.current.ns
                                  (get {'my-alias 'my.aliased.ns
                                        'nsmap-alias 'nsmap.aliased.ns}
                                       alias
                                       (symbol (str alias "-unresolved")))))}
          custom-mqn-sexpr (fn [s] (-> s p/parse-string n/children first (n/sexpr opts)))]
      (is (= 'prefix (custom-mqn-sexpr "#:prefix {:a 1 :b 2}")))
      (is (= 'my.current.ns (custom-mqn-sexpr "#:: {:a 1 :b 2}")))
      (is (= 'my.aliased.ns (custom-mqn-sexpr "#::my-alias {:a 1 :b 2}")))
      (is (= 'my-alias-nope-unresolved (custom-mqn-sexpr "#::my-alias-nope {:a 1 :b 2}"))))))
