(ns cider.nrepl.middleware.util.error-handling
  "Utilities to safely reply to op requests and help deal with the
  errors/exceptions that might arise from doing so."
  (:refer-clojure :exclude [error-handler])
  (:require
   [clojure.main]
   [clojure.set :as set]
   [clojure.stacktrace]
   [clojure.walk :as walk]
   [nrepl.middleware.caught :as caught]
   [nrepl.misc :refer [response-for]]
   [nrepl.transport :as transport]
   [orchard.stacktrace :as stacktrace])
  (:import java.io.InputStream clojure.lang.RT #_(nrepl.transport Transport))) ;; BB-PATCH a sci protocol is not a class

;;; UTILITY FUNCTIONS

(defn base-error-response
  "Takes a CIDER-nREPL message as `msg`, an Exception `ex`, and a
  non-collection vararg of `statuses`.  This will return the standard
  response for CIDER-nREPL sync-op errors that can be rendered by
  CIDER's stacktrace viewer. N.B., statuses such as `:done` and
  `:<op-name>-error` are NOT automatically added"
  [msg ex & statuses]
  (response-for
   msg (merge {:ex (str (class ex))
               :err (with-out-str (clojure.stacktrace/print-cause-trace ex))
               :root-ex (str (class (clojure.main/root-cause ex)))
               :pp-stacktrace (stacktrace/analyze ex)}
              (when statuses {:status (set statuses)}))))

(defn- normalize-status
  "Accepts various representations of an nREPL reply message's status
  and normalizes them to a set. Accepts and normalizes as follows:

  - nil => empty set
  - set => returns the set
  - coll => set representation of coll's items
  - single item (kw, string, int, etc.) => set containing single item"
  [status]
  (cond (nil? status) #{}
        (set? status) status
        (coll? status) (set status)
        :else (set [status])))

(defn- selector
  "Selector used for dispatch on both the `op` and `error` handler
  multimethods. The handlers expect one of the following:

  - map => A map that will form the basis of the nREPL reply
    message.
  - fn (NOT ifn's) => A fn with arity 1 for ops and 2 for errors, must
    return a map that will form the basis of the nREPL reply message.
  - coll => The coll will be turned into a set which is used as the
    reply message's status.
  - kw => Wrapped in a set and used as the reply message's status.
  - ::default => used as the default error handler, which simply adds
    a reasonably named keyword (ie, `:<op>-error`) to the status."
  [input & _]
  (cond (= ::default input) :default
        (fn? input) :function
        (map? input) :inline-reply
        (coll? input)  :status-coll
        (keyword? input) :status-item))

(defn- shallow-bencodable?
  "Returns false if `item`'s type can't be bencoded as defined by the
  algorithm in `nrepl.bencode/write-bencode`. Does not
  examine the elements of a collection to ensure that the enclosed
  elements are also bencodable, and so you probably actually want to
  use `deep-bencodable-or-fail` or write something similar."
  [item]
  (cond
    (instance? (RT/classForName "[B") item) :bytes
    (instance? InputStream item) :input-stream
    (integer? item) :integer
    (string? item)  :string
    (symbol? item)  :named
    (keyword? item) :named
    (map? item)     :map
    (or (nil? item) (coll? item) (.isArray (class item))) :list
    :else false))

(defn- deep-bencodable-or-fail
  "Walks through the data structure provided by `item` and returns
  true if it -- and all nested elements -- are bencodable as defined
  by the algorithm in `nrepl.bencode/write-bencode`. If
  any part of `input` is not bencodable, will throw an
  `IllegalArgumentException`. See `cider-nrepl` bug #332 at
  https://github.com/clojure-emacs/cider-nrepl/issues/332 for further
  details."
  [item]
  (walk/prewalk
   #(if (shallow-bencodable? %)
      %
      (throw (IllegalArgumentException. (format "Can't bencode %s: %s" (.getName (class %)) %))))
   item)
  true) ;; Need to actually return truthy since `nil` is bencodable

;;; ERROR HANDLER - see selector docstring

(defmulti error-handler selector)

(defmethod error-handler :inline-reply [answer msg e]
  (let [reply           (base-error-response msg e)
        terminal-status (set/union #{:done} (normalize-status (:status answer)))]
    (response-for msg (assoc reply :status terminal-status))))

(defmethod error-handler :function [f msg e]
  (error-handler (f msg e) msg e))

(defmethod error-handler :status-coll [statuses msg e]
  (error-handler {:status (set statuses)} msg e))

(defmethod error-handler :status-item [status msg e]
  (error-handler {:status (set [status])} msg e))

(defmethod error-handler :default [_ msg e]
  (error-handler (keyword (str (:op msg) "-error")) msg e))

;;; OP HANDLER - see selector docstring

(defmulti op-handler selector)

(defmethod op-handler :inline-reply
  [answer msg]
  (let [terminal-status (set/union #{:done} (normalize-status (:status answer)))]
    ;; Check the bencodability of `answer` (the current transport can
    ;; only send non-bencodable data if stored under the `:value`
    ;; key). If non-bencodable elements exist, throw an exception.
    (deep-bencodable-or-fail (dissoc answer :value))
    ;; If bencodable, create a terminated reply message.
    (response-for msg (assoc answer :status terminal-status))))

(defmethod op-handler :function [f msg]
  (op-handler (f msg) msg))

(defmethod op-handler :status-coll [statuses msg]
  (op-handler {:status (set statuses)} msg))

(defmethod op-handler :status-item [status msg]
  (op-handler {:status (set [status])} msg))

;;; SAFE TRANSPORT WRAPPER

(defmacro with-safe-transport
  "This will safely handle all the transport calls mapped out in the
  `handle-<middleware>` functions. All checked exceptions will be caught,
  analyzed by the `cider.nrepl.middleware.stacktrace` middleware, and an error
  message will be returned to the client with a stacktrace renderable by the
  default CIDER stacktrace viewer. Takes the default pass-through `handler`
  current `msg` and a list of pairings between op names and actions used to
  process the ops as varargs. Actions can either be expressed as a 2-item vector
  with the head being the op-action and the tail being the error-action, or if
  the default error handler is sufficient, then the op name can be paired
  directly to the op-action.

  Actions can be functions, maps, non-associate collections, and single items
  such as kw's, strings, numbers, etc. The utilization of each type is discussed
  above in the `selector` method.

  The op/action pairings can be given either as flat varargs (`op action op
  action ...`) or as a single map expression (`{op action, ...}`), the latter
  being handy together with `with-op-aliases`."
  {:style/indent 2}
  [handler msg & pairings]
  (let [dispatch (if (= 1 (count pairings))
                   (first pairings)
                   `(hash-map ~@pairings))]
    `(let [{op# :op transport# :transport :as msg#} ~msg]
       (if-let [action# (get ~dispatch op#)]
         (let [[op-action# err-action#]  (if (vector? action#) action# [action# ::default])]
           ;; Catch `Throwable`, not just `Exception`: an `Error` (e.g.
           ;; `AssertionError`, `StackOverflowError`) escaping here would leave the
           ;; op without a terminal `:done`, hanging the client until it times out.
           (try (transport/send transport# (op-handler op-action# msg#))
                (catch Throwable e# (transport/send transport# (error-handler err-action# msg# e#)))))
         (~handler msg#)))))

(defn with-op-aliases
  "Given a map of op names to actions, add an entry for the deprecated,
  unnamespaced alias of every `cider/`-namespaced op (pointing to the same
  action). This is the dispatch-side mirror of
  `cider.nrepl/with-deprecated-aliases`, so the handler answers exactly the same
  legacy op names the descriptors advertise, without hand-duplicating each
  pairing. Ops not starting with `cider/` are passed through unchanged."
  [pairings]
  (reduce-kv
   (fn [acc op action]
     (if (.startsWith ^String op "cider/")
       (assoc acc op action (subs op (count "cider/")) action)
       (assoc acc op action)))
   {}
   pairings))

(defn eval-interceptor-transport
  "Return a transport that wraps the transport in `msg` and intercepts the
  `:value` coming out of it as well as any raised exceptions. Exceptions are
  propagated as errors in the inspector middleware (specified by
  `error-statuses`), so that the client CIDER code properly renders them instead
  of silently ignoring. `reply-fn` is invoked on the message and the `:value`
  when it arrives."
  #_[{:keys [^Transport transport] :as msg} reply-fn & error-statuses] ;; BB-PATCH a sci protocol is not a class
  [{:keys [transport] :as msg} reply-fn & error-statuses]
  (reify #_Transport transport/Transport ;; BB-PATCH
    (recv [_this]
      #_(.recv transport) ;; BB-PATCH
      (transport/recv transport))

    (recv [_this timeout]
      #_(.recv transport timeout) ;; BB-PATCH
      (transport/recv transport timeout))

    (send [this response]
      (cond (contains? response :value)
            (reply-fn msg response)

            (and (contains? (:status response) :eval-error)
                 (contains? response ::caught/throwable))
            (let [e (::caught/throwable response)
                  resp (apply base-error-response msg e :done error-statuses)]
              #_(.send transport resp) ;; BB-PATCH
              (transport/send transport resp))

            :else #_(.send transport response) ;; BB-PATCH
            (transport/send transport response))
      this)))
