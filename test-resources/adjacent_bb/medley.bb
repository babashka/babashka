#!/usr/bin/env bb

(ns medley
  (:require [medley.core :as medley]))

(prn (medley/index-by :id [{:id 1}]))
