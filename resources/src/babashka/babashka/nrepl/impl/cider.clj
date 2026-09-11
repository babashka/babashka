(ns babashka.nrepl.impl.cider
  "The cider-nrepl ops babashka answers itself: the test runner and its
  stacktraces, and the version CIDER asks for. Same ops and replies as
  cider-nrepl, so CIDER needs no middleware on the classpath."
  {:no-doc true}
  (:require
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [clojure.test :as test]
   [clojure.walk :as walk]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :as caught]
   [nrepl.middleware.print :as print]
   [nrepl.middleware.session :as session]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]
   [orchard.inspect :as inspect]
   [sci.core :as sci]))

(def version
  "The cider-nrepl version whose protocol these ops speak."
  (let [s "0.62.2"
        [major minor incremental] (map parse-long (str/split s #"\."))]
    {:major major :minor minor :incremental incremental :qualifier "" :version-string s}))

;;;; Stacktraces, in the shape CIDER's stacktrace buffer renders

(defn- sci-frame
  "A sci frame; nREPL's and babashka's own are flagged tooling, which CIDER hides."
  [{:keys [ns name file line column]}]
  (let [ns (str ns)]
    {:name (str ns "/" (or name "fn"))
     :file (or file "NO_SOURCE_FILE")
     :line (or line 0)
     :column column
     :ns ns
     :fn (str name)
     :flags (if (or (str/starts-with? ns "nrepl.") (str/starts-with? ns "babashka.")
                    (= "clojure.core" ns))
              #{:clj :tooling}
              #{:clj :project})}))

(defn- java-frame
  "A JVM frame; sci's own frames are flagged tooling, which CIDER hides."
  [[class method file line]]
  (let [class (str class)]
    {:name (str class "/" method)
     :class class
     :method (str method)
     :file (str file)
     :line (or line 0)
     :flags (if (or (str/starts-with? class "sci.") (str/starts-with? class "babashka."))
              #{:java :tooling}
              #{:java})}))

(defn analyze
  "Causes of `ex`, innermost last, each with its stack: sci's frames when
  there are any, the JVM's otherwise. A sci error is analyzed as the
  exception it wraps, with the wrapper's frames."
  [^Throwable ex]
  (let [sci-trace (map sci-frame (try (sci/stacktrace ex) (catch Throwable _ nil)))
        ex (if (= :sci/error (:type (ex-data ex))) (or (ex-cause ex) ex) ex)]
    (loop [causes [] e ex]
      (if e
        (let [data (ex-data e)
              trace (if (and (seq sci-trace) (identical? e ex))
                      sci-trace
                      (map java-frame (:trace (Throwable->map e))))]
          (recur (conj causes (cond-> {:class (.getName (class e))
                                       :message (or (ex-message e) "")
                                       :stacktrace (vec trace)}
                                (:type data) (assoc :phase (some-> (:phase data) name))
                                (seq (dissoc data :type :line :column :file :phase :sci.impl/callstack))
                                (assoc :data (with-out-str (pp/pprint (dissoc data :sci.impl/callstack))))))
                 (.getCause e)))
        causes))))

;;;; The test runner: clojure.test with `report` rebound, results collected
;;;; the way cider-nrepl collects them

(def ^:private current-report (atom nil))

(defn- report-reset! []
  (reset! current-report {:summary {:ns 0 :var 0 :test 0 :pass 0 :fail 0 :error 0}
                          :results {}
                          :testing-ns nil}))

(defn- print-object [object]
  (str/replace (with-out-str (pp/pprint object)) #"\n\n+$" "\n"))

(defn- test-result
  "One assertion's result: cider-nrepl's keys, the exception kept under
  `:error` for `test-stacktrace`."
  [ns v {:keys [actual expected fault] t :type :as m}]
  (let [v-name (or (:name (meta v)) ::unknown)
        context (when (seq test/*testing-contexts*) (test/testing-contexts-str))
        index (count (get-in (:results @current-report) [ns v-name]))]
    (merge (dissoc m :expected :actual)
           {:ns ns :var v-name :index index :context context}
           (when (and (#{:fail :error} t) (not fault))
             {:expected (print-object expected)})
           (when (= :fail t)
             {:actual (print-object actual)})
           (when (= :error t)
             (let [e (or (:babashka.impl.clojure.test/sci-error m) actual)
                   frames (try (sci/stacktrace e) (catch Throwable _ nil))
                   in-test (some #(when (and (= (str ns) (str (:ns %)))
                                              (= (str v-name) (str (:name %))))
                                     %)
                                 frames)]
               {:error e
                :line (or (:line in-test) (:line (meta v)))})))))

(defn- final-status [{:keys [type] :as m}]
  (let [ns (ns-name (get m :ns (:testing-ns @current-report)))
        v (last test/*testing-vars*)]
    (swap! current-report
           #(-> %
                (update-in [:summary :test] inc)
                (update-in [:summary type] (fnil inc 0))
                (update-in [:results ns (or (:name (meta v)) ::unknown)]
                           (fnil conj [])
                           (test-result ns v m))))))

(defn- report [{:keys [type] :as m}]
  (case type
    :begin-test-ns (let [ns (ns-name (get m :ns (:testing-ns @current-report)))]
                     (swap! current-report #(-> % (assoc :testing-ns ns) (update-in [:summary :ns] inc))))
    :begin-test-var (swap! current-report update-in [:summary :var] inc)
    (:pass :fail :error) (final-status m)
    nil))

(defn- test-var [v]
  (when-let [t (:test (meta v))]
    (binding [test/*testing-vars* (conj test/*testing-vars* v)]
      (test/do-report {:type :begin-test-var :var v})
      (test/inc-report-counter :test)
      (let [result (try (t) ::ok (catch Throwable e e))]
        (when-not (= ::ok result)
          (test/do-report {:type :error :fault true :expected nil :actual result
                           :message "Uncaught exception, not in assertion"}))))))

(defn- run-failed? []
  (let [{:keys [fail error]} (:summary @current-report)]
    (or (pos? fail) (pos? error))))

(defn- test-vars [ns vars fail-fast?]
  (let [once-fixture-fn (test/join-fixtures (::test/once-fixtures (meta ns)))
        each-fixture-fn (test/join-fixtures (::test/each-fixtures (meta ns)))]
    (try
      (once-fixture-fn
       (fn []
         (reduce (fn [_ v]
                   (cond-> (each-fixture-fn (fn [] (test-var v)))
                     (and fail-fast? (run-failed?)) reduced))
                 nil
                 vars)))
      (catch Throwable e
        (swap! current-report update-in [:summary :test] dec)
        (report {:type :error :fault true :expected nil :actual e
                 :message "Uncaught exception in test fixture"})))))

(defn- test-ns [ns vars fail-fast?]
  (binding [test/report report
            test/*report-counters* (ref test/*initial-report-counters*)]
    (test/do-report {:type :begin-test-ns :ns ns})
    (if-let [test-hook (ns-resolve ns 'test-ns-hook)]
      (test-hook)
      (test-vars ns vars fail-fast?))
    (test/do-report {:type :end-test-ns :ns ns})))

(defn- run-corpus
  "Runs `corpus`, a map of namespace object to test vars, and returns the
  report."
  [corpus fail-fast?]
  (report-reset!)
  (let [start (System/currentTimeMillis)]
    (reduce (fn [_ [ns vars]]
              (cond-> (test-ns ns vars fail-fast?)
                (and fail-fast? (run-failed?)) reduced))
            nil
            corpus)
    (let [ms (- (System/currentTimeMillis) start)]
      (assoc @current-report :elapsed-time {:ms ms :humanized (str "Completed in " ms " ms")}))))

;;;; Var queries, the subset cider-nrepl sends for tests

(defn- test-var? [v] (boolean (:test (meta v))))

(defn- has-tests? [ns] (some test-var? (vals (ns-interns ns))))

(defn- query-namespaces [{:keys [exactly]}]
  (if exactly
    (map (fn [n] (or (find-ns (symbol n))
                     (throw (ex-info "Namespace not found" {::status :namespace-not-found :namespace n}))))
         exactly)
    (filter has-tests? (all-ns))))

(defn- query-vars
  [{:keys [ns-query exactly include-meta-key exclude-meta-key search]}]
  (let [include (seq (map keyword include-meta-key))
        exclude (seq (map keyword exclude-meta-key))
        search (some-> search re-pattern)]
    (cond->> (if (seq exactly)
               (keep (comp find-var symbol) exactly)
               (mapcat (comp vals ns-interns) (query-namespaces ns-query)))
      true (filter test-var?)
      search (filter #(re-find search (str (:name (meta %)))))
      include (filter #((apply some-fn include) (meta %)))
      exclude (remove #((apply some-fn exclude) (meta %))))))

(defn- corpus [var-query]
  (let [vars (query-vars var-query)
        by-ns (group-by (comp :ns meta) vars)
        hook-only (for [ns (query-namespaces (:ns-query var-query))
                        :when (and (not (contains? by-ns ns)) (ns-resolve ns 'test-ns-hook))]
                    [ns nil])]
    (into by-ns hook-only)))

;;;; The inspector: orchard's engine, the inspector kept on the session

(defn- swap-inspector! [{:keys [session]} f & args]
  (-> session
      (alter-meta! update ::inspector
                   (fn [inspector]
                     (apply f (if (map? inspector) inspector (inspect/start nil)) args)))
      (get ::inspector)))

(defn- inspector-response
  ([msg inspector] (inspector-response msg inspector {:status :done}))
  ([msg inspector extra]
   (binding [*print-length* nil *print-level* nil]
     (response-for msg {:value (pr-str (seq (:rendered inspector)))
                        :path (pr-str (seq (:path inspector)))}
                   extra))))

(defn- inspector-config [msg]
  (let [config (select-keys msg [:page-size :sort-maps :max-atom-length :max-coll-size
                                 :max-value-length :max-nested-depth :pretty-print :only-diff])
        config (reduce (fn [m k] (cond-> m (contains? m k) (update k = "true")))
                       config [:pretty-print :sort-maps :only-diff])]
    (cond-> config
      (= "true" (:tidy-qualified-keywords msg)) (assoc :pov-ns (some-> msg :ns symbol)))))

(defn- inspect-value [{:keys [view-mode] :as msg} value]
  (let [config (inspector-config msg)
        inspector (swap-inspector! msg #(cond-> (inspect/start (merge % config) value)
                                          view-mode (inspect/set-view-mode view-mode)))]
    (inspector-response msg inspector {})))

(defn- inspecting-transport
  "For an eval with `:inspect`: the transport that replaces the value reply
  with the inspector's rendering of it, and reports an error as an
  inspector error."
  [{:keys [transport] :as msg}]
  (reify t/Transport
    (recv [_this] (t/recv transport))
    (recv [_this timeout] (t/recv transport timeout))
    (send [this resp]
      (cond (contains? resp :value)
            (t/send transport (inspect-value msg (:value resp)))
            (::caught/throwable resp)
            (t/send transport (-> resp
                                  (update :status (fnil conj #{}) :inspect-eval-error)
                                  (assoc :ex (str (class (::caught/throwable resp))))))
            :else (t/send transport resp))
      this)))

(defn- inspect-reply [msg f & args]
  (try (inspector-response msg (apply swap-inspector! msg f args))
       (catch Throwable e
         (response-for msg :status #{:done :inspect-error} :err (str e)))))

(defn- print-current-value-reply [{:keys [::print/print-fn session] :as msg}]
  (let [inspector (-> session meta ::inspector)]
    (with-open [writer (print/replying-PrintWriter :value msg msg)]
      (binding [*print-length* (or *print-length* 100)
                *print-level* (or *print-level* 20)]
        ((or print-fn pp/pprint) (:value inspector) writer)
        (.flush writer)))
    (t/respond-to msg :status :done)))

(def ^:private inspect-ops
  {"inspect-pop" #(inspect-reply % inspect/up)
   "inspect-push" #(inspect-reply % inspect/down (:idx %))
   "inspect-next-sibling" #(inspect-reply % inspect/next-sibling)
   "inspect-previous-sibling" #(inspect-reply % inspect/previous-sibling)
   "inspect-next-page" #(inspect-reply % inspect/next-page)
   "inspect-prev-page" #(inspect-reply % inspect/prev-page)
   "inspect-refresh" #(inspect-reply % inspect/refresh (inspector-config %))
   "inspect-toggle-pretty-print" #(inspect-reply % (fn [i] (inspect/inspect-render (update i :pretty-print not))))
   "inspect-toggle-view-mode" #(inspect-reply % inspect/toggle-view-mode)
   "inspect-display-analytics" #(inspect-reply % inspect/display-analytics)
   "inspect-set-page-size" #(inspect-reply % inspect/refresh (inspector-config %))
   "inspect-set-max-atom-length" #(inspect-reply % inspect/refresh (inspector-config %))
   "inspect-set-max-coll-size" #(inspect-reply % inspect/refresh (inspector-config %))
   "inspect-set-max-nested-depth" #(inspect-reply % inspect/refresh (inspector-config %))
   "inspect-clear" #(inspect-reply % (constantly (inspect/start nil)))
   "inspect-def-current-value" #(inspect-reply % inspect/def-current-value (symbol (:ns %)) (:var-name %))
   "inspect-tap-current-value" #(inspect-reply % inspect/tap-current-value)
   "inspect-tap-indexed" #(inspect-reply % inspect/tap-indexed (:idx %))})

;;;; The ops

(def ^:private results
  "The last run's results, for `retest` and `test-stacktrace`."
  (atom {}))

(defn- wire
  "A report as bencode takes it: string keys, names for keywords and
  symbols, exceptions as their string, no nil entries (bencode would send
  them as empty lists)."
  [report]
  (walk/postwalk (fn [x]
                   (cond (map? x) (into {} (keep (fn [[k v]] (when (some? v) [(if (keyword? k) (str (symbol k)) (str k)) v]))) x)
                         (keyword? x) (str (symbol x))
                         (symbol? x) (str x)
                         (instance? Throwable x) (str x)
                         :else x))
                 report))

(defn- run-and-reply [msg corpus-fn]
  (let [{:keys [exec]} (meta (:session msg))
        fail-fast? (= "true" (:fail-fast msg))]
    (exec (:id msg)
          (fn []
            (try
              (let [report (run-corpus (corpus-fn) fail-fast?)]
                (reset! results (:results report))
                (t/respond-to msg (wire report)))
              (catch clojure.lang.ExceptionInfo e
                (if (= :namespace-not-found (::status (ex-data e)))
                  (t/respond-to msg :status :namespace-not-found)
                  (throw e)))))
          (fn [] (t/respond-to msg :status :done))
          msg)))

(defn- test-var-query-reply [{:keys [var-query] :as msg}]
  (run-and-reply msg #(corpus (walk/keywordize-keys var-query))))

(defn- test-reply [{:keys [ns tests include exclude] :as msg}]
  (run-and-reply msg #(corpus {:ns-query {:exactly [ns]}
                               :include-meta-key include
                               :exclude-meta-key exclude
                               :exactly (map (fn [t] (str ns "/" t)) tests)})))

(defn- test-all-reply [{:keys [include exclude] :as msg}]
  (run-and-reply msg #(corpus {:ns-query {}
                               :include-meta-key include
                               :exclude-meta-key exclude})))

(defn- retest-reply [msg]
  (run-and-reply msg (fn []
                       (into {} (for [[ns tests] @results
                                      :let [vars (->> (mapcat val tests)
                                                      (filter (comp #{:fail :error} :type))
                                                      (map :var) distinct
                                                      (keep #(ns-resolve ns %)))]
                                      :when (seq vars)]
                                  [(the-ns ns) vars])))))

(defn- test-stacktrace-reply [{:keys [ns var index] :as msg}]
  (let [{:keys [exec]} (meta (:session msg))]
    (exec (:id msg)
          (fn []
            (if-let [e (get-in @results [(symbol ns) (symbol var) index :error])]
              (let [;; the erring test itself as the first frame, when sci has none
                    test-frame {:name (str ns "/" var)
                                :ns ns :fn var
                                :file (:file (meta (ns-resolve (symbol ns) (symbol var))))
                                :line (get-in @results [(symbol ns) (symbol var) index :line])
                                :flags #{:clj :project}}
                    [first-cause & more] (analyze e)
                    sci-frames? (seq (try (sci/stacktrace e) (catch Throwable _ nil)))]
                (doseq [cause (cons (cond-> first-cause
                                      (not sci-frames?) (update :stacktrace #(into [test-frame] %)))
                                    more)]
                  (t/respond-to msg (wire cause))))
              (t/respond-to msg :status :no-error)))
          (fn [] (t/respond-to msg :status :done))
          msg)))

(defn- analyze-last-stacktrace-reply
  "Each cause of the session's `*e`, then done, `no-error` without one."
  [msg]
  (if-let [e (get @(:session msg) #'*e)]
    (doseq [cause (analyze e)]
      (t/respond-to msg (wire cause)))
    (t/respond-to msg :status :no-error))
  (t/respond-to msg :status :done))

(defn wrap-cider
  "Middleware for the cider-nrepl ops babashka implements."
  [h]
  (fn [{:keys [op] :as msg}]
    (let [op (str/replace op #"^cider/" "")]
      (cond
        (and (= "eval" op) (:inspect msg))
        (h (assoc msg :transport (inspecting-transport msg)))
        (= "inspect-print-current-value" op)
        (print-current-value-reply msg)
        (contains? inspect-ops op)
        (t/send (:transport msg) ((inspect-ops op) msg))
        :else
        (case op
      ("cider/test" "test") (test-reply msg)
      ("cider/test-var-query" "test-var-query") (test-var-query-reply msg)
      ("cider/test-all" "test-all") (test-all-reply msg)
      ("cider/retest" "retest") (retest-reply msg)
      ("cider/test-stacktrace" "test-stacktrace") (test-stacktrace-reply msg)
      ("cider/analyze-last-stacktrace" "analyze-last-stacktrace" "cider/stacktrace" "stacktrace")
      (analyze-last-stacktrace-reply msg)
      ("cider/cider-version" "cider-version") (t/respond-to msg :cider-version version :status :done)
      (h msg))))))

(set-descriptor! #'wrap-cider
                 {:requires #{#'session/session #'caught/wrap-caught #'print/wrap-print}
                  :expects #{"eval"}
                  :describe-fn (fn [_] {:cider-version version})
                  :handles (into {"test" {:doc "Runs the tests of a namespace, or the named ones."
                                          :requires {"ns" "The namespace."}
                                          :optional {"tests" "Test var names." "fail-fast" "\"true\" to stop at the first failure."}
                                          :returns {}}
                                  "test-var-query" {:doc "Runs the tests a var query selects." :requires {"var-query" "The query."} :optional {} :returns {}}
                                  "test-all" {:doc "Runs the tests of every loaded namespace." :requires {} :optional {} :returns {}}
                                  "retest" {:doc "Reruns the tests that failed or errored last time." :requires {} :optional {} :returns {}}
                                  "test-stacktrace" {:doc "The causes of an erring test's exception." :requires {"ns" "" "var" "" "index" ""} :optional {} :returns {}}
                                  "analyze-last-stacktrace" {:doc "The causes of the session's last exception." :requires {} :optional {} :returns {}}
                                  "stacktrace" {:doc "The former name of analyze-last-stacktrace." :requires {} :optional {} :returns {}}
                                  "cider-version" {:doc "The cider-nrepl version these ops speak." :requires {} :optional {} :returns {"cider-version" ""}}
                                  "inspect-print-current-value" {:doc "Prints the inspected value." :requires {} :optional {} :returns {}}}
                                 (map (fn [op] [op {:doc "Inspector operation, see cider-nrepl."
                                                    :requires {} :optional {}
                                                    :returns {"value" "The rendered inspector." "path" "The path to the inspected value."}}]))
                                 (keys inspect-ops))})
