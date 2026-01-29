(ns babashka.impl.terminal
  {:no-doc true}
  (:require [babashka.terminal]
            [sci.core :as sci]))

(def terminal-namespace
  (let [tns (sci/create-ns 'babashka.terminal)]
    {'tty? (sci/copy-var babashka.terminal/tty? tns)}))
