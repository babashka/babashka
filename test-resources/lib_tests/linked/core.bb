(ns linked.core
  (:refer-clojure :exclude [map set])
  (:require [linked.map :as m]))

(defn map
  ([] m/empty-linked-map)
  ([& keyvals] (apply assoc m/empty-linked-map keyvals)))
