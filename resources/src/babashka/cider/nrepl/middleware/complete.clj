(ns cider.nrepl.middleware.complete
  "Stand-in for cider.nrepl.middleware.complete.
  Answers its ops with babashka's completion."
  {:no-doc true}
  (:require
   [babashka.nrepl.impl.server :as server]
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [clojure.string :as str]))

(defn- doc-reply [msg]
  (let [{:keys [ns name arglists-str doc]} (server/lookup-reply (assoc msg :op "info"))]
    {:completion-doc (when name
                       (str/join "\n" (cond-> [(str ns "/" name)]
                                        arglists-str (conj arglists-str)
                                        doc (conj (str "  " doc)))))}))

(defn- flush-caches-reply [_msg]
  {})

(defn handle-complete [handler msg]
  (with-safe-transport handler msg
    (with-op-aliases {"cider/complete" server/complete-reply
                      "cider/complete-doc" doc-reply
                      "cider/complete-flush-caches" flush-caches-reply})))
