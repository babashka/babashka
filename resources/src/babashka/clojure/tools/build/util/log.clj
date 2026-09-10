;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.util.log
  (:require
    [clojure.pprint :as pprint]))

(defmacro log
  [verbose & msgs]
  `(when ~verbose
     (println ~@msgs)))

(defmacro log-map
  [verbose m]
  `(when ~verbose
     (binding [*print-namespace-maps* false]
       (pprint/pprint ~m))))
