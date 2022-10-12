(ns babashka.impl.hiccup
  {:no-doc true}
  (:require [hiccup.compiler :as compiler]
            [hiccup.util :as util]
            [sci.core :as sci :refer [copy-var]]))

(def hns (sci/create-ns 'hiccup.core nil))
(def hns2 (sci/create-ns 'hiccup2.core nil))
(def uns (sci/create-ns 'hiccup.util nil))
(def cns (sci/create-ns 'hiccup.compiler nil))

(defmacro html-2
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
  ;; (prn :escape-strings util/*escape-strings?*)
  (if (map? options)
    (let [mode            (:mode options :xhtml)
          escape-strings? (:escape-strings? options true)]
      `(binding
           [util/*html-mode* ~mode
            util/*escape-strings?* ~escape-strings?]
         (util/raw-string (compiler/render-html (list ~@content)))))
    `(util/raw-string (compiler/render-html (list ~@(cons options content))))))

(defmacro html-1
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
    `(str (hiccup2.core/html ~(assoc options :escape-strings? false) ~@content))
    `(str (hiccup2.core/html {:escape-strings? false} ~options ~@content))))

(def ^{:added "2.0"} raw
  "Short alias for [[hiccup.util/raw-string]]."
  util/raw-string)

(def hiccup-namespace
  {'html (copy-var html-1 hns {:name 'html})})

(def hiccup2-namespace
  {'html (copy-var html-2 hns2 {:name 'html})
   'raw (copy-var util/raw-string hns2)})

(def html-mode (copy-var util/*html-mode* uns))
(def escape-strings? (copy-var util/*escape-strings?* uns))

(def hiccup-util-namespace
  {'*html-mode* html-mode
   '*escape-strings?* escape-strings?
   'raw-string (copy-var util/raw-string uns)
   'to-uri (copy-var util/to-uri uns)})

(defn render-html [& contents]
  (binding [util/*html-mode* @html-mode
            util/*escape-strings?* @escape-strings?]
    (apply compiler/render-html contents)))

(def hiccup-compiler-namespace
  {'render-html (copy-var render-html cns)})
