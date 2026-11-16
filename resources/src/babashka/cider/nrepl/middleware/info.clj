(ns cider.nrepl.middleware.info
  "Stand-in for cider.nrepl.middleware.info.
  Answers its ops with babashka's lookup."
  {:no-doc true}
  (:require
   [babashka.nrepl.impl.server :as server]
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [orchard.meta :as meta]
   [orchard.misc :as misc]))

(defn- info-reply [msg]
  (server/lookup-reply (assoc msg :op "info")))

(defn- eldoc-reply [msg]
  (server/lookup-reply (assoc msg :op "eldoc")))

(defn- eldoc-datomic-query-reply [_msg]
  {:status :no-eldoc})

(defn- classify-symbols-reply [{:keys [ns symbols]}]
  (let [ns (or (misc/as-sym ns) 'user)]
    {:classification (reduce (fn [acc s]
                               (if-let [kind (meta/classify-symbol ns (symbol s))]
                                 (assoc acc s (name kind))
                                 acc))
                             {}
                             symbols)}))

(defn handle-info [handler msg]
  (with-safe-transport handler msg
    (merge
     (with-op-aliases {"cider/info" info-reply
                       "cider/eldoc" eldoc-reply
                       "cider/eldoc-datomic-query" eldoc-datomic-query-reply})
     {"cider/classify-symbols" classify-symbols-reply})))
