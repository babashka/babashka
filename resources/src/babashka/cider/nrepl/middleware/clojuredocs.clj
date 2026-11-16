(ns cider.nrepl.middleware.clojuredocs
  "This middleware allows you to query for data ClojureDocs.
  It's a very simple wrapper around `orchard.clojuredocs`."
  {:added "0.22"}
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [orchard.clojuredocs :as docs]))

(defn- clojuredocs-lookup-reply [{:keys [ns sym]}]
  (try
    ;; TODO: change this to `resolve-doc` once I've added the extra arity there
    (if-let [doc (docs/resolve-and-find-doc (symbol ns) (symbol sym))]
      {:clojuredocs (util/transform-value doc)}
      {:status :no-doc})
    ;; TODO: Handle a missing ns directly in Orchard
    (catch Exception _
      {:status :no-doc})))

(defn clojuredocs-refresh-cache-reply [{:keys [export-edn-url]}]
  (docs/clean-cache!)
  (if export-edn-url
    (docs/update-cache! export-edn-url)
    (docs/update-cache!))
  {:status :ok})

(defn handle-clojuredocs [handler msg]
  (with-safe-transport handler msg
    (with-op-aliases {"cider/clojuredocs-refresh-cache" clojuredocs-refresh-cache-reply
                      "cider/clojuredocs-lookup" clojuredocs-lookup-reply})))
