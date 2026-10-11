(ns babashka.nrepl.impl.track-state
  "Loads cider-nrepl's track-state middleware on the first session."
  {:no-doc true}
  (:require
   [babashka.nrepl.impl.debug :as debug]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.session :as session]
   [nrepl.transport :as t]))

(def ^:private track-state-ns
  (delay (require 'cider.nrepl.middleware.track-state)
         {:handle-tracker (resolve 'cider.nrepl.middleware.track-state/handle-tracker)
          :state-response (resolve 'cider.nrepl.middleware.track-state/calculate-changed-project-state-response)}))

(defn load!
  "Loads the track-state middleware."
  []
  @track-state-ns)

(defn wrap-track-state
  "Passes every message through cider-nrepl's track-state once it is loaded,
  and replies to `cider/get-state`."
  [h]
  (fn [{:keys [op] :as msg}]
    (if (realized? track-state-ns)
      (let [{:keys [handle-tracker state-response]} @track-state-ns]
        (if (= "cider/get-state" op)
          (t/send (:transport msg) (assoc (state-response msg) :status #{:state :done}))
          (handle-tracker h msg)))
      (h msg))))

(set-descriptor! #'wrap-track-state
                 {:requires #{#'session/session}
                  :expects #{"eval" "load-file" "cider/undef" "undef" "cider/undef-all" "undef-all" #'debug/wrap-debug}
                  :handles {"cider/get-state"
                            {:doc "Returns the REPL type and the namespaces changed since the last reply."
                             :requires {}
                             :optional {}
                             :returns {"repl-type" "`clj`."
                                       "changed-namespaces" "A map of namespaces to `aliases` and `interns`."}}}})
