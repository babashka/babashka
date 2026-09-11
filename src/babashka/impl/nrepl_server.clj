(ns babashka.impl.nrepl-server
  {:no-doc true}
  (:require
   [babashka.impl.common :as common]
   [babashka.impl.nrepl.sci :as sci-helpers]
   [babashka.nrepl.server :as server]
   [sci.core :as sci]
   [sci.impl.io :as sio]))

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

(defn pr-on
  "Prints `x` to `w` as clojure.core/pr-on does, under the session's print
  settings. `*out*` stays bound, so what printing a lazy value writes
  goes to the session's out."
  [x ^java.io.Writer w]
  (binding [*print-length* @sio/print-length
            *print-level* @sio/print-level
            *print-meta* @sio/print-meta
            *print-namespace-maps* @sio/print-namespace-maps
            *print-readably* @sio/print-readably
            *print-dup* @sio/print-dup-var]
    (if *print-dup*
      (print-dup x w)
      (print-method x w))
    nil))

(def sci-helpers-namespace
  (let [ns-sci (sci/create-ns 'babashka.nrepl.impl.sci)]
    {'completions (sci/copy-var completions ns-sci)
     'lookup (sci/copy-var lookup ns-sci)
     'pr-on (sci/copy-var pr-on ns-sci)}))
