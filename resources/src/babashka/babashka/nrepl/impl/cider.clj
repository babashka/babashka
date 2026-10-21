(ns babashka.nrepl.impl.cider
  "Answers CIDER's test, stacktrace, inspector and version ops with
  cider-nrepl's replies, so CIDER needs no middleware on the classpath."
  {:no-doc true}
  (:require
   [cider.nrepl.middleware.test :as test-mw]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :as caught]
   [nrepl.middleware.print :as print]
   [nrepl.middleware.session :as session]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as t]
   [orchard.inspect :as inspect]
   [orchard.stacktrace :as stacktrace]))

(def version
  "The cider-nrepl version whose protocol these ops speak."
  (let [s "0.63.1"
        [major minor incremental] (map parse-long (str/split s #"\."))]
    {:major major :minor minor :incremental incremental :qualifier "" :version-string s}))

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

(defn- analyze-last-stacktrace-reply
  "Sends each cause of the session's `*e`, or `no-error` if unset, followed by `done`."
  [msg]
  (if-let [e (get @(:session msg) #'*e)]
    (doseq [cause (stacktrace/analyze e)]
      (t/respond-to msg cause))
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
      ("test" "test-var-query" "test-all" "retest" "test-stacktrace") (test-mw/handle-test h msg)
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
                                  "test-all" {:doc "Runs the tests of the project's namespaces." :requires {} :optional {"load?" "Loads the project's namespaces first."} :returns {}}
                                  "retest" {:doc "Reruns the tests that failed or errored last time." :requires {} :optional {} :returns {}}
                                  "test-stacktrace" {:doc "The causes of an erring test's exception." :requires {"ns" "" "var" "" "index" ""} :optional {} :returns {}}
                                  "analyze-last-stacktrace" {:doc "Returns the causes of the session's last exception." :requires {} :optional {} :returns {}}
                                  "stacktrace" {:doc "Alias for analyze-last-stacktrace." :requires {} :optional {} :returns {}}
                                  "cider-version" {:doc "The cider-nrepl version these ops speak." :requires {} :optional {} :returns {"cider-version" ""}}
                                  "inspect-print-current-value" {:doc "Prints the inspected value." :requires {} :optional {} :returns {}}}
                                 (map (fn [op] [op {:doc "Inspector operation, see cider-nrepl."
                                                    :requires {} :optional {}
                                                    :returns {"value" "The rendered inspector." "path" "The path to the inspected value."}}]))
                                 (keys inspect-ops))})
