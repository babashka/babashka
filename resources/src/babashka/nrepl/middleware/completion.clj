(ns nrepl.middleware.completion
  "Code completion middleware.

  The middleware is a simple wrapper around the
  functionality in `nrepl.completion`. Its
  API is inspired by cider-nrepl's \"complete\" middleware.

  The middleware can be configured to use a different completion
  function via a dynamic variable or a request parameter.

  NOTE: The functionality here is experimental and
  the API is subject to changes."
  {:author "Bozhidar Batsov"
   :added "0.8"}
  (:require
   [clojure.walk :as walk]
   [nrepl.middleware :as middleware :refer [set-descriptor!]]
   [nrepl.misc :as misc]
   [nrepl.transport :as t :refer [safe-handle]]
   [nrepl.util.completion :as complete]))

(def ^:dynamic *complete-fn*
  "Function to use for completion. Takes three arguments: `prefix`, the completion prefix,
  `ns`, the namespace in which to look for completions, and `options`, a map of additional
  options for the completion function."
  complete/completions)

(def ^:private parse-options
  (memoize
   (fn [options]
     (update (walk/keywordize-keys options) :extra-metadata (comp set (partial map keyword))))))

(defn completion-reply
  [{:keys [prefix ns complete-fn options] :as msg}]
  (let [the-ns (if ns (symbol ns) (symbol (str (misc/resolve-in-session msg *ns*))))
        completion-fn (or (some-> complete-fn symbol misc/requiring-resolve)
                          (misc/resolve-in-session msg *complete-fn*))]
    {:status :done
     :completions (completion-fn prefix the-ns (parse-options options))}))

(defn wrap-completion
  "Middleware that provides code completion.
  It understands the following params:

  * `prefix` - the prefix which to complete.
  * `ns`- the namespace in which to do completion. Defaults to `*ns*`.
  * `complete-fn` – a fully-qualified symbol naming a var whose function to use for
  completion. Must point to a function with signature [prefix ns options].
  * `options` – a map of options to pass to the completion function."
  [h]
  (fn [msg]
    (safe-handle msg
      "completions" completion-reply
      :else h)))

(set-descriptor! #'wrap-completion
                 {:requires #{"clone"}
                  :expects #{}
                  :handles {"completions"
                            {:doc "Provides a list of completion candidates."
                             :requires {"prefix" "The prefix to complete."}
                             :optional {"ns" "The namespace in which we want to obtain completion candidates. Defaults to `*ns*`."
                                        "complete-fn" "The fully qualified name of a completion function to use instead of the default one (e.g. `my.ns/completion`)."
                                        "options" "A map of options supported by the completion function. Supported keys: `extra-metadata` (possible values: `:arglists`, `:docs`)."}
                             :returns {"completions" "A list of completion candidates. Each candidate is a map with `:candidate` and `:type` keys. Vars also have a `:ns` key."}}}
                  :session-dynvars #{#'*complete-fn*}})
