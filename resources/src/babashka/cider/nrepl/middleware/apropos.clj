(ns cider.nrepl.middleware.apropos
  "Search symbols and docs matching a regular expression"
  {:author "Jeff Valk"}
  (:require
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [orchard.apropos :as apropos]))

;;; ## Middleware

(defn- msg->var-query-map [msg]
  (let [ns-query (select-keys msg [:exactly :project? :load-project-ns? :has-tests?
                                   :include-regexps :exclude-regexps])]
    (cond-> msg
      ;; Compatibility for the pre-var-query API
      (:privates? msg)
      (assoc-in [:var-query :private?] true)

      (:query msg)
      (assoc-in [:var-query :search] (:query msg))

      (and (:query msg)
           (not (:case-sensitive? msg)))
      (update-in [:var-query :search] #(format "(?i:%s)" %))

      (:docs? msg)
      (assoc-in [:var-query :search-property] :doc)

      (:docs? msg)
      (assoc :full-doc? true)

      true
      (assoc-in [:var-query :ns-query] ns-query)

      true
      (update :var-query util.coerce/var-query)

      (:ns msg)
      (update :ns (comp find-ns symbol)))))

(defn apropos [msg]
  {:apropos-matches (-> msg
                        msg->var-query-map
                        apropos/find-symbols)})

(defn handle-apropos [handler msg]
  (with-safe-transport handler msg
    (with-op-aliases {"cider/apropos" apropos})))
