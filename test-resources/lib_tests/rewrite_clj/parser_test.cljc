(ns ^{:doc "Tests for EDN parser."
      :author "Yannick Scherer"}
 rewrite-clj.parser-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as p])
  #?(:clj (:import [clojure.lang ExceptionInfo]
                   [java.io File])))

(deftest t-parsing-the-first-few-whitespaces
  (doseq [[ws parsed]
          [["   "     "   "]
           ["   \n  " "   "]]]
    (let [n (p/parse-string ws)]
      (is (= :whitespace (node/tag n)))
      (is (= parsed (node/string n))))))

(deftest t-parsing-whitespace-strings
  (doseq [[ws children]
          [["   \n   "     [[:whitespace "   "]
                            [:newline "\n"]
                            [:whitespace "   "]]]
           [" \t \r\n \t " [[:whitespace " \t "]
                            [:newline "\n"]
                            [:whitespace " \t "]]]]]

    (let [n (p/parse-string-all ws)]
      (is (= :forms (node/tag n)))
      (is (= (string/replace ws "\r\n" "\n") (node/string n)))
      (is (= children (map (juxt node/tag node/string) (node/children n)))))))

#?(:clj
   (deftest t-parsing-unicode-whitespace-strings
     (let [ws "\u2028"
           children [[:whitespace "\u2028"]]
           n (p/parse-string-all ws)]
       (is (= :forms (node/tag n)))
       (is (= (string/replace ws "\r\n" "\n") (node/string n)))
       (is (= children (map (juxt node/tag node/string) (node/children n)))))))

(deftest t-parsing-simple-data
  (doseq [[s r]
          [["0"                          0]
           ["0.1"                        0.1]
           ["12e10"                      1.2e11]
           ["2r1100"                     12]
           ["1N"                         1N]
           [":key"                       :key]
           ["\\\\"                       \\]
           ["\\a"                        \a]
           ["\\space"                    \space]
           ["\\u2202"                    \u2202]
           ["\\'"                        \']
           [":1.5"                       :1.5]
           [":1.5.0"                     :1.5.0]
           [":ns/key"                    :ns/key]
           [":key:key"                   :key:key]
           [":x'"                        :x']
           ["sym"                        'sym]
           ["sym#"                       'sym#]
           ["sym'"                       'sym']
           ["sym'sym"                    'sym'sym]
           ["sym:sym"                    'sym:sym]
           ["\"string\""                 "string"]
           ["b//"                        'b//]]]
    (let [n (p/parse-string s)]
      (is (= :token (node/tag n)))
      (is (= s (node/string n)))
      (is (= r (node/sexpr n))))))

(deftest t-parsing-clojure-1-12-array-class-tokens
  (doseq [dimension (range 1 10)]
    (let [s (str "foobar/" dimension)
          n (p/parse-string s)]
      (is (= :token (node/tag n)))
      (is (= s (node/string n)))
      (is (= (symbol "foobar" (str dimension))
             (node/sexpr n))))))

(deftest t-parsing-garden-selectors
  ;; https://github.com/noprompt/garden
  (doseq [[s expected-r]
          [[":&:hover"    :&:hover]
          ;; clj clojure reader can't parse :&::before but we can create a keyword for it
           [":&::before"  (keyword "&::before")]]]
    (let [n (p/parse-string s)
          r (node/sexpr n)]
      (is (= s (node/string n)))
      (is (= :token (node/tag n)))
      (is (keyword? r))
      (is (= expected-r r)))))

(deftest t-ratios
  (let [s "3/4"
        r #?(:clj 3/4
             ;; no ratios in cljs; they are evaluated on sexpr
             :cljs 0.75)
        n (p/parse-string s)]
    (is (= :token (node/tag n)))
    (is (= s (node/string n)))
    (is (= r (node/sexpr n)))))

(deftest t-big-integers
  (let [s "1234567890123456789012345678901234567890"
        r 1234567890123456789012345678901234567890N
        n (p/parse-string s)]
    (is (= :token (node/tag n)))
    (is (= s (node/string n)))
    (is (= r (node/sexpr n)))))

(deftest t-parsing-symbolic-inf-values
  (doseq [[s r]
          [["##Inf" #?(:cljs js/Number.POSITIVE_INFINITY
                       :default Double/POSITIVE_INFINITY)]
           ["##-Inf" #?(:cljs js/Number.NEGATIVE_INFINITY
                        :default Double/NEGATIVE_INFINITY)]]]
    (let [n (p/parse-string s)]
      (is (= :token (node/tag n)))
      (is (= s (node/string n)))
      (is (= r (node/sexpr n))))))

(deftest t-parsing-symbolic-NaN-value
  (let [n (p/parse-string "##NaN")
        e (node/sexpr n)]
    (is (= :token (node/tag n)))
    (is (= "##NaN" (node/string n)))
    #?(:cljs (is (js/Number.isNaN e))
       :default (is (Double/isNaN e)))))

(deftest t-parsing-reader-prefixed-data
  (doseq [[ s         t                 ws            sexpr        ltag   lcld]
          [["@sym"    :deref            []            '@sym        :token 'sym]
           ["@  sym"  :deref            [:whitespace] '@sym        :token 'sym]
           ["'sym"    :quote            []            ''sym        :token 'sym]
           ["'  sym"  :quote            [:whitespace] ''sym        :token 'sym]
           ["`sym"    :syntax-quote     []            ''sym        :token 'sym]
           ["`  sym"  :syntax-quote     [:whitespace] ''sym        :token 'sym]
           ["~sym"    :unquote          []            '~sym        :token 'sym]
           ["~  sym"  :unquote          [:whitespace] '~sym        :token 'sym]
           ["~@sym"   :unquote-splicing []            '~@sym       :token 'sym]
           ["~@  sym" :unquote-splicing [:whitespace] '~@sym       :token 'sym]
           ["~ @sym"  :unquote          [:whitespace] '~ @sym      :deref '@sym]
           ["#=sym"   :eval             []            '(eval 'sym) :token 'sym]
           ["#=  sym" :eval             [:whitespace] '(eval 'sym) :token 'sym]
           ["#'sym"   :var              []            '#'sym       :token 'sym]
           ["#'\nsym" :var              [:newline]    '#'sym       :token 'sym]]]
    (testing (pr-str s)
      (let [n (p/parse-string s)
            children (node/children n)
            c (map node/tag children)]
        (is (= t (node/tag n)) "tag")
        (is (= ltag (last c)) "ltag")
        (is (= sexpr (node/sexpr n)) "sexpr")
        (is (= s (node/string n)) "string")
        ;; ` and #= return different sexpr's than via clojure.core/read-string
        (when-not (#{:syntax-quote :eval} t)
          (is (= sexpr
                 #?(:cljs (rdr/read-string s)
                    ;; BB_TEST_PATCH
                    :default (binding [#_#_rdr/*read-eval* false] (rdr/read-string s)))
                 #?@(:cljs []
                     :default [(binding [*read-eval* false] (read-string s))]))
              "read-string"))
        (is (= lcld (node/sexpr (last children))) "lcld")
        (is (= ws (vec (butlast c))) "ws")))))

(deftest t-eval
  (let [n (p/parse-string "#=(+ 1 2)")]
    (is (= :eval (node/tag n)))
    (is (= "#=(+ 1 2)" (node/string n)))
    (is (= '(eval '(+ 1 2)) (node/sexpr n)))))

(deftest t-uneval
  (let [s "#' #_    (+ 1 2) sym"
        n (p/parse-string s)
        [ws0 uneval ws1 sym] (node/children n)]
    (is (= :var (node/tag n)))
    (is (= s (node/string n)))
    (is (= :whitespace (node/tag ws0)))
    (is (= :whitespace (node/tag ws1)))
    (is (= :token (node/tag sym)))
    (is (= 'sym (node/sexpr sym)))
    (is (= :uneval (node/tag uneval)))
    (is (= "#_    (+ 1 2)" (node/string uneval)))
    (is (node/printable-only? uneval))
    (is (thrown-with-msg? ExceptionInfo #"unsupported operation" (node/sexpr uneval)))))

(deftest t-parsing-regular-expressions
  (doseq [[s expected-sexpr]
          [["#\"regex\""       '(re-pattern "regex")]
           ["#\"regex\\.\""    '(re-pattern "regex\\.")]
           ["#\"[reg|k].x\""   '(re-pattern "[reg|k].x")]
           ["#\"a\\nb\""       '(re-pattern  "a\\nb")]
           ["#\"a\nb\""        '(re-pattern  "a\nb")]

           ["#\"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\""
            '(re-pattern "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")]]]
       (let [n (p/parse-string s)]
         (is (= :regex (node/tag n)))
         (is (= (count s) (node/length n)))
         (is (= expected-sexpr (node/sexpr n))))))

(deftest t-parsing-strings
  (doseq [[s tag sexpr]
          [["\"123\""       :token       "123"]
           ["\"123\\n456\"" :token       "123\n456"]
           ["\"123\n456\""  :multi-line  "123\n456"]]]
       (let [n (p/parse-string s)]
         (is (= tag (node/tag n)))
         (is (= s (node/string n)))
         (is (= sexpr (node/sexpr n))))))

(deftest t-parsing-seqs
  (doseq [[s t w c]
          [["(1 2 3)"          :list        2  3]
           ["()"               :list        0  0]
           ["( )"              :list        1  0]
           ["() "              :list        0  0]
           ["[1 2 3]"          :vector      2  3]
           ["[]"               :vector      0  0]
           ["[ ]"              :vector      1  0]
           ["[] "              :vector      0  0]
           ["#{1 2 3}"         :set         2  3]
           ["#{}"              :set         0  0]
           ["#{ }"             :set         1  0]
           ["#{} "             :set         0  0]
           ["{:a 0 :b 1}"      :map         3  4]
           ["{}"               :map         0  0]
           ["{ }"              :map         1  0]
           ["{} "              :map         0  0]]]
       (let [n (p/parse-string s)
             children (node/children n)
             fq (frequencies (map node/tag children))]
         (is (= t (node/tag n)))
         (is (= (string/trim s) (node/string n)))
         (is (= (node/sexpr n) #?(:cljs (rdr/read-string s)
                                  ;; BB_TEST_PATCH
                                  :default (binding [#_#_rdr/*read-eval* false] (rdr/read-string s)))))
         (is (= w (:whitespace fq 0)))
         (is (= c (:token fq 0))))))

(deftest t-parsing-invalid-maps
  ;; I don't know if this ability is intentional, but libraries
  ;; have come to rely on the behavior of parsing invalid maps.
  ;; Note: sexpr won't be possible on invalid Clojure
  (doseq [[s t]
          [["{:a}"      :map]
           ["{:r 1 :u}" :map]]]
       (let [n (p/parse-string s)]
         (is (= t (node/tag n)))
         (is (= s (node/string n))))))

(deftest t-parsing-metadata
  (doseq [[meta-str expected-tag expected-meta-child-tag]
          [["^:private"          :meta  :token]
           ["^{:private true}"   :meta  :map]
           ["#^:private"         :meta* :token]
           ["#^{:private true}"  :meta* :map]]
          :let [s (str meta-str " s")
                n (p/parse-string s)
                [meta-data ws target-sym] (node/children n)]]
    (is (= expected-tag (node/tag n)))
    (is (= s (node/string n)))
    (is (= 's (node/sexpr n)))
    (is (= {:private true} (meta (node/sexpr n))))
    (is (= expected-meta-child-tag (node/tag meta-data)))
    (is (= :whitespace (node/tag ws)))
    (is (= :token (node/tag target-sym)))
    (is (= 's (node/sexpr target-sym)))))

(deftest t-parsing-multiple-metadata-forms
  (doseq [[meta-str expected-meta-tag expected-tag-on-metadata]
          [["^:private ^:awe"                 :meta  :token]
           ["^{:private true} ^{:awe true}"   :meta  :map]
           ["#^:private #^:awe"               :meta* :token]
           ["#^{:private true} #^{:awe true}" :meta* :map]]
          :let [s (str meta-str " s")
                n (p/parse-string s)
                [meta-data ws inner-node] (node/children n)
                [inner-meta-data inner-ws target-sym] (node/children inner-node)]]
    ;; outer metadata
    (is (= expected-meta-tag (node/tag n)))
    (is (= {:private true :awe true} (meta (node/sexpr n))))
    (is (= expected-tag-on-metadata (node/tag meta-data)))
    (is (= :whitespace (node/tag ws)))

    ;; inner metadata
    (is (= expected-meta-tag (node/tag inner-node)))
    (is (= {:awe true} (meta (node/sexpr inner-node))))
    (is (= expected-tag-on-metadata (node/tag inner-meta-data)))
    (is (= :whitespace (node/tag inner-ws)))

    ;; target symbol
    (is (= s (node/string n)))
    (is (= 's (node/sexpr target-sym)))))

(deftest t-parsing-tag-symbol-metadata
  (doseq [[s expected-node]
          [["^MyType foo"         (node/meta-node [(node/token-node 'MyType)
                                                   (node/spaces 1)
                                                   (node/token-node 'foo)])]
           ["^{:tag MyType} foo"  (node/meta-node
                                    [(node/map-node [(node/keyword-node :tag)
                                                     (node/spaces 1)
                                                     (node/token-node 'MyType)])
                                     (node/spaces 1)
                                     (node/token-node 'foo)])]
           ["#^MyType foo"        (node/raw-meta-node [(node/token-node 'MyType)
                                                       (node/spaces 1)
                                                       (node/token-node 'foo)])]
           ["#^{:tag MyType} foo" (node/raw-meta-node
                                    [(node/map-node [(node/keyword-node :tag)
                                                     (node/spaces 1)
                                                     (node/token-node 'MyType)])
                                     (node/spaces 1)
                                     (node/token-node 'foo)])]]

          :let [n (p/parse-string s)]]
    (is (= expected-node n) s)
    (is (= s (node/string n)))
    (is (= 'foo (node/sexpr n)) s)
    (is (= {:tag 'MyType} (meta (node/sexpr n))) s)))

(deftest t-parsing-tag-string-metadata
  (doseq [[s expected-node]
          [["^\"MyType\" foo"         (node/meta-node [(node/string-node "MyType")
                                                       (node/spaces 1)
                                                       (node/token-node 'foo)])]
           ["^{:tag \"MyType\"} foo"  (node/meta-node
                                        [(node/map-node [(node/keyword-node :tag)
                                                         (node/spaces 1)
                                                         (node/string-node "MyType")])
                                         (node/spaces 1)
                                         (node/token-node 'foo)])]
           ["#^\"MyType\" foo"        (node/raw-meta-node [(node/string-node "MyType")
                                                           (node/spaces 1)
                                                           (node/token-node 'foo)])]
           ["#^{:tag \"MyType\"} foo" (node/raw-meta-node
                                        [(node/map-node [(node/keyword-node :tag)
                                                         (node/spaces 1)
                                                         (node/string-node "MyType")])
                                         (node/spaces 1)
                                         (node/token-node 'foo)])]]

          :let [n (p/parse-string s)]]
    (is (= expected-node n) s)
    (is (= s (node/string n)))
    (is (= 'foo (node/sexpr n)) s)
    (is (= {:tag "MyType"} (meta (node/sexpr n))) s)))

(deftest t-parsing-clj-1-12-vector-metadata
  (doseq [[s expected-meta expected-node]
          [["^[a b c] foo"
            {:param-tags '[a b c]}
            (node/meta-node [(node/vector-node [(node/token-node 'a)
                                                (node/spaces 1)
                                                (node/token-node 'b)
                                                (node/spaces 1)
                                                (node/token-node 'c)])
                             (node/spaces 1)
                             (node/token-node 'foo)])]

           ["^[] foo"
            {:param-tags []}
            (node/meta-node [(node/vector-node [])
                             (node/spaces 1)
                             (node/token-node 'foo)])]

           ["^[_ _] foo"
            {:param-tags '[_ _]}
            (node/meta-node [(node/vector-node [(node/token-node '_)
                                                (node/spaces 1)
                                                (node/token-node '_)])
                             (node/spaces 1)
                             (node/token-node 'foo)])]

           ["^{:param-tags [a b c]} foo"
            {:param-tags '[a b c]}
            (node/meta-node
              [(node/map-node [(node/keyword-node :param-tags)
                               (node/spaces 1)
                               (node/vector-node [(node/token-node 'a)
                                                  (node/spaces 1)
                                                  (node/token-node 'b)
                                                  (node/spaces 1)
                                                  (node/token-node 'c)])])
               (node/spaces 1)
               (node/token-node 'foo)])]

           ["#^[a b c] foo"
            {:param-tags '[a b c]}
            (node/raw-meta-node [(node/vector-node [(node/token-node 'a)
                                                    (node/spaces 1)
                                                    (node/token-node 'b)
                                                    (node/spaces 1)
                                                    (node/token-node 'c)])
                                 (node/spaces 1)
                                 (node/token-node 'foo)])]

           ["#^{:param-tags [a b c]} foo"
            {:param-tags '[a b c]}
            (node/raw-meta-node
              [(node/map-node [(node/keyword-node :param-tags)
                               (node/spaces 1)
                               (node/vector-node [(node/token-node 'a)
                                                    (node/spaces 1)
                                                    (node/token-node 'b)
                                                    (node/spaces 1)
                                                    (node/token-node 'c)])])
               (node/spaces 1)
               (node/token-node 'foo)])]]

          :let [n (p/parse-string s)]]
    (is (= expected-node n) s)
    (is (= s (node/string n)))
    (is (= 'foo (node/sexpr n)) s)
    (is (= expected-meta (meta (node/sexpr n))) s)))

(deftest t-parsing-invalid-metadata
  (let [s "^(list not valid) foo"
        n (p/parse-string s)]
    (is (= (node/meta-node [(node/list-node [(node/token-node 'list)
                                             (node/spaces 1)
                                             (node/token-node 'not)
                                             (node/spaces 1)
                                             (node/token-node 'valid)])
                            (node/spaces 1)
                            (node/token-node 'foo)])
           n))
    (is (= s (node/string n)))
    (is (thrown-with-msg? ExceptionInfo #"Metadata must be a map, keyword, symbol or string"
                          (node/sexpr n)))))

(deftest t-parsing-reader-macros
  (doseq [[s t children]
          [["#'a"             :var             [:token]]
           ["#=(+ 1 2)"       :eval            [:list]]
           ["#macro 1"        :reader-macro    [:token :whitespace :token]]
           ["#macro (* 2 3)"  :reader-macro    [:token :whitespace :list]]
           ["#?(:clj bar)"    :reader-macro    [:token :list]]
           ["#? (:clj bar)"   :reader-macro    [:token :whitespace :list]]
           ["#?@ (:clj bar)"  :reader-macro    [:token :whitespace :list]]
           ["#?foo baz"       :reader-macro    [:token :whitespace :token]]
           ["#_abc"           :uneval          [:token]]
           ["#_(+ 1 2)"       :uneval          [:list]]]]
       (let [n (p/parse-string s)]
         (is (= t (node/tag n)))
         (is (= s (node/string n)))
         (is (= children (map node/tag (node/children n)))))))

(deftest t-parsing-anonymous-fn
  (doseq [[s t sexpr-match children]
          [["#(+ % 1)"
            :fn  #"\(fn\* \[p1_.*#\] \(\+ p1_.*# 1\)\)"
            [:token :whitespace
             :token :whitespace
             :token]]
           ["#(+ %& %2 %1)"
            :fn  #"\(fn\* \[p1_.*# p2_.*# & rest_.*#\] \(\+ rest_.*# p2_.*# p1_.*#\)\)"
            [:token :whitespace
             :token :whitespace
             :token :whitespace
             :token]]]]
      (let [n (p/parse-string s)]
        (is (= t (node/tag n)))
        (is (= s (node/string n)))
        (is (re-matches sexpr-match (str (node/sexpr n))))
        (is (= children (map node/tag (node/children n)))))))

(deftest t-parsing-comments
  (doseq [s ["; this is a comment\n"
             ";; this is a comment\n"
             "; this is a comment"
             ";; this is a comment"
             ";"
             ";;"
             ";\n"
             ";;\n"
             "#!shebang comment\n"
             "#! this is a comment"
             "#!\n"]]
    (let [n (p/parse-string s)]
      (is (node/printable-only? n))
      (is (= :comment (node/tag n)))
      (is (= s (node/string n))))))

(deftest t-parsing-auto-resolve-keywords
  (doseq [[s sexpr-default sexpr-custom]
          [["::key"        :?_current-ns_?/key    :my.current.ns/key]
           ["::xyz/key"    :??_xyz_??/key         :my.aliased.ns/key]]]
       (let [n (p/parse-string s)]
         (is (= :token (node/tag n)))
         (is (= s (node/string n)))
         (is (= sexpr-default (node/sexpr n)))
         (is (= sexpr-custom (node/sexpr n {:auto-resolve #(if (= :current %)
                                                              'my.current.ns
                                                              (get {'xyz 'my.aliased.ns} % 'alias-unresolved))}))))))

(deftest t-parsing-qualified-maps
  (doseq [[s sexpr]
          [["#:abc{:x 1, :y 1}"
            {:abc/x 1, :abc/y 1}]
           ["#:abc   {:x 1, :y 1}"
            {:abc/x 1, :abc/y 1}]
           ["#:abc  ,,, \n\n {:x 1 :y 2}"
            {:abc/x 1, :abc/y 2}]
           ["#:foo{:kw 1, :n/kw 2, :_/bare 3, 0 4}"
            {:foo/kw 1, :n/kw 2, :bare 3, 0 4}]
           ["#:abc{:a {:b 1}}"
            {:abc/a {:b 1}}]
           ["#:abc{:a #:def{:b 1}}"
            {:abc/a {:def/b 1}}]]]
       (let [n (p/parse-string s)]
         (is (= :namespaced-map (node/tag n)))
         (is (= (count s) (node/length n)))
         (is (= s (node/string n)))
         (is (= sexpr (node/sexpr n))))))

(deftest t-parsing-auto-resolve-current-ns-maps
  (doseq [[s sexpr-default sexpr-custom]
          [["#::{:x 1, :y 1}"
            {:?_current-ns_?/x 1, :?_current-ns_?/y 1}
            {:booya.fooya/x 1, :booya.fooya/y 1}]
           ["#::   {:x 1, :y 1}"
            {:?_current-ns_?/x 1, :?_current-ns_?/y 1}
            {:booya.fooya/x 1, :booya.fooya/y 1}]
           ["#:: \n,,\n,,  {:x 1, :y 1}"
            {:?_current-ns_?/x 1, :?_current-ns_?/y 1}
            {:booya.fooya/x 1, :booya.fooya/y 1}]
           ["#::{:kw 1, :n/kw 2, :_/bare 3, 0 4}"
            {:?_current-ns_?/kw 1, :n/kw 2, :bare 3, 0 4}
            {:booya.fooya/kw 1, :n/kw 2, :bare 3, 0 4}]
           ["#::{:a {:b 1}}"
            {:?_current-ns_?/a {:b 1}}
            {:booya.fooya/a {:b 1}}]
           ["#::{:a #::{:b 1}}"
            {:?_current-ns_?/a {:?_current-ns_?/b 1}}
            {:booya.fooya/a {:booya.fooya/b 1}}]]]
    (let [n (p/parse-string s)]
      (is (= :namespaced-map (node/tag n)))
      (is (= (count s) (node/length n)))
      (is (= s (node/string n)))
      (is (= sexpr-default (node/sexpr n)))
      (is (= sexpr-custom (node/sexpr n {:auto-resolve #(if (= :current %)
                                                          'booya.fooya
                                                          'alias-unresolved)}))))))

(deftest parsing-auto-resolve-ns-alias-maps
  (doseq [[s sexpr-default sexpr-custom]
          [["#::nsalias{:x 1, :y 1}"
            '{:??_nsalias_??/x 1, :??_nsalias_??/y 1}
            '{:bing.bang/x 1, :bing.bang/y 1}]
           ["#::nsalias   {:x 1, :y 1}"
            '{:??_nsalias_??/x 1, :??_nsalias_??/y 1}
            '{:bing.bang/x 1, :bing.bang/y 1}]
           ["#::nsalias ,,,,,,,,,,\n,,,,,,\n,,,,,  {:x 1, :y 1}"
            '{:??_nsalias_??/x 1, :??_nsalias_??/y 1}
            '{:bing.bang/x 1, :bing.bang/y 1}]
           ["#::nsalias{:kw 1, :n/kw 2, :_/bare 3, 0 4}"
            '{:??_nsalias_??/kw 1, :n/kw 2, :bare 3, 0 4}
            '{:bing.bang/kw 1, :n/kw 2, :bare 3, 0 4}]
           ["#::nsalias{:a {:b 1}}"
            '{:??_nsalias_??/a {:b 1}}
            '{:bing.bang/a {:b 1}}]
           ["#::nsalias{:a #::nsalias2{:b 1}}"
            '{:??_nsalias_??/a {:??_nsalias2_??/b 1}}
            '{:bing.bang/a {:woopa.doopa/b 1}}]]]
    (let [n (p/parse-string s)]
      (is (= :namespaced-map (node/tag n)))
      (is (= (count s) (node/length n)))
      (is (= s (node/string n)))
      (is (= sexpr-default (node/sexpr n)))
      (is (= sexpr-custom (node/sexpr n {:auto-resolve #(if (= :current %)
                                                          'my.current.ns
                                                          (get {'nsalias 'bing.bang
                                                                'nsalias2 'woopa.doopa} % 'alias-unresolved))}))))))

(deftest t-parsing-exceptions
  (doseq [[s p]
          [["#"                     #".*Unexpected EOF.*"]
           ["#("                    #".*Unexpected EOF.*"]
           ["(def"                  #".*Unexpected EOF.*"]
           ["[def"                  #".*Unexpected EOF.*"]
           ["#{def"                 #".*Unexpected EOF.*"]
           ["{:a 0"                 #".*Unexpected EOF.*"]
           ["\"abc"                 #".*EOF.*"]
           ["#\"abc"                #".*Unexpected EOF.*"]
           ["(def x 0]"             #".*Unmatched delimiter.*"]
           ["foobar/0"              #".*Invalid symbol: foobar/0"]  ;; array class dimension can be 1 to 9
           ["foobar/11"             #".*Invalid symbol: foobar/11"] ;; array class dimension can be 1 to 9
           ["##wtf"                 #".*Invalid token: ##wtf"]
           ["#="                    #".*:eval node expects 1 value.*"]
           ["#^"                    #".*:meta node expects 2 values.*"]
           ["^:private"             #".*:meta node expects 2 values.*"]
           ["#^:private"            #".*:meta node expects 2 values.*"]
           ["#_"                    #".*:uneval node expects 1 value.*"]
           ["#'"                    #".*:var node expects 1 value.*"]
           ["#macro"                #".*:reader-macro node expects 2 values.*"]
           ["#:"                    #".*namespaced map expects a namespace*"]
           ["#::"                   #".*namespaced map expects a map*"]
           ["#::nsarg"              #".*namespaced map expects a map*"]
           ["#:{:a 1}"              #".*namespaced map expects a namespace*"]
           ["#::[a]"                #".*namespaced map expects a map*"]
           ["#:[a]"                 #".*namespaced map expects a namespace*"]
           ["#:: token"             #".*namespaced map expects a map*"]
           ["#::alias [a]"          #".*namespaced map expects a map*"]
           ["#:prefix [a]"          #".*namespaced map expects a map.*"]]]
       (is (thrown-with-msg? ExceptionInfo p (p/parse-string s)) s)))

(deftest t-sexpr-exceptions
  (doseq [s ["#_42"                 ;; reader ignore/discard
             ";; can't sexpr me!"   ;; comment
             " "                    ;; whitespace
             ]]
    (is (thrown-with-msg? ExceptionInfo #"unsupported operation.*" (node/sexpr (p/parse-string s))))))

(deftest t-parsing-multiple-forms
  (let [s "1 2 3"
        n (p/parse-string-all s)
        children (node/children n)]
    (is (= :forms (node/tag n)))
    (is (= s (node/string n)))
    (is (= '(do 1 2 3) (node/sexpr n)))
    (is (= [:token :whitespace
            :token :whitespace
            :token]
           (map node/tag children))))
  (let [s ";; Hi!\n(def pi 3.14)"
        n (p/parse-string-all s)
        children (node/children n)]
    (is (= :forms (node/tag n)))
    (is (= s (node/string n)))
    (is (= '(def pi 3.14) (node/sexpr n)))
    (is (= [:comment :list] (map node/tag children)))
    (node/string (first children))))

#?(:clj
   (deftest t-parsing-files
     (let [f (doto (java.io.File/createTempFile "rewrite.test" "")
               (.deleteOnExit))
           s "âbcdé"
           c ";; Hi"
           o (str c "\n\n" (pr-str s))]
       (spit f o)
       (is (= o (slurp f)))
       (let [n (p/parse-file-all f)
             children (node/children n)]
         (is (= :forms (node/tag n)))
         (is (= o (node/string n)))
         (is (= s (node/sexpr n)))
         (is (= [:comment :newline :token] (map node/tag children)))
         (is (= [";; Hi\n" "\n" (pr-str s)] (map node/string children)))))))

(defn- nodes-with-meta
  "Create map associating row/column number pairs with the node at that position."
  [n]
  (let [start-pos ((juxt :row :col) (meta n))
        end-pos ((juxt :end-row :end-col) (meta n))
        entry {start-pos {:node n, :end-pos end-pos}}]
    (if (node/inner? n)
      (->> (node/children n)
           (map nodes-with-meta)
           (into entry))
      entry)))

(deftest t-rowcolumn-metadata-from-clojure-tools-reader
  ;; if you update this test, please also review/update:
  ;;   rewrite-clj.zip-test.t-rowcolumn-positions-from-position-tracking-zipper
  (let [s (str
           ;12345678901234
           "(defn f\n"
           "  [x]\n"
           "  (println x))")
        positions (->> (p/parse-string-all s)
                       (nodes-with-meta))]
    (doseq [[pos end t s sexpr]
            [[[1 1]  [3 15] :list   s              '(defn f [x] (println x))]
             [[1 2]  [1 6]  :token  "defn"         'defn]
             [[1 7]  [1 8]  :token  "f"            'f]
             [[2 3]  [2 6]  :vector "[x]"          '[x]]
             [[2 4]  [2 5]  :token  "x"            'x]
             [[3 3]  [3 14] :list   "(println x)"  '(println x)]
             [[3 4]  [3 11] :token  "println"      'println]
             [[3 12] [3 13] :token  "x"            'x]]]
      (let [{:keys [node end-pos]} (positions pos)]
        (is (= t (node/tag node)))
        (is (= s (node/string node)))
        (is (= sexpr (node/sexpr node)))
        (is (= end end-pos)))))
  ;; root node
  (let [s (str
           ;1234567890
           "(def a 1)\n"
           "(def b\n"
           "  2)")
        n (p/parse-string-all s)
        start-pos ((juxt :row :col) (meta n))
        end-pos ((juxt :end-row :end-col) (meta n))]
    (is (= [1 1] start-pos))
    (is (= [3 5] end-pos))))

(deftest t-os-specific-line-endings
  (doseq [[in expected]
          [["heya\r\nplaya\r\n"
            "heya\nplaya\n"]
           [";; comment\r\n(+ 1 2 3)\r\n"
            ";; comment\n(+ 1 2 3)\n"]
           ["1\r2\r\n3\r\f4"
            "1\n2\n3\n4"]
           ["\n\n\n\n"
            "\n\n\n\n"]
           ["\r\r\r\r\r"
            "\n\n\n\n\n"]
           ["\r\n\r\n\r\n\r\n\r\n\r\n"
            "\n\n\n\n\n\n"]
           [;1   2 3   4   5 6   7
            "\r\n\r\r\f\r\n\r\r\n\r"
            "\n\n\n\n\n\n\n"]
           ]]
    (let [str-actual (-> in p/parse-string-all node/string)]
      (is (= expected str-actual) "from string")
      #?(:clj
         (is (= expected (let [t-file (File/createTempFile "rewrite-clj-parse-test" ".clj")]
                            (.deleteOnExit t-file)
                            (spit t-file in)
                            (-> t-file p/parse-file-all node/string))) "from file")))))

(deftest t-position-in-ex-data
  (let [ex (try (p/parse-string "(defn foo [)")
                (catch #?(:clj Exception :cljs :default) e e))]
    (is (= 1 (-> ex ex-data :row)))
    (is (= 12 (-> ex ex-data :col)))))
