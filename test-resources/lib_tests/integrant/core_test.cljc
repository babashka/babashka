(ns integrant.core-test
  (:require [clojure.spec.alpha :as s]
   #?(:clj  [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [are deftest is testing]])
            [integrant.core :as ig]
            [weavejester.dependency :as dep]))

(def log (atom []))

(defmethod ig/prep-key ::p [_ v]
  (merge {:a (ig/ref ::a)} v))

(defmethod ig/init-key :default [k v]
  (swap! log conj [:init k v])
  [v])

(defmethod ig/init-key ::x [k v]
  (swap! log conj [:init k v])
  :x)

(defmethod ig/init-key ::error-init [_ _]
  (throw (ex-info "Testing" {:reason ::test})))

(defmethod ig/init-key ::k [_ v] v)

(defmethod ig/init-key ::n [_ v] (inc v))
(defmethod ig/pre-init-spec ::n [_] nat-int?)

(defmethod ig/init-key ::r [_ v] {:v v})
(defmethod ig/resolve-key ::r [_ {:keys [v]}] v)
(defmethod ig/resume-key ::r [k v _ _] (ig/init-key k v))

(defmethod ig/halt-key! :default [k v]
  (swap! log conj [:halt k v]))

(defmethod ig/halt-key! ::error-halt [_ _]
  (throw (ex-info "Testing" {:reason ::test})))

(defmethod ig/resume-key :default [k cfg cfg' sys]
  (swap! log conj [:resume k cfg cfg' sys])
  [cfg])

(defmethod ig/resume-key ::x [k cfg cfg' sys]
  (swap! log conj [:resume k cfg cfg' sys])
  :rx)

(defmethod ig/suspend-key! :default [k v]
  (swap! log conj [:suspend k v]))

(derive ::p ::pp)
(derive ::pp ::ppp)

(derive ::ap ::a)
(derive ::ap ::p)

(deftest ref-test
  (is (ig/ref? (ig/ref ::foo)))
  (is (ig/ref? (ig/ref [::foo ::bar])))
  (is (ig/reflike? (ig/ref ::foo)))
  (is (ig/reflike? (ig/ref [::foo ::bar]))))

(deftest refset-test
  (is (ig/refset? (ig/refset ::foo)))
  (is (ig/refset? (ig/refset [::foo ::bar])))
  (is (ig/reflike? (ig/refset ::foo)))
  (is (ig/reflike? (ig/refset [::foo ::bar]))))

(deftest composite-keyword-test
  (let [k (ig/composite-keyword [::a ::b])]
    (is (isa? k ::a))
    (is (isa? k ::b))
    (is (identical? k (ig/composite-keyword [::a ::b])))
    (is (not= k (ig/composite-keyword [::a ::c])))))

(deftest valid-config-key-test
  (is (ig/valid-config-key? ::a))
  (is (not (ig/valid-config-key? :a))))

(deftest expand-test
  (is (= (ig/expand {::a (ig/ref ::b), ::b 1})
         {::a 1, ::b 1}))
  (is (= (ig/expand {::a (ig/ref ::b), ::b (ig/ref ::c), ::c 2})
         {::a 2, ::b 2, ::c 2}))
  (is (= (ig/expand {::a (ig/ref ::pp), ::p 1})
         {::a 1, ::p 1}))
  (is (= (ig/expand {::a (ig/refset ::ppp), ::p 1, ::pp 2})
         {::a #{1 2}, ::p 1, ::pp 2}))
  (is (= (ig/expand {::a (ig/refset ::ppp)})
         {::a #{}})))

#?(:clj
   (deftest read-string-test
     (is (= (ig/read-string "{:foo/a #ig/ref :foo/b, :foo/b 1}")
            {:foo/a (ig/ref :foo/b), :foo/b 1}))
     (is (= (ig/read-string "{:foo/a #ig/refset :foo/b, :foo/b 1}")
            {:foo/a (ig/refset :foo/b), :foo/b 1}))
     (is (= (ig/read-string {:readers {'test/var find-var}}
                            "{:foo/a #test/var clojure.core/+}")
            {:foo/a #'+}))))

#?(:bb :TODO :clj
   (defn- remove-lib [lib]
     (remove-ns lib)
     (dosync (alter @#'clojure.core/*loaded-libs* disj lib))))

(derive :integrant.test-child/foo :integrant.test/foo)

#?(:bb :TODO
   :clj
   (deftest load-namespaces-test
     (testing "all namespaces"
       (remove-lib 'integrant.test.foo)
       (remove-lib 'integrant.test.bar)
       (remove-lib 'integrant.test.baz)
       (remove-lib 'integrant.test.quz)
       (is (= (set (ig/load-namespaces {:integrant.test/foo                     1
                                        :integrant.test.bar/wuz                 2
                                        [:integrant.test/baz :integrant.test/x] 3
                                        [:integrant.test/y :integrant.test/quz] 4}))
              '#{integrant.test.foo
                 integrant.test.bar
                 integrant.test.baz
                 integrant.test.quz}))
       (is (some? (find-ns 'integrant.test.foo)))
       (is (some? (find-ns 'integrant.test.bar)))
       (is (some? (find-ns 'integrant.test.baz)))
       (is (some? (find-ns 'integrant.test.quz)))
       (is (= (some-> 'integrant.test.foo/message find-var var-get) "foo"))
       (is (= (some-> 'integrant.test.bar/message find-var var-get) "bar"))
       (is (= (some-> 'integrant.test.baz/message find-var var-get) "baz"))
       (is (= (some-> 'integrant.test.quz/message find-var var-get) "quz")))

     (testing "some namespaces"
       (remove-lib 'integrant.test.foo)
       (remove-lib 'integrant.test.bar)
       (remove-lib 'integrant.test.baz)
       (remove-lib 'integrant.test.quz)
       (is (= (set (ig/load-namespaces
                    {:integrant.test/foo 1
                     :integrant.test/bar (ig/ref :integrant.test/foo)
                     :integrant.test/baz 3}
                    [:integrant.test/bar]))
              '#{integrant.test.foo
                 integrant.test.bar}))
       (is (some? (find-ns 'integrant.test.foo)))
       (is (some? (find-ns 'integrant.test.bar)))
       (is (nil?  (find-ns 'integrant.test.baz))))

     (testing "load namespaces of ancestors"
       (remove-lib 'integrant.test.foo)
       (is (= (set (ig/load-namespaces
                    {:integrant.test-child/foo 1}))
              '#{integrant.test.foo}))
       (is (some? (find-ns 'integrant.test.foo))))))


(deftest dependency-graph-test
  (let [m {::a (ig/ref ::p), ::b (ig/refset ::ppp) ::p 1, ::pp 2}]
    (testing "graph with refsets"
      (let [g (ig/dependency-graph m)]
        (is (dep/depends? g ::a ::p))
        (is (dep/depends? g ::b ::p))
        (is (dep/depends? g ::b ::pp))))

    (testing "graph without refsets"
      (let [g (ig/dependency-graph m {:include-refsets? false})]
        (is (dep/depends? g ::a ::p))
        (is (not (dep/depends? g ::b ::p)))
        (is (not (dep/depends? g ::b ::pp)))))))

(deftest key-comparator-test
  (let [graph (ig/dependency-graph {::a (ig/ref ::ppp) ::p 1, ::b 2})]
    (is (= (sort (ig/key-comparator graph) [::b ::a ::p])
           [::p ::a ::b]))))

(deftest derived-from?-test
  (are [a b] (ig/derived-from? a b)
    ::p           ::p
    ::p           ::pp
    ::p           ::ppp
    ::ap          [::a ::p]
    ::ap          [::a ::pp]
    [::a ::p]     [::a ::pp]
    [::a ::b ::p] [::a ::ppp]))

(deftest find-derived-1-test
  (testing "missing key"
    (is (nil? (ig/find-derived-1 {} ::p))))

  (testing "derived key"
    (is (= (ig/find-derived-1 {::a "x" ::p "y"} ::pp)
           [::p "y"])))

  (testing "ambiguous key"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Ambiguous key: " ::pp "\\. "
                          "Found multiple candidates: " ::p ", " ::pp))
         (ig/find-derived-1 {::a "x" ::p "y", ::pp "z"} ::pp))))

  (testing "composite key"
    (is (= (ig/find-derived-1 {::a "x" [::b ::x] "y"} ::x)
           [[::b ::x] "y"]))))

(deftest find-derived-test
  (testing "missing key"
    (is (nil? (ig/find-derived {} ::p))))

  (testing "derived key"
    (is (= (ig/find-derived {::a "x" ::p "y" ::pp "z"} ::pp)
           [[::p "y"] [::pp "z"]])))

  (testing "ambiguous key"
    (is (= (ig/find-derived {::a "x" ::p "y" ::pp "z"} ::ppp)
           [[::p "y"] [::pp "z"]])))

  (testing "composite key"
    (is (= (ig/find-derived {::a "x" [::b ::x] "y", [::b ::y] "z"} ::b)
           [[[::b ::x] "y"] [[::b ::y] "z"]]))))

(deftest prep-test
  (testing "default"
    (is (= (ig/prep {::q {:b 2}, ::a 1})
           {::q {:b 2}, ::a 1})))

  (testing "custom prep-key"
    (is (= (ig/prep {::p {:b 2}, ::a 1})
           {::p {:a (ig/ref ::a), :b 2}, ::a 1})))

  (testing "prep then init"
    (is (= (ig/init (ig/prep {::p {:b 2}, ::a 1}))
           {::p [{:a [1], :b 2}], ::a [1]}))))

(deftest init-test
  (testing "without keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b 1})]
      (is (= m {::a [[1]], ::b [1]}))
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]]))))

  (testing "with keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b 1, ::c 2} [::a])]
      (is (= m {::a [[1]], ::b [1]}))
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]]))))

  (testing "with inherited keys"
    (reset! log [])
    (let [m (ig/init {::p (ig/ref ::a), ::a 1} [::pp])]
      (is (= m {::p [[1]], ::a [1]}))
      (is (= @log [[:init ::a 1]
                   [:init ::p [1]]]))))

  (testing "with composite keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), [::x ::b] 1})]
      (is (= m {::a [:x], [::x ::b] :x}))
      (is (= @log [[:init [::x ::b] 1]
                   [:init ::a :x]]))))

  (testing "with composite refs"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref [::b ::c]), [::b ::c ::e] 1, [::b ::d] 2})]
      (is (= m {::a [[1]], [::b ::c ::e] [1], [::b ::d] [2]}))
      (is (or (= @log [[:init [::b ::c ::e] 1]
                       [:init ::a [1]]
                       [:init [::b ::d] 2]])
              (= @log [[:init [::b ::d] 2]
                       [:init [::b ::c ::e] 1]
                       [:init ::a [1]]])))))

  (testing "with failing composite refs"
    (reset! log [])
    (is (thrown-with-msg?
          #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          #"^Invalid composite key: \[:integrant.core-test/a :b\]. Every keyword must be namespaced.$"
          (ig/init {[::a :b] :anything}))))

  (testing "with custom resolve-key"
    (let [m (ig/init {::a (ig/ref ::r), ::r 1})]
      (is (= m {::a [1], ::r {:v 1}}))))

  (testing "with refsets"
    (reset! log [])
    (let [m (ig/init {::a (ig/refset ::ppp), ::p 1, ::pp 2})]
      (is (= m {::a [#{[1] [2]}], ::p [1], ::pp [2]}))
      (is (= @log [[:init ::p 1]
                   [:init ::pp 2]
                   [:init ::a #{[1] [2]}]]))))

  (testing "with refsets and keys"
    (reset! log [])
    (let [m {::a (ig/refset ::ppp), ::p 1, ::pp 2}]
      (is (= (ig/init m [::a])      {::a [#{}]}))
      (is (= (ig/init m [::a ::p])  {::a [#{[1]}] ::p [1]}))
      (is (= (ig/init m [::a ::pp]) {::a [#{[1] [2]}] ::p [1] ::pp [2]}))))

  (testing "large config"
    (is (= (ig/init {:a/a1 {} :a/a2 {:_ (ig/ref :a/a1)}
                     :a/a3 {} :a/a4 {} :a/a5 {}
                     :a/a6 {} :a/a7 {} :a/a8 {}
                     :a/a9 {} :a/a10 {}})
           {:a/a1 [{}] :a/a2 [{:_ [{}]}]
            :a/a3 [{}] :a/a4 [{}] :a/a5 [{}]
            :a/a6 [{}] :a/a7 [{}] :a/a8 [{}]
            :a/a9 [{}] :a/a10 [{}]})))

  (testing "with passing specs"
    (let [m (ig/init {::n (ig/ref ::k), ::k 1})]
      (is (= m {::n 2, ::k 1}))))

  (testing "with failing specs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Spec failed on key " ::n " when building system"))
         (ig/init {::n (ig/ref ::k), ::k 1.1}))))

  (testing "with failing composite specs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Spec failed on key \\[" ::n " " ::nnn "\\] when building system"))
         (ig/init {[::n ::nnn] 1.1})))))

(deftest halt-test
  (testing "without keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b 1})]
      (ig/halt! m)
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:halt ::a [[1]]]
                   [:halt ::b [1]]]))))

  (testing "with keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), ::b (ig/ref ::c), ::c 1})]
      (ig/halt! m [::a])
      (is (= @log [[:init ::c 1]
                   [:init ::b [1]]
                   [:init ::a [[1]]]
                   [:halt ::a [[[1]]]]]))
      (reset! log [])
      (ig/halt! m [::c])
      (is (= @log [[:halt ::a [[[1]]]]
                   [:halt ::b [[1]]]
                   [:halt ::c [1]]]))))

  (testing "with partial system"
    (reset! log [])
    (let [m (ig/init {::a 1, ::b (ig/ref ::a)} [::a])]
      (ig/halt! m)
      (is (= @log [[:init ::a 1]
                   [:halt ::a [1]]]))))

  (testing "with inherited keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::p), ::p 1} [::a])]
      (ig/halt! m [::pp])
      (is (= @log [[:init ::p 1]
                   [:init ::a [1]]
                   [:halt ::a [[1]]]
                   [:halt ::p [1]]]))))

  (testing "with composite keys"
    (reset! log [])
    (let [m (ig/init {::a (ig/ref ::b), [::x ::b] 1})]
      (ig/halt! m)
      (is (= @log [[:init [::x ::b] 1]
                   [:init ::a :x]
                   [:halt ::a [:x]]
                   [:halt [::x ::b] :x]])))))

(deftest suspend-resume-test
  (testing "same configuration"
    (reset! log [])
    (let [c  {::a (ig/ref ::b), ::b 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume c m)]
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:suspend ::a [[1]]]
                   [:suspend ::b [1]]
                   [:resume ::b 1 1 [1]]
                   [:resume ::a [1] [1] [[1]]]]))))

  (testing "missing keys"
    (reset! log [])
    (let [c  {::a (ig/ref ::b), ::b 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume (dissoc c ::a) m)]
      (is (= @log [[:init ::b 1]
                   [:init ::a [1]]
                   [:suspend ::a [[1]]]
                   [:suspend ::b [1]]
                   [:halt ::a [[1]]]
                   [:resume ::b 1 1 [1]]]))))

  (testing "missing refs"
    (reset! log [])
    (let [c  {::a {:b (ig/ref ::b)}, ::b 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume {::a []} m)]
      (is (= @log [[:init ::b 1]
                   [:init ::a {:b [1]}]
                   [:suspend ::a [{:b [1]}]]
                   [:suspend ::b [1]]
                   [:halt ::b [1]]
                   [:resume ::a [] {:b [1]} [{:b [1]}]]]))))

  (testing "with custom resolve-key"
    (let [c  {::a (ig/ref ::r), ::r 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume c m)]
      (is (= m m'))))

  (testing "composite keys"
    (reset! log [])
    (let [c  {::a (ig/ref ::x), [::b ::x] 1}
          m  (ig/init c)
          _  (ig/suspend! m)
          m' (ig/resume c m)]
      (is (= @log [[:init [::b ::x] 1]
                   [:init ::a :x]
                   [:suspend ::a [:x]]
                   [:suspend [::b ::x] :x]
                   [:resume [::b ::x] 1 1 :x]
                   [:resume ::a :rx :x [:x]]]))))

  (testing "resume key with dependencies"
    (reset! log [])
    (let [c  {::a {:b (ig/ref ::b)}, ::b 1}
          m  (ig/init c [::a])
          _  (ig/suspend! m)
          m' (ig/resume c m [::a])]
      (is (= @log
             [[:init ::b 1]
              [:init ::a {:b [1]}]
              [:suspend ::a [{:b [1]}]]
              [:suspend ::b [1]]
              [:resume ::b 1 1 [1]]
              [:resume ::a {:b [1]} {:b [1]} [{:b [1]}]]])))))

(deftest invalid-configs-test
  (testing "ambiguous refs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Ambiguous key: " ::ppp "\\. "
                          "Found multiple candidates: "
                          "(" ::p ", " ::pp "|" ::pp ", " ::p ")"))
         (ig/init {::a (ig/ref ::ppp), ::p 1, ::pp 2}))))

  (testing "missing refs"
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
         (re-pattern (str "Missing definitions for refs: " ::b))
         (ig/init {::a (ig/ref ::b)}))))

  (testing "missing refs with explicit keys"
    (is (= (ig/init {::a (ig/ref ::ppp), ::p 1, ::pp 2} [::p ::pp])
           {::p [1], ::pp [2]})))

  (testing "missing refs with explicit keys"
    (is (= (ig/init {::a 1, ::b (ig/ref ::c)} [::a])
           {::a [1]}))))

(defn build-log [config]
  (let [log (atom [])]
    [(ig/build config (keys config) (fn [k v] (last (swap! log conj [:build k v]))))
     @log]))

(deftest build-test
  (is (= [{::a [:build ::a [:build ::b 1]]
           ::b [:build ::b 1]}
          [[:build ::b 1]
           [:build ::a [:build ::b 1]]]]
         (build-log {::a (ig/ref ::b)
                     ::b 1}))))

(defn test-log [f m]
  (let [log (atom [])]
    [(f m (keys m) (fn [k v] (last (swap! log conj [:test k v]))))
     @log]))

(deftest run-test
  (let [config {::a (ig/ref ::b), ::b 1}
        [system _] (build-log config)]
    (is (= [nil
            [[:test ::b [:build ::b 1]]
             [:test ::a [:build ::a [:build ::b 1]]]]]
           (test-log ig/run! system)))
    (is (= [nil
            [[:test ::a [:build ::a [:build ::b 1]]]
             [:test ::b [:build ::b 1]]]]
           (test-log ig/reverse-run! system)))))

(deftest fold-test
  (let [config {::a (ig/ref ::ppp), ::b (ig/ref ::pp), ::p 1, ::c 2}
        system (ig/init config)]
    (is (= (ig/fold system #(conj %1 [%2 %3]) [])
           [[::p [1]] [::a [[1]]] [::b [[1]]] [::c [2]]]))))

(deftest wrapped-exception-test
  (testing "exception when building"
    (let [ex (try (ig/init {::a 1, ::error-init (ig/ref ::a)}) nil
                  (catch #?(:clj Throwable :cljs :default) t t))]
      (is (some? ex))
      (is (= (#?(:clj .getMessage :cljs ex-message) ex)
             (str "Error on key " ::error-init " when building system")))
      (is (= (ex-data ex)
             {:reason   ::ig/build-threw-exception
              :system   {::a [1]}
              :function ig/init-key
              :key      ::error-init
              :value    [1]}))
      (let [cause (#?(:clj .getCause :cljs ex-cause) ex)]
        (is (some? cause))
        (is (= (#?(:clj .getMessage :cljs ex-message) cause) "Testing"))
        (is (= (ex-data cause) {:reason ::test})))))

  (testing "exception when running"
    (let [system (ig/init {::a 1
                           ::error-halt (ig/ref ::a)
                           ::b (ig/ref ::error-halt)
                           ::c (ig/ref ::b)})
          ex     (try (ig/halt! system)
                      (catch #?(:clj Throwable :cljs :default) t t))]
      (is (some? ex))
      (is (= (#?(:clj .getMessage :cljs ex-message) ex)
             (str "Error on key " ::error-halt " when running system")))
      (is (= (ex-data ex)
             {:reason         ::ig/run-threw-exception
              :system         {::a [1], ::error-halt [[1]], ::b [[[1]]], ::c [[[[1]]]]}
              :completed-keys '(::c ::b)
              :remaining-keys '(::a)
              :function       ig/halt-key!
              :key            ::error-halt
              :value          [[1]]}))
      (let [cause (#?(:clj .getCause :cljs ex-cause) ex)]
        (is (some? cause))
        (is (= (#?(:clj .getMessage :cljs ex-message) cause) "Testing"))
        (is (= (ex-data cause) {:reason ::test}))))))
