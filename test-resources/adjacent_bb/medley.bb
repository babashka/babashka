#!/usr/bin/env bb

(require '[local-dep])

(assert (= :foo local-dep/local-dep-var))

(ns medley
  (:require [medley.core :as medley]))

(prn (medley/index-by :id [{:id 1}]))
