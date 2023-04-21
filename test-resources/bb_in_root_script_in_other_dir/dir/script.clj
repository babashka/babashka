#!/usr/bin/env bb

(ns script
  (:require [medley.core :as medley]))

(prn (medley/index-by :id [{:id 1}]))
