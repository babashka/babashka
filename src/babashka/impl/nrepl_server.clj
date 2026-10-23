(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:require
   [babashka.impl.common :as common]
   [babashka.impl.nrepl.sci :as sci-helpers]
   [babashka.nrepl.server :as server]
   [sci.core :as sci]))

(defn start-server!
  ([]
   (start-server! nil))
  ([opts]
   (server/start-server! (common/ctx)
                         (merge {:describe {"versions" {"babashka" common/version}}}
                                opts))))

(def nrepl-server-namespace
  (let [ns-sci (sci/create-ns 'babashka.nrepl.server)]
    {'start-server! (sci/copy-var start-server! ns-sci)
     'stop-server! (sci/copy-var server/stop-server! ns-sci)}))

(defn completions
  "Completions for `query` in the current sci namespace."
  [query]
  (sci-helpers/completions (common/ctx) query))

(defn lookup
  "Metadata of `sym-str`, resolved in `ns-str` or the current sci namespace."
  [sym-str ns-str]
  (sci-helpers/lookup (common/ctx) sym-str :ns-str ns-str))

(def sci-helpers-namespace
  (let [ns-sci (sci/create-ns 'babashka.nrepl.impl.sci)]
    {'completions (sci/copy-var completions ns-sci)
     'lookup (sci/copy-var lookup ns-sci)}))
