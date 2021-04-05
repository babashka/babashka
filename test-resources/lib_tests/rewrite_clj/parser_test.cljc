(ns ^{:doc "Tests for EDN parser."
      :author "Yannick Scherer"}
 rewrite-clj.parser-test
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is are]]
            ;; not available in bb (yet):
            #_[clojure.tools.reader.edn :refer [read-string]]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as p])
  #?(:clj (:import [clojure.lang ExceptionInfo]
                   [java.io File])))

(deftest t-parsing-the-first-few-whitespaces
  (are [?ws ?parsed]
       (let [n (p/parse-string ?ws)]
         (is (= :whitespace (node/tag n)))
         (is (= ?parsed (node/string n))))
    "   "     "   "
    "   \n  " "   "))

(deftest t-parsing-whitespace-strings
  (are [?ws ?children]
       (let [n (p/parse-string-all ?ws)]
         (is (= :forms (node/tag n)))
         (is (= (.replace ?ws "\r\n" "\n") (node/string n)))
         (is (= ?children (map (juxt node/tag node/string) (node/children n)))))
    "   \n   "     [[:whitespace "   "]
                    [:newline "\n"]
                    [:whitespace "   "]]
    " \t \r\n \t " [[:whitespace " \t "]
                    [:newline "\n"]
                    [:whitespace " \t "]]))

#?(:clj
   (deftest t-parsing-unicode-whitespace-strings
     (are [?ws ?children]
         (let [n (p/parse-string-all ?ws)]
           (is (= :forms (node/tag n)))
           (is (= (.replace ?ws "\r\n" "\n") (node/string n)))
           (is (= ?children (map (juxt node/tag node/string) (node/children n)))))
       "\u2028"       [[:whitespace "\u2028"]])))

(deftest t-parsing-simple-data
  (are [?s ?r]
      (let [n (p/parse-string ?s)]
        (is (= :token (node/tag n)))
        (is (= ?s (node/string n)))
        (is (= ?r (node/sexpr n))))
    "0"                          0
    "0.1"                        0.1
    "12e10"                      1.2e11
    "2r1100"                     12
    "1N"                         1N
    ":key"                       :key
    "\\\\"                       \\
    "\\a"                        \a
    "\\space"                    \space
    "\\'"                        \'
    ":1.5"                       :1.5
    ":1.5.0"                     :1.5.0
    ":ns/key"                    :ns/key
    ":key:key"                   :key:key
    ":x'"                        :x'
    "sym"                        'sym
    "sym#"                       'sym#
    "sym'"                       'sym'
    "sym'sym"                    'sym'sym
    "sym:sym"                    'sym:sym
    "\"string\""                 "string"))

(deftest t-parsing-garden-selectors
  ;; https://github.com/noprompt/garden
  (are [?s ?r]
       (let [n (p/parse-string ?s)
             r (node/sexpr n)]
         (is (= ?s (node/string n)))
         (is (= :token (node/tag n)))
         (is (keyword? r))
         (is (= ?r r)))
    ":&:hover"    :&:hover
    ;; clj clojure reader can't parse :&::before but we can create a keyword for it
    ":&::before"  (keyword "&::before")))

(deftest t-ratios
  (are [?s ?r]
       (let [n (p/parse-string ?s)]
         (is (= :token (node/tag n)))
         (is (= ?s (node/string n)))
         (is (= ?r (node/sexpr n))))
    "3/4" #?(:clj 3/4
             ;; no ratios in cljs; they are evaluated on sexpr
             :cljs 0.75)))

(deftest t-big-integers
  (are [?s ?r]
      (let [n (p/parse-string ?s)]
        (is (= :token (node/tag n)))
        (is (= ?s (node/string n)))
        (is (= ?r (node/sexpr n))))
    "1234567890123456789012345678901234567890" 1234567890123456789012345678901234567890N))

(deftest t-parsing-symbolic-inf-values
  (are [?s ?r]
      (let [n (p/parse-string ?s)]
        (is (= :token (node/tag n)))
        (is (= ?s (node/string n)))
        (is (= ?r (node/sexpr n))))
    "##Inf" '##Inf
    "##-Inf" '##-Inf))

(deftest t-parsing-symbolic-NaN-value
  (let [n (p/parse-string "##NaN")
        e (node/sexpr n)]
    (is (= :token (node/tag n)))
    (is (= "##NaN" (node/string n)))
    #?(:cljs (is (js/Number.isNaN e))
       :default (is (Double/isNaN e)))))

(deftest t-parsing-reader-prefixed-data
  (are [?s ?t ?ws ?sexpr]
       (let [n (p/parse-string ?s)
             children (node/children n)
             c (map node/tag children)]
         (is (= ?t (node/tag n)))
         (is (= :token (last c)))
         (is (= ?sexpr (node/sexpr n)))
         (is (= 'sym (node/sexpr (last children))))
         (is (= ?ws (vec (butlast c)))))
    "@sym"                 :deref            []              '(deref sym)
    "@  sym"               :deref            [:whitespace]   '(deref sym)
    "'sym"                 :quote            []              '(quote sym)
    "'  sym"               :quote            [:whitespace]   '(quote sym)
    "`sym"                 :syntax-quote     []              '(quote sym)
    "`  sym"               :syntax-quote     [:whitespace]   '(quote sym)
    "~sym"                 :unquote          []              '(unquote sym)
    "~  sym"               :unquote          [:whitespace]   '(unquote sym)
    "~@sym"                :unquote-splicing []              '(unquote-splicing sym)
    "~@  sym"              :unquote-splicing [:whitespace]   '(unquote-splicing sym)
    "#=sym"                :eval             []              '(eval 'sym)
    "#=  sym"              :eval             [:whitespace]   '(eval 'sym)
    "#'sym"                :var              []              '(var sym)
    "#'\nsym"              :var              [:newline]      '(var sym)))

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
  (are [?s ?expected-sexpr]
       (let [n (p/parse-string ?s)]
         (is (= :regex (node/tag n)))
         (is (= (count ?s) (node/length n)))
         (is (= ?expected-sexpr (node/sexpr n))))
    "#\"regex\""       '(re-pattern "regex")
    "#\"regex\\.\""    '(re-pattern "regex\\.")
    "#\"[reg|k].x\""   '(re-pattern "[reg|k].x")
    "#\"a\\nb\""       '(re-pattern  "a\\nb")
    "#\"a\nb\""        '(re-pattern  "a\nb")

    "#\"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\""
    '(re-pattern "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")))

(deftest t-parsing-strings
  (are [?s ?tag ?sexpr]
       (let [n (p/parse-string ?s)]
         (is (= ?tag (node/tag n)))
         (is (= ?s (node/string n)))
         (is (= ?sexpr (node/sexpr n))))
    "\"123\""       :token       "123"
    "\"123\\n456\"" :token       "123\n456"
    "\"123\n456\""  :multi-line  "123\n456"))

(deftest t-parsing-seqs
  (are [?s ?t ?w ?c]
       (let [n (p/parse-string ?s)
             children (node/children n)
             fq (frequencies (map node/tag children))]
         (is (= ?t (node/tag n)))
         (is (= (.trim ?s) (node/string n)))
         (is (= (node/sexpr n) (edn/read-string ?s)))
         (is (= ?w (:whitespace fq 0)))
         (is (= ?c (:token fq 0))))
    "(1 2 3)"          :list        2  3
    "()"               :list        0  0
    "( )"              :list        1  0
    "() "              :list        0  0
    "[1 2 3]"          :vector      2  3
    "[]"               :vector      0  0
    "[ ]"              :vector      1  0
    "[] "              :vector      0  0
    "#{1 2 3}"         :set         2  3
    "#{}"              :set         0  0
    "#{ }"             :set         1  0
    "#{} "             :set         0  0
    "{:a 0 :b 1}"      :map         3  4
    "{}"               :map         0  0
    "{ }"              :map         1  0
    "{} "              :map         0  0))

(deftest t-parsing-invalid-maps
  ;; I don't know if this ability is intentional, but libraries
  ;; have come to rely on the behavior of parsing invalid maps.
  ;; Note: sexpr won't be possible on invalid Clojure
  (are [?s ?t]
       (let [n (p/parse-string ?s)]
         (is (= ?t (node/tag n)))
         (is (= ?s (node/string n))))
    "{:a}" :map
    "{:r 1 :u}" :map))

(deftest t-parsing-metadata
  (are [?s ?t ?mt]
       (let [s (str ?s " s")
             n (p/parse-string s)
             [mta ws sym] (node/children n)]
         (is (= ?t (node/tag n)))
         (is (= s (node/string n)))
         (is (= 's (node/sexpr n)))
         (is (= {:private true} (meta (node/sexpr n))))
         (is (= ?mt (node/tag mta)))
         (is (= :whitespace (node/tag ws)))
         (is (= :token (node/tag sym)))
         (is (= 's (node/sexpr sym))))
    "^:private"          :meta  :token
    "^{:private true}"   :meta  :map
    "#^:private"         :meta* :token
    "#^{:private true}"  :meta* :map))

(deftest t-parsing-multiple-metadata-forms
  (are [?s ?expected-meta-tag ?expected-tag-on-metadata]
       (let [s (str ?s " s")
             n (p/parse-string s)
             [mdata ws inner-n] (node/children n)
             [inner-mdata inner-ws sym] (node/children inner-n)]
         ;; outer meta
         (is (= ?expected-meta-tag (node/tag n)))
         (is (= {:private true :awe true} (meta (node/sexpr n))))
         (is (= ?expected-tag-on-metadata (node/tag mdata)))
         (is (= :whitespace (node/tag ws)))

         ;; inner meta
         (is (= ?expected-meta-tag (node/tag inner-n)))
         (is (= {:awe true} (meta (node/sexpr inner-n))))
         (is (= ?expected-tag-on-metadata (node/tag inner-mdata)))
         (is (= :whitespace (node/tag inner-ws)))

         ;; symbol
         (is (= s (node/string n)))
         (is (= 's (node/sexpr sym))))
    "^:private ^:awe"                 :meta  :token
    "^{:private true} ^{:awe true}"   :meta  :map
    "#^:private #^:awe"               :meta* :token
    "#^{:private true} #^{:awe true}" :meta* :map))

(deftest t-parsing-reader-macros
  (are [?s ?t ?children]
       (let [n (p/parse-string ?s)]
         (is (= ?t (node/tag n)))
         (is (= ?s (node/string n)))
         (is (= ?children (map node/tag (node/children n)))))
    "#'a"             :var             [:token]
    "#=(+ 1 2)"       :eval            [:list]
    "#macro 1"        :reader-macro    [:token :whitespace :token]
    "#macro (* 2 3)"  :reader-macro    [:token :whitespace :list]
    "#?(:clj bar)"    :reader-macro    [:token :list]
    "#? (:clj bar)"   :reader-macro    [:token :whitespace :list]
    "#?@ (:clj bar)"  :reader-macro    [:token :whitespace :list]
    "#?foo baz"       :reader-macro    [:token :whitespace :token]
    "#_abc"           :uneval          [:token]
    "#_(+ 1 2)"       :uneval          [:list]))

(deftest t-parsing-anonymous-fn
  (are [?s ?t ?sexpr-match ?children]
      (let [n (p/parse-string ?s)]
        (is (= ?t (node/tag n)))
        (is (= ?s (node/string n)))
        (is (re-matches ?sexpr-match (str (node/sexpr n))))
        (is (= ?children (map node/tag (node/children n)))))
    "#(+ % 1)"
    :fn  #"\(fn\* \[p1_.*#\] \(\+ p1_.*# 1\)\)"
    [:token :whitespace
     :token :whitespace
     :token]

    "#(+ %& %2 %1)"
    :fn  #"\(fn\* \[p1_.*# p2_.*# & rest_.*#\] \(\+ rest_.*# p2_.*# p1_.*#\)\)"
    [:token :whitespace
     :token :whitespace
     :token :whitespace
     :token]))

(deftest t-parsing-comments
  (are [?s]
       (let [n (p/parse-string ?s)]
         (is (node/printable-only? n))
         (is (= :comment (node/tag n)))
         (is (= ?s (node/string n))))
    "; this is a comment\n"
    ";; this is a comment\n"
    "; this is a comment"
    ";; this is a comment"
    ";"
    ";;"
    ";\n"
    ";;\n"
    "#!shebang comment\n"
    "#! this is a comment"
    "#!\n"))

(deftest t-parsing-auto-resolve-keywords
  (are [?s ?sexpr-default ?sexpr-custom]
       (let [n (p/parse-string ?s)]
         (is (= :token (node/tag n)))
         (is (= ?s (node/string n)))
         (is (= ?sexpr-default (node/sexpr n)))
         (is (= ?sexpr-custom (node/sexpr n {:auto-resolve #(if (= :current %)
                                                              'my.current.ns
                                                              (get {'xyz 'my.aliased.ns} % 'alias-unresolved))}))))
    "::key"        :?_current-ns_?/key    :my.current.ns/key
    "::xyz/key"    :??_xyz_??/key         :my.aliased.ns/key))

(deftest t-parsing-qualified-maps
  (are [?s ?sexpr]
       (let [n (p/parse-string ?s)]
         (is (= :namespaced-map (node/tag n)))
         (is (= (count ?s) (node/length n)))
         (is (= ?s (node/string n)))
         (is (= ?sexpr (node/sexpr n))))
    "#:abc{:x 1, :y 1}"
    {:abc/x 1, :abc/y 1}

    "#:abc   {:x 1, :y 1}"
    {:abc/x 1, :abc/y 1}

    "#:abc  ,,, \n\n {:x 1 :y 2}"
    {:abc/x 1, :abc/y 2}

    "#:foo{:kw 1, :n/kw 2, :_/bare 3, 0 4}"
    {:foo/kw 1, :n/kw 2, :bare 3, 0 4}

    "#:abc{:a {:b 1}}"
    {:abc/a {:b 1}}

    "#:abc{:a #:def{:b 1}}"
    {:abc/a {:def/b 1}}))

(deftest t-parsing-auto-resolve-current-ns-maps
  (are [?s ?sexpr-default ?sexpr-custom]
       (let [n (p/parse-string ?s)]
         (is (= :namespaced-map (node/tag n)))
         (is (= (count ?s) (node/length n)))
         (is (= ?s (node/string n)))
         (is (= ?sexpr-default (node/sexpr n)))
         (is (= ?sexpr-custom (node/sexpr n {:auto-resolve #(if (= :current %)
                                                              'booya.fooya
                                                              'alias-unresolved)}))))
    "#::{:x 1, :y 1}"
    {:?_current-ns_?/x 1, :?_current-ns_?/y 1}
    {:booya.fooya/x 1, :booya.fooya/y 1}

    "#::   {:x 1, :y 1}"
    {:?_current-ns_?/x 1, :?_current-ns_?/y 1}
    {:booya.fooya/x 1, :booya.fooya/y 1}

    "#:: \n,,\n,,  {:x 1, :y 1}"
    {:?_current-ns_?/x 1, :?_current-ns_?/y 1}
    {:booya.fooya/x 1, :booya.fooya/y 1}

    "#::{:kw 1, :n/kw 2, :_/bare 3, 0 4}"
    {:?_current-ns_?/kw 1, :n/kw 2, :bare 3, 0 4}
    {:booya.fooya/kw 1, :n/kw 2, :bare 3, 0 4}

    "#::{:a {:b 1}}"
    {:?_current-ns_?/a {:b 1}}
    {:booya.fooya/a {:b 1}}

    "#::{:a #::{:b 1}}"
    {:?_current-ns_?/a {:?_current-ns_?/b 1}}
    {:booya.fooya/a {:booya.fooya/b 1}}))

(deftest parsing-auto-resolve-ns-alias-maps
  (are [?s ?sexpr-default ?sexpr-custom]
       (let [n (p/parse-string ?s)]
         (is (= :namespaced-map (node/tag n)))
         (is (= (count ?s) (node/length n)))
         (is (= ?s (node/string n)))
         (is (= ?sexpr-default (node/sexpr n)))
         (is (= ?sexpr-custom (node/sexpr n {:auto-resolve #(if (= :current %)
                                                              'my.current.ns
                                                              (get {'nsalias 'bing.bang
                                                                    'nsalias2 'woopa.doopa} % 'alias-unresolved))}))))
    "#::nsalias{:x 1, :y 1}"
    '{:??_nsalias_??/x 1, :??_nsalias_??/y 1}
    '{:bing.bang/x 1, :bing.bang/y 1}

    "#::nsalias   {:x 1, :y 1}"
    '{:??_nsalias_??/x 1, :??_nsalias_??/y 1}
    '{:bing.bang/x 1, :bing.bang/y 1}

    "#::nsalias ,,,,,,,,,,\n,,,,,,\n,,,,,  {:x 1, :y 1}"
    '{:??_nsalias_??/x 1, :??_nsalias_??/y 1}
    '{:bing.bang/x 1, :bing.bang/y 1}

    "#::nsalias{:kw 1, :n/kw 2, :_/bare 3, 0 4}"
    '{:??_nsalias_??/kw 1, :n/kw 2, :bare 3, 0 4}
    '{:bing.bang/kw 1, :n/kw 2, :bare 3, 0 4}

    "#::nsalias{:a {:b 1}}"
    '{:??_nsalias_??/a {:b 1}}
    '{:bing.bang/a {:b 1}}

    "#::nsalias{:a #::nsalias2{:b 1}}"
    '{:??_nsalias_??/a {:??_nsalias2_??/b 1}}
    '{:bing.bang/a {:woopa.doopa/b 1}}))

(deftest t-parsing-exceptions
  (are [?s ?p]
       (is (thrown-with-msg? ExceptionInfo ?p (p/parse-string ?s)))
    "#"                     #".*Unexpected EOF.*"
    "#("                    #".*Unexpected EOF.*"
    "(def"                  #".*Unexpected EOF.*"
    "[def"                  #".*Unexpected EOF.*"
    "#{def"                 #".*Unexpected EOF.*"
    "{:a 0"                 #".*Unexpected EOF.*"
    "\"abc"                 #".*EOF.*"
    "#\"abc"                #".*Unexpected EOF.*"
    "(def x 0]"             #".*Unmatched delimiter.*"
    "##wtf"                 #".*Invalid token: ##wtf"
    "#="                    #".*:eval node expects 1 value.*"
    "#^"                    #".*:meta node expects 2 values.*"
    "^:private"             #".*:meta node expects 2 values.*"
    "#^:private"            #".*:meta node expects 2 values.*"
    "#_"                    #".*:uneval node expects 1 value.*"
    "#'"                    #".*:var node expects 1 value.*"
    "#macro"                #".*:reader-macro node expects 2 values.*"
    "#:"                    #".*namespaced map expects a namespace*"
    "#::"                   #".*namespaced map expects a map*"
    "#::nsarg"              #".*namespaced map expects a map*"
    "#:{:a 1}"              #".*namespaced map expects a namespace*"
    "#::[a]"                #".*namespaced map expects a map*"
    "#:[a]"                 #".*namespaced map expects a namespace*"
    "#:: token"             #".*namespaced map expects a map*"
    "#::alias [a]"          #".*namespaced map expects a map*"
    "#:prefix [a]"          #".*namespaced map expects a map.*"))

(deftest t-sexpr-exceptions
  (are [?s]
      (is (thrown-with-msg? ExceptionInfo #"unsupported operation.*" (node/sexpr (p/parse-string ?s))))
    "#_42"                 ;; reader ignore/discard
    ";; can't sexpr me!"   ;; comment
    " "                    ;; whitespace
    ))

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
    (are [?pos ?end ?t ?s ?sexpr]
         (let [{:keys [node end-pos]} (positions ?pos)]
           (is (= ?t (node/tag node)))
           (is (= ?s (node/string node)))
           (is (= ?sexpr (node/sexpr node)))
           (is (= ?end end-pos)))
      [1 1]  [3 15] :list   s              '(defn f [x] (println x))
      [1 2]  [1 6]  :token  "defn"         'defn
      [1 7]  [1 8]  :token  "f"            'f
      [2 3]  [2 6]  :vector "[x]"          '[x]
      [2 4]  [2 5]  :token  "x"            'x
      [3 3]  [3 14] :list   "(println x)"  '(println x)
      [3 4]  [3 11] :token  "println"      'println
      [3 12] [3 13] :token  "x"            'x)))


(deftest t-os-specific-line-endings
  (are [?in ?expected]
    (let [str-actual (-> ?in p/parse-string-all node/string)]
      (is (= ?expected str-actual) "from string")
      #?(:clj
         (is (= ?expected (let [t-file (File/createTempFile "rewrite-clj-parse-test" ".clj")]
                            (.deleteOnExit t-file)
                            (spit t-file ?in)
                            (-> t-file p/parse-file-all node/string))) "from file")))
    "heya\r\nplaya\r\n"
    "heya\nplaya\n"

    ";; comment\r\n(+ 1 2 3)\r\n"
    ";; comment\n(+ 1 2 3)\n"

    "1\r2\r\n3\r\f4"
    "1\n2\n3\n4"

    "\n\n\n\n"
    "\n\n\n\n"

    "\r\r\r\r\r"
    "\n\n\n\n\n"

    "\r\n\r\n\r\n\r\n\r\n\r\n"
    "\n\n\n\n\n\n"

    ;1   2 3   4   5 6   7
    "\r\n\r\r\f\r\n\r\r\n\r"
    "\n\n\n\n\n\n\n"))


