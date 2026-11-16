(ns cider.nrepl.middleware.profile
  "Simplistic manual tracing profiler for coarse usecases where the accuracy
  doesn't matter much and you already know which functions to measure."
  (:require
   [cider.nrepl.middleware.util :refer [msg->var]]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [cider.nrepl.middleware.inspect :as inspect-mw]
   [nrepl.misc :refer [response-for]]
   [orchard.profile :as profile]))

(defn toggle-var-reply [msg]
  (if-let [v (msg->var msg)]
    (if (profile/profiled? v)
      (do (profile/unprofile-var v)
          (response-for msg :status :done :value "unprofiled"))
      (if (profile/profilable? v)
        (do (profile/profile-var v)
            (response-for msg :status :done :value "profiled"))
        (response-for msg :status [:done :profile-invalid-var])))
    (response-for msg :status #{:profile-no-such-var :done})))

(defn toggle-ns-reply [{:keys [ns transport] :as msg}]
  (let [profiled? (profile/toggle-profile-ns (symbol ns))]
    (response-for msg
                  :status :done
                  :value (if profiled? "profiled" "unprofiled"))))

(defn summary-reply
  "Return profiling summary optimized for viewing through CIDER inspector."
  [{:keys [transport] :as msg}]
  (inspect-mw/inspect-reply*
   (assoc msg
          :max-coll-size 1 ;; To narrow :samples column.
          :view-mode :table)
   (profile/summary-for-inspector)))

(defn clear-reply [msg]
  (profile/clear)
  (response-for msg :status [:done :profile-cleared]))

(defn handle-profile [handler msg]
  (with-safe-transport handler msg
    "cider/profile-toggle-var" toggle-var-reply
    "cider/profile-toggle-ns"  toggle-ns-reply
    "cider/profile-summary"    summary-reply
    "cider/profile-clear"      clear-reply))
