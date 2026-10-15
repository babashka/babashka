(ns babashka.nrepl.impl.clojuredocs
  "Implements the cider-nrepl ops `cider/clojuredocs-lookup` and
  `cider/clojuredocs-refresh-cache` on top of orchard.clojuredocs."
  {:no-doc true}
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.transport :refer [safe-handle]]
   [orchard.clojuredocs :as docs]))

(defn see-also
  "Returns the see-also names of lookup result m as strings, or nil if the export is not cached."
  [{:keys [ns name]}]
  (when (and ns name (or (seq @docs/cache) (.exists docs/cache-file)))
    (try (some->> (docs/see-also (str ns "/" name)) (map str))
         (catch Exception _ nil))))

(defn- wire [x]
  (cond
    (map? x) (into {} (map (fn [[k v]] [(str (wire k)) (wire v)])) x)
    (sequential? x) (mapv wire x)
    (keyword? x) (subs (str x) 1)
    :else x))

(defn- lookup-reply [{:keys [ns sym]}]
  (if-let [doc (try (docs/resolve-and-find-doc (symbol ns) (symbol sym))
                    (catch Exception _ nil))]
    {:status :done :clojuredocs (wire doc)}
    {:status #{:done :no-doc}}))

(defn- refresh-cache-reply [{:keys [export-edn-url]}]
  (docs/clean-cache!)
  (if export-edn-url
    (docs/update-cache! export-edn-url)
    (docs/update-cache!))
  {:status #{:done :ok}})

(defn wrap-clojuredocs
  "Middleware for the cider-nrepl ClojureDocs ops."
  [h]
  (fn [msg]
    (safe-handle msg
      "cider/clojuredocs-lookup" lookup-reply
      "clojuredocs-lookup" lookup-reply
      "cider/clojuredocs-refresh-cache" refresh-cache-reply
      "clojuredocs-refresh-cache" refresh-cache-reply
      :else h)))

(def ^:private lookup-descriptor
  {:doc "Returns the ClojureDocs entry of a symbol."
   :requires {"ns" "The namespace to resolve in." "sym" "The symbol to look up."}
   :optional {}
   :returns {"clojuredocs" "The ClojureDocs entry."}})

(def ^:private refresh-cache-descriptor
  {:doc "Downloads the ClojureDocs export to the cache file."
   :requires {}
   :optional {"export-edn-url" "The URL of the export."}
   :returns {}})

(set-descriptor! #'wrap-clojuredocs
                 {:requires #{}
                  :expects #{}
                  :handles {"cider/clojuredocs-lookup" lookup-descriptor
                            "clojuredocs-lookup" lookup-descriptor
                            "cider/clojuredocs-refresh-cache" refresh-cache-descriptor
                            "clojuredocs-refresh-cache" refresh-cache-descriptor}})
