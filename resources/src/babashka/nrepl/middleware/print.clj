(ns nrepl.middleware.print
  "Support for configurable printing. See the docstring of `wrap-print` and the
  Pretty Printing section of the Middleware documentation for more information."
  {:author "Michael Griffiths"
   :added  "0.6"}
  (:refer-clojure :exclude [print])
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc]
   [nrepl.transport :as transport])
  (:import
   (clojure.lang RT)
   (java.io OutputStreamWriter PrintWriter StringWriter Writer)
   (nrepl.out CallbackBufferedOutputStream QuotaBoundWriter QuotaExceeded)
   #_(nrepl.transport Transport) ;; BB-PATCH a sci protocol is not a class
))

(def ^:dynamic *print-fn*
  "Function to use for printing. Takes two arguments: `value`, the value to print,
  and `writer`, the `java.io.PrintWriter` to print on.

  Defaults to the equivalent of `clojure.core/pr`."
  #_@#'clojure.core/pr-on ;; BB-PATCH pr-on is private to clojure.core and absent in sci
(fn [x w] (binding [*out* w] (pr x)))) ;; Private in clojure.core

(def ^:dynamic *stream?*
  "If logical true, the result of printing each value will be streamed to the
  client over one or more messages. Defaults to false."
  false)

(def ^:dynamic *buffer-size*
  "The size of the buffer to use when streaming results. Defaults to 1024."
  1024)

(def ^:dynamic *quota*
  "A hard limit on the number of bytes printed for each value. Defaults to nil. No
  limit will be used if not set."
  nil)

(defn- bound-configuration
  "Returns a map, suitable for merging into responses handled by this middleware,
  of the currently-bound dynamic vars used for configuration."
  []
  {::print-fn *print-fn*
   ::stream? *stream?*
   ::buffer-size *buffer-size*
   ::quota *quota*})

(def configuration-keys
  [::print-fn ::stream? ::buffer-size ::quota ::keys])

(defn with-quota-bound-writer
  "Returns a `java.io.Writer` that wraps `writer` and throws `QuotaExceeded` once
  it has written more than `quota` bytes."
  ^Writer
  [^Writer writer quota]
  (if quota
    (QuotaBoundWriter. writer quota)
    writer))

(defn replying-PrintWriter
  "Returns a `java.io.PrintWriter` suitable for binding as `*out*` or `*err*`. All
  of the content written to that `PrintWriter` will be sent as messages on the
  transport of `msg`, keyed by `key`."
  ^java.io.PrintWriter
  [key msg {:keys [::buffer-size ::quota]}]
  (-> (CallbackBufferedOutputStream. #(transport/respond-to msg key %)
                                     (or buffer-size 1024))
      (OutputStreamWriter.)
      (with-quota-bound-writer quota)
      (PrintWriter. true)))

(defn- send-streamed
  [{:keys [transport] :as msg}
   resp
   {:keys [::print-fn ::keys] :as opts}]
  ;; Iterator is used instead of reduce for cleaner stacktrace if an exception
  ;; gets thrown during printing.
  (let [it (RT/iter keys)]
    (while (.hasNext it)
      (let [key (.next it)
            value (get resp key)]
        (try (with-open [writer (replying-PrintWriter key msg opts)]
               (print-fn value writer))
             (catch QuotaExceeded _
               (transport/respond-to msg :status ::truncated))))))
  (transport/send transport (apply dissoc resp keys)))

(defn- send-nonstreamed
  [{:keys [transport]}
   resp
   {:keys [::print-fn ::quota ::keys]}]
  ;; Iterator is used instead of reduce for cleaner stacktrace if an exception
  ;; gets thrown during printing.
  (let [it (RT/iter keys)]
    (loop [resp resp]
      (if (.hasNext it)
        (let [key (.next it)
              value (get resp key)
              writer (with-quota-bound-writer (StringWriter.) quota)
              truncated? (volatile! false)]
          (try (print-fn value writer)
               (catch QuotaExceeded _
                 (vreset! truncated? true)))
          (recur (cond-> (assoc resp key (str writer))
                   @truncated? (update ::truncated-keys (fnil conj []) key))))

        (transport/send transport (cond-> resp
                                    (::truncated-keys resp)
                                    (update :status #(set (conj % ::truncated)))))))))

(defn- printing-transport
  [{:keys [transport] :as msg}]
  (reify transport/Transport
    (recv [_this]
      (transport/recv transport))
    (recv [_this timeout]
      (transport/recv transport timeout))
    (send [this resp]
      (let [resp-pr (::print-fn resp)
            ;; Request config has priority over response config, but if the
            ;; request didn't have explicit ::print set, prefer ::print-fn from
            ;; the response.
            opts (cond-> (merge (bound-configuration) resp msg)
                   (and resp-pr (nil? (::print msg))) (assoc ::print-fn resp-pr))
            resp (apply dissoc resp configuration-keys)]
        (if (::stream? opts)
          (send-streamed msg resp opts)
          (send-nonstreamed msg resp opts)))
      this)))

(defn- resolve-print
  [{:keys [::print] :as msg}]
  (when-let [var-sym (some-> print (symbol))]
    (let [print-var (misc/requiring-resolve var-sym)]
      (when-not print-var
        (transport/respond-to msg {::error (str "Couldn't resolve var " var-sym)
                                   :status ::error}))
      print-var)))

(defn- booleanize-bencode-val [m key]
  (if (contains? m key)
    ;; As a convention, empty list is treated as logical false.
    (update m key #(if (= % []) false (boolean %)))
    m))

(defn wrap-print
  "Middleware that provides printing functionality to other middlewares.

  Returns a handler which transforms any slots specified by
  `:nrepl.middleware.print/keys` in messages sent via the request's transport to
  strings using the provided printing function and options.

  Supports the following options:

  * `::print` – a fully-qualified symbol naming a var whose function to use for
  printing. Must point to a function with signature [value writer options].

  * `::options` – a map of options to pass to the printing function. Defaults to
  `nil`.

  * `::print-fn` – the function to use for printing. In requests, will be
  resolved from the above two options (if provided). Defaults to the equivalent
  of `clojure.core/pr`. Must have signature [writer options].

  * `::stream?` – if logical true, the result of printing each value will be
  streamed to the client over one or more messages.

  * `::buffer-size` – the size of the buffer to use when streaming results.
  Defaults to 1024.

  * `::quota` – a hard limit on the number of bytes printed for each value.

  * `::keys` – a seq of the keys in the response whose values should be printed.

  The options may be specified in either the request or the responses sent on
  its transport. If any options are specified in both, those in the request will
  be preferred."
  [handler]
  (fn [{:keys [::options] :as msg}]
    (let [print-var (resolve-print msg)
          print-fn (if print-var
                     (fn [value writer]
                       (print-var value writer options))
                     (misc/resolve-in-session msg *print-fn*))
          msg (-> msg
                  (assoc ::print-fn print-fn)
                  (booleanize-bencode-val ::stream?))]
      (handler (assoc msg :transport (printing-transport msg))))))

(set-descriptor! #'wrap-print {:requires #{"clone"}
                               :expects #{}
                               :handles {}
                               :session-dynvars #{#'*print-fn* #'*stream?*
                                                  #'*buffer-size* #'*quota*}})

(def wrap-print-optional-arguments
  {"nrepl.middleware.print/print" "A fully-qualified symbol naming a var whose function to use for printing. Must point to a function with signature [value writer options]."
   "nrepl.middleware.print/options" "A map of options to pass to the printing function. Defaults to `nil`."
   "nrepl.middleware.print/stream?" "If logical true, the result of printing each value will be streamed to the client over one or more messages."
   "nrepl.middleware.print/buffer-size" "The size of the buffer to use when streaming results. Defaults to 1024."
   "nrepl.middleware.print/quota" "A hard limit on the number of bytes printed for each value."
   "nrepl.middleware.print/keys" "A seq of the keys in the response whose values should be printed."})
