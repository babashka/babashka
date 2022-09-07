(ns babashka.impl.server
  (:require [babashka.impl.clojure.core.server :as server]
            [babashka.impl.common :as common]
            [babashka.impl.socket-repl :as socket-repl]
            [sci.core :as sci]))

(def sns (sci/create-ns 'clojure.core.server nil))

(def prepl (fn [& args]
             (apply server/prepl @common/ctx args)))

(def io-prepl
  (fn [& args]
    (apply server/io-prepl @common/ctx args)))

(def start-server
  (fn [& args]
    (apply server/start-server @common/ctx args)))

(def clojure-core-server-namespace
  {'repl (sci/copy-var socket-repl/repl sns)
   'prepl (sci/copy-var prepl sns)
   'io-prepl (sci/copy-var io-prepl sns)
   'start-server (sci/copy-var start-server sns)
   'stop-server (sci/copy-var server/stop-server sns)})
