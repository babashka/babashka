(ns cider.nrepl.middleware.inspect
  (:require
   [cider.nrepl.middleware.util :refer [respond-to]]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling
    :refer [eval-interceptor-transport with-op-aliases with-safe-transport]]
   [cider.nrepl.pprint :as pprint]
   [nrepl.middleware.print :as print]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.inspect :as inspect]))

(defn- update-inspector [inspector f & args]
  ;; Ensure that there is valid inspector value before passing it to
  ;; orchard.inspect functions.
  (let [inspector (if (map? inspector)
                    inspector (inspect/start nil))]
    (apply f inspector args)))

(defn swap-inspector!
  [{:keys [session]} f & args]
  (-> session
      (alter-meta! update ::inspector #(apply update-inspector % f args))
      (get ::inspector)))

(defn- inspector-response
  ([msg inspector]
   (inspector-response msg inspector {:status :done}))

  ([msg inspector extra-response-data]
   (let [data (binding [*print-length* nil, *print-level* nil]
                {:value (pr-str (seq (:rendered inspector)))
                 :path (pr-str (seq (:path inspector)))})]
     (response-for msg data extra-response-data))))

(defn- booleanize [m keys]
  (reduce (fn [m k] (cond-> m
                      (contains? m k) (update k = "true")))
          m keys))

(defn- msg->inspector-config [msg]
  (as-> msg config
    (select-keys config [:page-size :sort-maps :max-atom-length :max-coll-size
                         :max-value-length :max-nested-depth :pretty-print
                         :only-diff])
    (booleanize config [:pretty-print :sort-maps :only-diff])
    (let [pov-ns (when (= (:tidy-qualified-keywords msg) "true")
                   (some-> msg :ns symbol))]
      (cond-> config
        pov-ns (assoc :pov-ns pov-ns)))))

(defn inspect-reply* [{:keys [view-mode] :as msg} value]
  (let [config (msg->inspector-config msg)
        ;; Setting view mode from the start is only needed by cider-profile.
        inspector (swap-inspector! msg #(cond-> (inspect/start (merge % config) value)
                                          view-mode (inspect/set-view-mode view-mode)))]
    ;; By using 3-arity `inspector-response` we ensure that the default
    ;; `{:status :done}` is not sent with this message, as the underlying
    ;; eval will send it on its own.
    (inspector-response msg inspector {})))

(defn inspect-reply
  [msg eval-response]
  (let [value (cljs/response-value msg eval-response)]
    (transport/send (:transport msg)
                    (inspect-reply* msg value))))

(defn handle-eval-inspect [handler msg]
  ;; Let eval command be executed but intercept its :value with `inspect-reply`.
  (handler (assoc msg :transport (eval-interceptor-transport
                                  msg inspect-reply :inspect-eval-error))))

(defn pop-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/up)))

(defn push-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/down (:idx msg))))

(defn next-sibling-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/next-sibling)))

(defn previous-sibling-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/previous-sibling)))

(defn next-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/next-page)))

(defn prev-page-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/prev-page)))

(defn refresh-reply [msg]
  (let [overrides (msg->inspector-config msg)]
    (inspector-response msg (swap-inspector! msg #(inspect/refresh % overrides)))))

(defn toggle-pretty-print-reply [msg]
  (inspector-response msg (swap-inspector! msg #(-> (update % :pretty-print not) (inspect/inspect-render)))))

(defn toggle-view-mode-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/toggle-view-mode)))

(defn- display-analytics-reply [msg]
  (inspector-response msg (swap-inspector! msg inspect/display-analytics)))

(defn ^:deprecated set-page-size-reply [msg] (refresh-reply msg))
(defn ^:deprecated set-max-atom-length-reply [msg] (refresh-reply msg))
(defn ^:deprecated set-max-coll-size-reply [msg] (refresh-reply msg))
(defn ^:deprecated set-max-nested-depth-reply [msg] (refresh-reply msg))

(defn clear-reply [msg]
  (inspector-response msg (swap-inspector! msg (constantly (inspect/start nil)))))

(defn def-current-value [msg]
  (inspector-response msg (swap-inspector! msg inspect/def-current-value (symbol (:ns msg)) (:var-name msg))))

(defn print-current-value-reply [{:keys [::print/print-fn session] :as msg}]
  (let [inspector (-> session meta ::inspector)]
    (with-open [writer (print/replying-PrintWriter :value msg msg)]
      (binding [*print-length* (or *print-length* 100)
                *print-level* (or *print-level* 20)]
        ((or print-fn pprint/pprint) (:value inspector) writer)
        (.flush writer)))
    (respond-to msg :status :done)))

(defn tap-current-value [msg]
  (inspector-response msg (swap-inspector! msg inspect/tap-current-value)))

(defn tap-indexed [msg]
  (inspector-response msg (swap-inspector! msg inspect/tap-indexed (:idx msg))))

(defn handle-inspect [handler {:keys [op inspect] :as msg}]
  (cond
    (and (= op "eval") inspect)
    (handle-eval-inspect handler msg)

    ;; This is outside of `with-safe-transport` because it streams the pretty
    ;; printed result to the client in multiple messages. It does NOT return any
    ;; value, which is expected by `with-safe-transport`.
    (#{"cider/inspect-print-current-value" "inspect-print-current-value"} op)
    (print-current-value-reply msg)

    :else
    (with-safe-transport handler msg
      (with-op-aliases {"cider/inspect-pop" pop-reply
                        "cider/inspect-push" push-reply
                        "cider/inspect-next-sibling" next-sibling-reply
                        "cider/inspect-previous-sibling" previous-sibling-reply
                        "cider/inspect-next-page" next-page-reply
                        "cider/inspect-prev-page" prev-page-reply
                        "cider/inspect-refresh" refresh-reply
                        "cider/inspect-toggle-pretty-print" toggle-pretty-print-reply
                        "cider/inspect-toggle-view-mode" toggle-view-mode-reply
                        "cider/inspect-display-analytics" display-analytics-reply
                        "cider/inspect-set-page-size" refresh-reply
                        "cider/inspect-set-max-atom-length" refresh-reply
                        "cider/inspect-set-max-coll-size" refresh-reply
                        "cider/inspect-set-max-nested-depth" refresh-reply
                        "cider/inspect-clear" clear-reply
                        "cider/inspect-def-current-value" def-current-value
                        "cider/inspect-tap-current-value" tap-current-value
                        "cider/inspect-tap-indexed" tap-indexed}))))
