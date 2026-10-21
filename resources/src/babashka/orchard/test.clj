(ns orchard.test
  "Run `clojure.test` tests and collect structured results.

  The smallest unit of execution is the var (as created by `deftest`), which
  runs all the assertions defined within it. Results are indexed by namespace,
  var and assertion index within the var, and failures and errors carry
  pretty-printed expected/actual values, so a client can render them without
  evaluating anything else.

  Pass an `:on-event` function in the options of `run-var-query` or
  `run-namespaces` to be told about progress while the run is under way,
  instead of only getting the report at the end. It's called on the thread
  running the tests with one of:

  - `{:type :begin-ns, :ns ns-sym}` before a namespace's tests run.
  - `{:type :end-var, :ns ns-sym, :var var-sym, :results [...], :summary {...}}`
    after a test var completes, or a fixture or `test-ns-hook` throws.
    `:results` holds that var's assertion results and `:summary` the running
    totals of the run.
  - `{:type :end-ns, :ns ns-sym, :elapsed-time {...}}` after a namespace's
    tests have run."
  {:added "0.45"}
  (:require
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.test :as test]
   [clojure.walk :as walk]
   [orchard.query :as query]
   [orchard.stacktrace :as stacktrace]
   [sci.core :as sci])) ;; BB-PATCH sci's stack frames for the line of an error

;;; ## Test Results
;;
;; `clojure.test` allows extensible test reporting by rebinding the `report`
;; function. The implementation below does this to capture report events in the
;; `current-report` atom.

(def current-report
  "An atom holding the results of the test run in progress."
  (atom nil))

(def ^:dynamic *on-event*
  "The `:on-event` function of the run in progress, if any."
  nil)

(defn- report-reset! []
  (reset! current-report {:summary {:ns 0
                                    :var 0
                                    :test 0
                                    :pass 0
                                    :fail 0
                                    :error 0}
                          :results {}
                          :testing-ns nil
                          :gen-input nil}))

(defn- emit! [event]
  (when *on-event*
    ;; A failing callback (say, a client that went away mid-run) mustn't
    ;; change the outcome of the tests, so its errors are dropped.
    (try
      (*on-event* event)
      (catch Throwable _))))

;; In the case of test errors, line number is obtained by searching the
;; stacktrace for the originating function. The search target will be the
;; current test var's `:test` metadata (which holds the actual test function) if
;; present, or the deref'ed var function otherwise (i.e. test fixture errors).
;;
;; This approach is similar in use to `clojure.test/file-position`, but doesn't
;; assume a fixed position in the stacktrace, and therefore resolves the correct
;; frame when the error occurs outside of an `is` form.

(defn stack-frame
  "Search the stacktrace of exception `e` for the function `f` and return info
  describing the stack frame, including var, class, and line."
  [^Exception e f]
  (when-let [class-name (some-> f class .getName)]
    (when-let [analyzed-trace (:stacktrace (first (stacktrace/analyze e)))]
      (some #(when-let [frame-cname (:class %)]
               (when (and (string? frame-cname)
                          (str/starts-with? frame-cname class-name))
                 %))
            analyzed-trace))))

(defn- deep-sorted-maps
  "Recursively converts all nested maps to sorted maps."
  [m]
  (try
    (walk/postwalk
     (fn [x]
       (if (and (map? x) (not (record? x))) ;; Prevent records turning into maps
         (with-meta (into (sorted-map) x) (meta x))
         x))
     m)
    (catch Throwable _
      ;; Some objects can't be walked or sorted (e.g. non-comparable keys,
      ;; Datomic entities that throw AbstractMethodError). Return as-is.
      m)))

(defn print-object
  "Print `object` using println for matcher-combinators results and pprint
  otherwise. The matcher-combinators library uses a custom print-method
  which doesn't get picked up by pprint since it uses a different dispatch
  mechanism."
  [object]
  (let [matcher-combinators-result? (= (:type (meta object))
                                       :matcher-combinators.clj-test/mismatch)
        print-fn (if matcher-combinators-result?
                   prn
                   pp/pprint)
        ;; The output will contain sorted maps for better readability and diff comparisons.
        result (with-out-str (print-fn (deep-sorted-maps object)))]
    ;; Replace extra newlines at the end, as sometimes returned by matchers-combinators:
    (str/replace result #"\n\n+$" "\n")))

(defn- diffs-result
  "Pretty-print the `[actual [removed added]]` diffs attached to a failure."
  [diffs]
  (let [pprint-str #(with-out-str (pp/pprint %))]
    (map (fn [[a [removed added]]]
           [(pprint-str a)
            [(pprint-str removed) (pprint-str added)]])
         diffs)))

(def ^:dynamic *test-error-handler*
  "A function you can override via `binding`, or safely via `alter-var-root`.
  On test `:error`s, the related Throwable be invoked as the sole argument
  passed to this var.
  For example, you can use this to add an additional `println`,
  for pretty-printing Spec failures. Remember to `flush` if doing so."
  identity)

(def fallback-var-name
  "The pseudo var name which will be used when no var name can be found
  for a given test report."
  ::unknown)

(defn- var-name
  "The name results of test var `v` are filed under."
  [v]
  (or (:name (meta v)) fallback-var-name))

(defn test-result
  "Transform the result of a test assertion. Append ns, var, assertion index,
  and 'testing' context. Retain any exception. Pretty-print expected/actual or
  use its `print-method`, if applicable."
  [ns v m]
  (let [{:keys [actual diffs expected fault]
         t :type} m
        v-name (var-name v)
        c (when (seq test/*testing-contexts*) (test/testing-contexts-str))
        i (count (get-in (:results @current-report {}) [ns v-name]))
        gen-input (:gen-input @current-report)]

    ;; Errors outside assertions (faults) do not return an :expected value.
    ;; Type :fail returns :actual value. Type :error returns :error and :line.
    (merge (dissoc m :expected :actual)
           {:ns ns, :var v-name, :index i, :context c}
           (when (and (#{:fail :error} t) (not fault))
             {:expected (print-object expected)})
           (when (and (#{:fail} t) gen-input)
             {:gen-input (print-object gen-input)})
           (when (#{:fail} t)
             {:actual (print-object actual)})
           (when diffs
             {:diffs (diffs-result diffs)})
           (when (#{:error} t)
             #_(let [e actual
                   f (or (:test (meta v)) (some-> v deref))] ; test fn or deref'ed fixture
               (*test-error-handler* e)
               {:error e
                :line (:line (stack-frame e f))}) ;; BB-PATCH sci fns share their classes, find the test in sci's stack frames
             (let [e (or (:babashka.impl.clojure.test/sci-error m) actual)
                   frames (try (sci/stacktrace e) (catch Throwable _ nil))
                   in-test (some #(when (and (= (str ns) (str (:ns %)))
                                             (= (str v-name) (str (:name %))))
                                    %)
                                 frames)]
               (*test-error-handler* e)
               {:error e
                :line (or (:line in-test) (:line (meta v)))})))))

(defn- emit-var-results! [ns v-name]
  (when *on-event*
    (let [{:keys [results summary]} @current-report]
      (emit! {:type :end-var
              :ns ns
              :var v-name
              :results (get-in results [ns v-name] [])
              :summary summary}))))

;;; ## test.check integration
;;
;; `test.check` generates random test inputs for property testing. We make the
;; inputs part of the report by parsing the respective calls to `report`:
;; `test.chuck`'s `checking` creates events of type
;; `:com.gfredericks.test.chuck.clojure-test/shrunk` with the minimal failing
;; input as determined by `test.check`. `test.check`'s own `defspec` does report
;; minimal inputs in recent versions, but for compatibility we also parse events
;; of type `:clojure.test.check.clojure-test/shrinking`, which `defspec`
;; produces to report failing input before shrinking it.

(defmulti report
  "Handle reporting for test events.

  This takes a test event map as an argument and updates the `current-report`
  atom to reflect test results and summary statistics."
  :type)

(defmethod report :default [_m])

(defmethod report :begin-test-ns
  [m]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))]
    (swap! current-report
           #(-> %
                (assoc :testing-ns ns)
                (update-in [:summary :ns] inc)))
    (emit! {:type :begin-ns :ns ns})))

(defmethod report :begin-test-var
  [_m]
  (swap! current-report update-in [:summary :var] inc))

(defn- in-checking-block?
  "Determine whether the report being generated is for a test.chuck `checking` block."
  [m]
  (boolean (:com.gfredericks.test.chuck.clojure-test/testing-contexts m)))

(defn- report-final-status
  [{:keys [type] :as m}]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))
        v (last test/*testing-vars*)
        gen-input (when (in-checking-block? m)
                    (:gen-input @current-report))]
    (swap! current-report
           #(-> %
                (update-in [:summary :test] inc)
                (update-in [:summary type] (fnil inc 0))
                (assoc :gen-input gen-input)
                (update-in [:results ns (var-name v)]
                           (fnil conj [])
                           (test-result ns v m))))))

(defmethod report :end-test-var
  [{:keys [var-elapsed-time]
    var-ref :var}]
  (let [n (or (some-> var-ref meta :ns ns-name)
              (:testing-ns @current-report))
        v (var-name var-ref)
        contexts-count (-> @current-report
                           (get-in [:results n v])
                           count)]
    (when var-elapsed-time
      ;; The timing info is only valid when the test var contained a single `is` assertion.
      ;; This is because timing works at var (`deftest`) granularity, not at `is` granularity.
      (when (= 1 contexts-count)
        (swap! current-report
               assoc-in
               [:results n v 0 :elapsed-time]
               var-elapsed-time))
      (swap! current-report
             assoc-in
             [:var-elapsed-time n v :elapsed-time]
             var-elapsed-time))
    (emit-var-results! n v)))

(defmethod report :end-test-ns
  [{:keys [ns-ref ns-elapsed-time]}]
  (let [n (or (some-> ns-ref ns-name)
              (:testing-ns @current-report))]
    (swap! current-report
           assoc-in
           [:ns-elapsed-time n]
           ns-elapsed-time)
    (emit! {:type :end-ns :ns n :elapsed-time ns-elapsed-time})))

(defmethod report :pass
  [m]
  (report-final-status m))

(defmethod report :fail
  [m]
  (report-final-status m))

(defmethod report :error
  [m]
  (report-final-status m))

(defmethod report :com.gfredericks.test.chuck.clojure-test/shrunk
  [m]
  (swap! current-report assoc :gen-input (-> m :shrunk :smallest)))

(defn- report-shrinking
  [{:keys [clojure.test.check.clojure-test/params]}]
  (swap! current-report assoc :gen-input params))

(defmethod report :clojure.test.check.clojure-test/shrinking
  [m]
  (report-shrinking m))

(defmethod report :clojure.test.check.clojure-test/shrunk
  [m]
  (report-shrinking m))

(defmethod report :matcher-combinators/mismatch
  [m]
  (report-final-status (assoc m
                              :type :fail
                              :actual (:markup m))))

(defn- report-uncaught-error
  "Report `e`, thrown outside of any assertion, as an error of var `v` in
  namespace object `ns`."
  [ns v e message]
  (binding [test/*testing-vars* (list v)]
    (report {:type :error, :fault true, :expected nil, :actual e
             :message message}))
  (emit-var-results! (ns-name ns) (var-name v)))

(defn report-fixture-error
  "Delegate reporting for test fixture errors to the `report` function. This
  finds the erring test fixture in the stacktrace and binds it as the current
  test var. Test count is decremented to indicate that no tests were run."
  [ns e]
  (let [frame (->> (concat (::test/once-fixtures (meta ns))
                           (::test/each-fixtures (meta ns)))
                   (keep #(stack-frame e %))
                   (first))
        ;; When no fixture frame matches, the throwable didn't originate in a
        ;; fixture - it escaped the test body itself (e.g. interrupting a test
        ;; that isn't wrapped in `is`). Guard against that so we report the
        ;; error instead of crashing on `(symbol nil)`.
        fixture (some-> frame :var symbol resolve)]
    (swap! current-report update-in [:summary :test] dec)
    (if fixture
      (report-uncaught-error ns fixture e "Uncaught exception in test fixture")
      (report-uncaught-error ns (last test/*testing-vars*) e
                             #_"Uncaught exception during test run" ;; BB-PATCH sci fns share their classes, so no fixture frame is found, and test-var catches what a test throws
                             "Uncaught exception in test fixture"))))

(defmacro ^:private timing
  "Executes `body`, reporting the time it took by persisting it to `time-atom`."
  {:style/indent 1}
  [time-atom & body]
  {:pre [(seq body)]}
  `(let [then# (System/currentTimeMillis)
         v# (do
              ~@body)
         took# (- (System/currentTimeMillis)
                  then#)]
     (reset! ~time-atom {:ms took#
                         :humanized (str "Completed in " took# " ms")})
     v#))

;;; ## Test Execution
;;
;; These functions are based on the ones in `clojure.test`, updated to accept
;; a list of vars to test, use the report implementation above, and distinguish
;; between test errors and faults outside of assertions.

(defn test-var
  "If var `v` has a function in its `:test` metadata, call that function,
  with `clojure.test/*testing-vars*` bound to append `v`."
  [v]
  (when-let [t (:test (meta v))]
    (binding [test/*testing-vars* (conj test/*testing-vars* v)]
      (test/do-report {:type :begin-test-var :var v})
      (test/inc-report-counter :test)
      (let [time-info (atom nil)
            result (timing time-info
                           (try
                             (t)
                             ::ok
                             (catch Throwable e
                               e)))]
        (when-not (= ::ok result)
          (test/do-report {:type :error
                           :fault true
                           :expected nil
                           :actual result
                           :message "Uncaught exception, not in assertion"}))
        (test/do-report {:type :end-test-var
                         :var v
                         :var-elapsed-time @time-info})))))

(defn- current-run-failed? []
  (or (some-> @current-report :summary :fail pos?)
      (some-> @current-report :summary :error pos?)))

(defn- test-vars
  "Call `test-var` on each var, with the fixtures defined for namespace object
  `ns`."
  [ns vars fail-fast?]
  (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))
        each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
    (try
      (once-fixture-fn
       (fn []
         (reduce (fn [_ v]
                   (cond-> (each-fixture-fn (fn []
                                              (test-var v)))
                     (and fail-fast? (current-run-failed?))
                     reduced))
                 nil
                 vars)))
      (catch Throwable e
        (report-fixture-error ns e)))))

(defn- call-test-ns-hook
  "Call `hook`, the `test-ns-hook` var of namespace object `ns`. Anything it
  throws is filed as an error of the hook itself, so the rest of the run goes
  on."
  [ns hook]
  (try
    (hook)
    (catch Throwable e
      (report-uncaught-error ns hook e "Uncaught exception in test-ns-hook"))))

(defn- test-ns
  "If the namespace object defines a function named `test-ns-hook`, call that.
  Otherwise, test the specified vars."
  [ns vars fail-fast?]
  (binding [test/report report
            test/*report-counters* (ref test/*initial-report-counters*)]
    (test/do-report {:type :begin-test-ns, :ns ns})
    (let [time-info (atom nil)]
      (timing time-info
              (if-let [test-hook (ns-resolve ns 'test-ns-hook)]
                (call-test-ns-hook ns test-hook)
                (test-vars ns vars fail-fast?)))
      (test/do-report {:type :end-test-ns
                       :ns ns
                       :ns-elapsed-time @time-info}))))

(defn- only-tests
  "Narrow an `[ns vars]` pair down to its test vars, or to nil when the
  namespace has neither those nor a `test-ns-hook`."
  [[ns vars]]
  (let [vars (filter (comp :test meta) vars)]
    (when (or (seq vars) (ns-resolve ns 'test-ns-hook))
      [ns vars])))

(defn- run-corpus
  "Test each `[ns vars]` pair of `corpus` and return the report. Only test
  vars are run, and namespaces with neither those nor a `test-ns-hook` are
  left out."
  [corpus {:keys [fail-fast? on-event]}]
  (report-reset!)
  ;; Results are filed under the outermost testing var and carry the enclosing
  ;; `testing` strings, so start from none of either, even when the run itself
  ;; happens inside a test.
  (binding [*on-event* on-event
            test/*testing-vars* (list)
            test/*testing-contexts* (list)]
    (let [elapsed-time (atom nil)]
      (timing elapsed-time
              (reduce (fn [_ [ns vars]]
                        (test-ns ns vars fail-fast?)
                        (when (and fail-fast? (current-run-failed?))
                          (reduced nil)))
                      nil
                      (keep only-tests corpus)))
      (assoc @current-report :elapsed-time @elapsed-time))))

(defn- with-test-ns-hook-namespaces
  "Augment `corpus` (a map of namespace -> test vars) with namespaces matching
  `ns-query` that define a `test-ns-hook` but contribute no vars of their own.
  `clojure.test` runs such a namespace's tests entirely through the hook, so
  without this they would be silently skipped (reported as \"No assertions\").
  `:has-tests?` is cleared because a hook-only namespace has no test vars and
  would otherwise be filtered out. `ns-query` may name namespaces by symbol,
  while the keys of `corpus` are namespace objects."
  [corpus ns-query]
  (let [already-tested (set (keys corpus))]
    (into corpus
          (comp (map the-ns)
                (remove already-tested)
                (filter #(ns-resolve % 'test-ns-hook))
                (map (fn [ns] [ns nil])))
          (query/namespaces (assoc ns-query :has-tests? false)))))

(defn run-var-query
  "Run the tests found by `var-query` (see `orchard.query/vars`) and return
  the report. Only test vars are run, as if `:test?` was set. Unless it picks
  vars with `:exactly`, namespaces matching its `:ns-query` that run their
  tests through a `test-ns-hook` are included.

  `opts` may contain:

  `:fail-fast?` Stop the run after the first failing or erring test var.
  `:on-event` A function called with progress events while the tests run
  (see the namespace docstring)."
  ([var-query]
   (run-var-query var-query {}))
  ([var-query opts]
   (let [corpus (group-by (comp :ns meta)
                          (query/vars var-query))]
     (run-corpus (cond-> corpus
                   (not (:exactly var-query))
                   (with-test-ns-hook-namespaces (:ns-query var-query)))
                 opts))))

(defn run-namespaces
  "Run the tests in map `m`, whose keys are namespace symbols and values are
  the var symbols to test in that namespace (or `nil` to run all its tests),
  and return the report. `opts` is as for `run-var-query`."
  ([m]
   (run-namespaces m {}))
  ([m opts]
   (run-corpus (mapv (fn [[ns vars]]
                       (let [ns (the-ns ns)]
                         [ns (if vars
                               ;; Results filed under the fallback name have
                               ;; no var to run again.
                               (keep (partial ns-resolve ns)
                                     (remove #{fallback-var-name} vars))
                               (vals (ns-interns ns)))]))
                     m)
               opts)))
