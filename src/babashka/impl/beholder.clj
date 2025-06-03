(ns babashka.impl.beholder
  (:require [nextjournal.beholder]
            [sci.core :as sci]))

(def bns (sci/create-ns 'nextjournal.beholder nil))

(def beholder-namespace (sci/copy-ns nextjournal.beholder bns))
