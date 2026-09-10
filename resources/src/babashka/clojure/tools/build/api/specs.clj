(ns clojure.tools.build.api.specs
  (:require [clojure.spec.alpha :as s]))

(s/def ::lib qualified-ident?)
(s/def ::path string?)
(s/def ::paths (s/coll-of string?))

;; there are better specs in clojure.tools.deps.specs, but no basis spec yet
;; just doing a simple check here
(s/def ::basis (s/nilable map?))