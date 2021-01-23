(ns babashka.impl.hiccup
  {:no-doc true}
  (:require [hiccup.compiler :as compiler]
            [hiccup.util :as util]
            [sci.core :as sci :refer [copy-var]]))

(def hns (sci/create-ns 'hiccup.core nil))
(def hns2 (sci/create-ns 'hiccup2.core nil))
(def uns (sci/create-ns 'hiccup.util nil))

(defn html-2
  "Render Clojure data structures to a compiled representation of HTML. To turn
  the representation into a string, use clojure.core/str. Strings inside the
  macro are automatically HTML-escaped. To insert a string without it being
  escaped, use the [[raw]] function.
  A literal option map may be specified as the first argument. It accepts two
  keys that control how the HTML is outputted:
  `:mode`
  : One of `:html`, `:xhtml`, `:xml` or `:sgml` (defaults to `:xhtml`).
    Controls how tags are rendered.
  `:escape-strings?`
  : True if strings should be escaped (defaults to true)."
  {:added "2.0"}
  [options & content]
  (if (map? options)
    (let [mode            (:mode options :xhtml)
          escape-strings? (:escape-strings? options true)]
      (binding [util/*html-mode* mode
                util/*escape-strings?* escape-strings?]
         (util/raw-string (apply compiler/compile-html-with-bindings content))))
    (util/raw-string (apply compiler/compile-html-with-bindings options content))))

(defn html-1
  "Render Clojure data structures to a string of HTML. Strings are **not**
  automatically escaped, but must be manually escaped with the [[h]] function.
  A literal option map may be specified as the first argument. It accepts the
  following keys:
  `:mode`
  : One of `:html`, `:xhtml`, `:xml` or `:sgml` (defaults to `:xhtml`).
    Controls how tags are rendered."
  ;; {:deprecated "2.0"}
  [options & content]
  (if (map? options)
    (str (apply html-2 (assoc options :escape-strings? false) content))
    (str (apply html-2 {:escape-strings? false} options content))))

(def ^{:added "2.0"} raw
  "Short alias for [[hiccup.util/raw-string]]."
  util/raw-string)

(def hiccup-namespace
  {'html (copy-var babashka.impl.hiccup/html-1 hns)})

(def hiccup2-namespace
  {'html (copy-var html-2 hns2)})

(def hiccup-util-namespace
  {'*html-mode* (copy-var util/*html-mode* uns)
   '*escape-strings?* (copy-var util/*escape-strings?* uns)
   'raw-string (copy-var util/raw-string uns)})
