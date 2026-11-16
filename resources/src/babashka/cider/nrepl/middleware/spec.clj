(ns cider.nrepl.middleware.spec
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [orchard.spec :as spec-utils]))

;; Replies

(defn spec-list-reply [msg]
  {:spec-list (spec-utils/spec-list (:filter-regex msg))})

(defn spec-form-reply [msg]
  {:spec-form (spec-utils/spec-form (:spec-name msg))})

(defn spec-example-reply [msg]
  {:spec-example (spec-utils/spec-example (:spec-name msg))})

(defn handle-spec [handler msg]
  (with-safe-transport handler msg
    (with-op-aliases {"cider/spec-list" spec-list-reply
                      "cider/spec-form" spec-form-reply
                      "cider/spec-example" spec-example-reply})))
