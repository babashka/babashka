(ns cider.nrepl.middleware.test
  "Test execution, reporting, and inspection"
  {:author "Jeff Valk"}
  (:require
   #_[cider.nrepl.middleware.test.cljs :as test-cljs] ;; BB-PATCH no ClojureScript
   [cider.nrepl.middleware.test.extensions]
   [cider.nrepl.middleware.util :as util :refer [respond-to]]
   #_[cider.nrepl.middleware.util.cljs :as cljs] ;; BB-PATCH no ClojureScript
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [clojure.walk :as walk]
   [nrepl.middleware.interruptible-eval :as ie]
   [orchard.misc :as misc]
   [orchard.stacktrace :as stacktrace]
   [orchard.test]
   [sci.core :as sci])) ;; BB-PATCH sci's stack frames for test-stacktrace

;;; ## Overview
;;
;; This middleware provides test execution and reporting support for the
;; `clojure.test` machinery, on top of `orchard.test`, which does the running
;; and collects the results. Results are indexed by namespace, var, and
;; assertion index within the var. This enables individual test
;; failures/errors to be inspected on demand, including cause and stacktrace
;; detail for errors.
;;
;; Non-passing tests may be re-run for any namespace using the `retest` op.
;; Whenever a var's tests are run, their previous results are overwritten, so
;; the session always holds the most recent test result for each var.
;;
;; When a request sets `stream` to "true", progress is sent while the tests
;; run, as `test-event` messages on the request's id, ahead of the final
;; report.

(def ^:dynamic *test-error-handler*
  "A function you can override via `binding`, or safely via `alter-var-root`.
  On test `:error`s, the related Throwable be invoked as the sole argument
  passed to this var.
  For example, you can use this to add an additional `println`,
  for pretty-printing Spec failures. Remember to `flush` if doing so."
  identity)

;;; ## Deprecated
;;
;; The runner moved to `orchard.test`. These keep code that used it from here
;; working, including `defmethod`s on `report`, which is the same multimethod.

(def ^{:deprecated "0.63.0"} current-report orchard.test/current-report)
(def ^{:deprecated "0.63.0"} report orchard.test/report)
(def ^{:deprecated "0.63.0"} stack-frame orchard.test/stack-frame)
(def ^{:deprecated "0.63.0"} test-result orchard.test/test-result)
(def ^{:deprecated "0.63.0"} test-var orchard.test/test-var)
(def ^{:deprecated "0.63.0"} report-fixture-error orchard.test/report-fixture-error)

(defn ^{:deprecated "0.63.0"} test-var-query
  "Use `orchard.test/run-var-query` instead."
  ([var-query]
   (orchard.test/run-var-query var-query))
  ([var-query fail-fast?]
   (orchard.test/run-var-query var-query {:fail-fast? fail-fast?})))

(defn ^{:deprecated "0.63.0"} test-nss
  "Use `orchard.test/run-namespaces` instead."
  ([m]
   (orchard.test/run-namespaces m))
  ([m fail-fast?]
   (orchard.test/run-namespaces m {:fail-fast? fail-fast?})))

(defn- stringify-messages
  "`clojure.test` allows any object as an assertion message; send them as strings."
  [report]
  (walk/postwalk (fn [x]
                   (if (and (map? x) (contains? x :message))
                     (update x :message str)
                     x))
                 report))

(defn- non-passing [results]
  (filterv (comp #{:fail :error} :type) results))

(defn- event-responder
  "Return an `orchard.test` `:on-event` function that streams the events to
  `msg`'s client. Passing assertions are left out: the counts are in the
  summary and the final report carries every result."
  [msg]
  (fn [event]
    (respond-to msg {:test-event
                     (util/transform-value
                      (stringify-messages
                       (cond-> event
                         (:results event) (update :results non-passing))))})))

(defn- run-opts [{:keys [fail-fast stream] :as msg}]
  (cond-> {:fail-fast? (= "true" fail-fast)}
    (= "true" stream) (assoc :on-event (event-responder msg))))

(defn- run-tests
  "Run the tests with `run-fn`, given the options for `msg`, and return the
  report ready to be sent."
  [msg run-fn]
  (binding [orchard.test/*test-error-handler* *test-error-handler*]
    (stringify-messages (run-fn (run-opts msg)))))

;;; ## Middleware

(def results
  "An atom holding results of test runs, indexed by namespace. This is used to
  reference exception objects from erring tests, and to rerun tests (by
  namespace) that did not pass previously. The var itself will be bound from
  the nREPL session."
  (atom {}))

(defn handle-test-var-query-op
  [{:keys [var-query session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (with-bindings (assoc @session #'ie/*msg* msg)
              (try
                (let [var-query (-> var-query
                                    (assoc-in [:ns-query :has-tests?] true)
                                    (assoc :test? true)
                                    (util.coerce/var-query))
                      report (run-tests msg #(orchard.test/run-var-query var-query %))]
                  (reset! results (:results report))
                  (respond-to msg (util/transform-value report)))
                (catch #_clojure.lang.ExceptionInfo Throwable e ;; BB-PATCH a throw ends the session thread, reply instead
                  (let [d (ex-data e)]
                    (if (::util.coerce/id d)
                      (case (::util.coerce/id d)
                        :namespace-not-found (respond-to msg :status :namespace-not-found))
                      #_(throw e) ;; BB-PATCH a throw ends the session thread, reply instead
                      (respond-to msg :status :error :err (str e))))))))
          (fn []
            (respond-to msg :status :done)))))

(defn handle-test-op
  [{:keys [ns tests include exclude] :as msg}]
  (handle-test-var-query-op
   (merge msg {:var-query {:ns-query {:exactly [ns]}
                           :include-meta-key include
                           :exclude-meta-key exclude
                           :exactly (map #(str ns "/" %) tests)}})))

(defn handle-test-all-op
  [{:keys [load? include exclude] :as msg}]
  (handle-test-var-query-op
   (merge msg {:var-query {:ns-query {:project? true
                                      :load-project-ns? load?}
                           :include-meta-key include
                           :exclude-meta-key exclude}})))

(defn handle-retest-op
  [{:keys [session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (with-bindings (assoc @session #'ie/*msg* msg)
              (let [nss (reduce (fn [ret [ns tests]]
                                  (let [problems (filter (comp #{:fail :error} :type)
                                                         (mapcat val tests))
                                        vars (distinct (map :var problems))]
                                    (if (seq vars)
                                      (assoc ret ns vars)
                                      ret)))
                                {} @results)
                    report (run-tests msg #(orchard.test/run-namespaces nss %))]
                (reset! results (:results report))
                (respond-to msg (util/transform-value report)))))
          (fn []
            (respond-to msg :status :done)))))

(defn handle-stacktrace-op
  [{:keys [ns var index session id] :as msg}]
  (let [{:keys [exec]} (meta session)]
    (exec id
          (fn []
            (with-bindings (assoc @session #'ie/*msg* msg)
              (let [[ns var] (map misc/as-sym [ns var])]
                (if-let [e (get-in @results [ns var index :error])]
                  #_(doseq [cause (stacktrace/analyze e)]
                    (respond-to msg cause)) ;; BB-PATCH without sci frames, start the trace at the test
                  (let [test-frame {:name (str ns "/" var)
                                    :ns (str ns) :fn (str var)
                                    :file (:file (meta (ns-resolve ns var)))
                                    :line (get-in @results [ns var index :line])
                                    :flags #{:clj :project}}
                        [first-cause & more] (stacktrace/analyze e)
                        sci-frames? (seq (try (sci/stacktrace e) (catch Throwable _ nil)))]
                    (doseq [cause (cons (cond-> first-cause
                                          (not sci-frames?) (update :stacktrace #(into [test-frame] %)))
                                        more)]
                      (respond-to msg cause)))
                  (respond-to msg :status :no-error)))))
          (fn []
            (respond-to msg :status :done)))))

(defn handle-test [handler msg & _configuration]
  (if #_(and (cljs/grab-cljs-env msg)
           (test-cljs/handle-test-cljs handler msg)) ;; BB-PATCH no ClojureScript
      false
    ;; Handled by the ClojureScript path.
    nil
    (case (:op msg)
      ;; (NOTE: deprecated)
      ("cider/test" "test")         (handle-test-op msg)
      ;; (NOTE: deprecated)
      ("cider/test-all" "test-all") (handle-test-all-op msg)

      ("cider/test-var-query" "test-var-query")   (handle-test-var-query-op msg)
      ("cider/test-stacktrace" "test-stacktrace") (handle-stacktrace-op msg)
      ("cider/retest" "retest")                   (handle-retest-op msg)
      (handler msg))))
