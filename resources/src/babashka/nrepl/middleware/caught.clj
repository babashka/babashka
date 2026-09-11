(ns nrepl.middleware.caught
  "Support for a hook for conveying errors interactively, akin to the `:caught`
  option of `clojure.main/repl`. See the docstring of `wrap-caught` and the
  Evaluation Errors section of the Middleware documentation for more
  information."
  {:author "Michael Griffiths"
   :added  "0.6"}
  (:require
   [clojure.main]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.print :as print]
   [nrepl.misc :as misc]
   [nrepl.transport :as transport])
  (:import
   #_(nrepl.transport Transport) ;; BB-PATCH a sci protocol is not a class
))

(def ^:dynamic *caught-fn*
  "Function to use to convey interactive errors (generally by printing to
  `*err*`). Takes one argument, a `java.lang.Throwable`."
  clojure.main/repl-caught)

(defn- bound-configuration
  []
  {::caught-fn *caught-fn*})

(def configuration-keys
  [::caught-fn ::print?])

(defn- resolve-caught [msg]
  (when-let [var-sym (some-> (::caught msg) (symbol))]
    (let [caught-var (misc/requiring-resolve var-sym)]
      (when-not caught-var
        (transport/respond-to msg {::error (str "Couldn't resolve var " var-sym)
                                   :status ::error}))
      caught-var)))

(defn- caught-transport
  [{:keys [transport] :as msg} opts]
  (reify transport/Transport
    (recv [_this]
      (transport/recv transport))
    (recv [_this timeout]
      (transport/recv transport timeout))
    (send [this {:keys [::throwable] :as resp}]
      (let [{:keys [::caught-fn ::print?]} (-> (merge msg (bound-configuration) resp opts)
                                               (select-keys configuration-keys))]
        (when throwable
          (caught-fn throwable))
        (transport/send transport (cond-> (apply dissoc resp configuration-keys)
                                    (and throwable print?)
                                    (update ::print/keys (fnil conj []) ::throwable)
                                    (not print?)
                                    (dissoc ::throwable))))
      this)))

(defn wrap-caught
  "Middleware that provides a hook for any `java.lang.Throwable` that should be
  conveyed interactively (generally by printing to `*err*`).

  Returns a handler which calls said hook on the `::caught/throwable` slot of
  messages sent via the request's transport.

  Supports the following options:

  * `::caught` – a fully-qualified symbol naming a var whose function to use to
  convey interactive errors. Must point to a function that takes a
  `java.lang.Throwable` as its sole argument.

  * `::caught-fn` – the function to use to convey interactive errors. Will be
  resolved from the above option if provided. Defaults to
  `clojure.main/repl-caught`. Must take a `java.lang.Throwable` as its sole
  argument.

  * `::print?` – if logical true, the printed value of any interactive errors
  will be returned in the response (otherwise they will be elided). Delegates to
  `nrepl.middleware.print` to perform the printing. Defaults to false.

  The options may be specified in either the request or the responses sent on
  its transport. If any options are specified in both, those in the request will
  be preferred."
  [handler]
  (fn [msg]
    (let [caught-var (resolve-caught msg)
          msg (assoc msg ::caught-fn (or caught-var
                                         (misc/resolve-in-session msg *caught-fn*)))
          opts (cond-> (select-keys msg configuration-keys)
                 ;; no caught-fn provided in the request, so defer to the response
                 (nil? caught-var)
                 (dissoc ::caught-fn)
                 ;; in bencode empty list is logical false
                 (contains? msg ::print?)
                 (update ::print? #(if (= [] %) false (boolean %))))]
      (handler (assoc msg :transport (caught-transport msg opts))))))

(set-descriptor! #'wrap-caught {:requires #{"clone" #'print/wrap-print}
                                :expects #{}
                                :handles {}
                                :session-dynvars #{#'*caught-fn*}})

(def wrap-caught-optional-arguments
  {"nrepl.middleware.caught/caught" "A fully-qualified symbol naming a var whose function to use to convey interactive errors. Must point to a function that takes a `java.lang.Throwable` as its sole argument."
   "nrepl.middleware.caught/print?" "If logical true, the printed value of any interactive errors will be returned in the response (otherwise they will be elided). Delegates to `nrepl.middleware.print` to perform the printing. Defaults to false."})
