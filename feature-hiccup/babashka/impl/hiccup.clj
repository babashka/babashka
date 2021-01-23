(ns babashka.impl.hiccup
  {:no-doc true}
  (:require [hiccup.core :as hiccup]
            [hiccup.util :as util]
            [hiccup2.core :as hiccup2]
            [sci.core :as sci :refer [copy-var]]))

(def hns (sci/create-ns 'hiccup.core nil))
(def hns2 (sci/create-ns 'hiccup2.core nil))
(def uns (sci/create-ns 'hiccup.util nil))

(defn html [x]
  (hiccup/html x))

(def hiccup-namespace
  {'html (copy-var html hns)})

(defn html2 [x]
  (hiccup2/html x))

(def hiccup2-namespace
  {'html (copy-var html2 hns2)})

(def hiccup-util-namespace
  {'*html-mode* (copy-var util/*html-mode* uns)
   '*escape-strings?* (copy-var util/*escape-strings?* uns)
   'raw-string (copy-var util/raw-string uns)})
