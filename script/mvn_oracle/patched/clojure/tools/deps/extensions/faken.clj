;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.extensions.faken
  (:require
    [babashka.impl.mvn.version]
    [clojure.string :as str]
    [clojure.tools.deps.extensions :as ext])
  ;; BB-TEST-PATCH the resolver's version scheme is a JVM class babashka does
  ;; not have; its ordering comes from the Maven layer instead
  )

;; Fake Maven extension for testing dependency resolution

;; Use the functions to construct a faux Maven repo

;; {lib {coord [dep1 ...]}}
(def ^:dynamic repo {})

(defmacro with-libs
  [libs & body]
  `(binding [repo ~libs]
     ~@body))

(defmethod ext/coord-type-keys :fkn [_type] #{:fkn/version})

(defmethod ext/dep-id :fkn
  [_lib coord _config]
  (select-keys coord [:fkn/version]))

(defmethod ext/manifest-type :fkn
  [_lib _coord _config]
  {:deps/manifest :fkn})

(defmethod ext/compare-versions [:fkn :fkn]
  [_lib coord-x coord-y _config]
  (babashka.impl.mvn.version/compare-versions (:fkn/version coord-x) (:fkn/version coord-y)))

(defmethod ext/coord-deps :fkn
  [lib coord _manifest config]
  (remove
    (fn [[lib {:keys [optional]}]] optional)
    (get-in repo [lib (ext/dep-id lib coord config)])))

(defn make-path
  [lib {:keys [fkn/version]}]
  (let [[n c] (str/split (name lib) #"\$")]
    (str "REPO/" (namespace lib)
         "/" n
         "/" version
         "/" n (if c (str "-" c) "") "-" version ".jar")))

(defmethod ext/coord-paths :fkn
  [lib coord _manifest _config]
  [(make-path lib coord)])

(defmethod ext/find-versions :fkn
  [_lib _coord _type _config]
  nil)

(comment
  (with-libs
    {'a/a {{:fkn/version "0.1.2"} [['b/b {:fkn/version "1.2.3"}]]}
     'b/b {{:fkn/version "1.2.3"} nil}}
    (ext/coord-deps 'a/a {:fkn/version "0.1.2"} :fkn nil))
  )