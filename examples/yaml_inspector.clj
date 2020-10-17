#!/usr/bin/env bb

(ns yaml-inspector
  (:require [babashka.classpath :as cp]
            [clj-yaml.core :as yaml]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(def cp (str/trim (:out (sh "clojure" "-Spath" "-Sdeps" "{:deps {djblue/portal {:mvn/version \"0.6.1\"}}}"))))
(cp/add-classpath cp)

(require '[portal.api :as p])
(p/open)
(p/tap)

(def yaml (yaml/parse-string (slurp *in*)))
(tap> yaml)

(.addShutdownHook (Runtime/getRuntime)
                  (Thread. (fn [] (p/close))))

@(promise)

