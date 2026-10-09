(ns babashka.nrepl.impl.debug
  "Loads cider-nrepl's debug middleware on first use."
  {:no-doc true}
  (:require
   [clojure.string :as str]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.print :as print]
   [nrepl.middleware.session :as session]))

(def ^:private handle-debug
  (delay (requiring-resolve 'cider.nrepl.middleware.debug/handle-debug)))

(def ^:private debugging? (atom false))

(def ^:private debug-ops
  #{"init-debugger" "debug-input" "debug-instrumented-defs"})

(defn- debug-msg? [{:keys [op code]}]
  (or (contains? debug-ops (str/replace op #"^cider/" ""))
      (and (= "eval" op)
           (string? code)
           (re-find #"#(dbg|break)!?" code))))

(defn wrap-debug
  "Loads the CIDER debugger on the first debug op or debug-tagged eval.
  Passes every later message through the debugger.
  Waits for the debugger to load."
  [h]
  (fn [msg]
    (if (or @debugging? (debug-msg? msg))
      (do (reset! debugging? true)
          (@handle-debug h msg))
      (h msg))))

(set-descriptor! #'wrap-debug
                 {:requires #{#'session/session #'print/wrap-print "load-file"}
                  :expects #{"eval"}
                  :handles (into {}
                                 (for [[op desc] {"init-debugger"
                                                  {:doc "Starts the debugger. Breakpoints reply to this message."
                                                   :requires {}
                                                   :optional {}
                                                   :returns {}}
                                                  "debug-input"
                                                  {:doc "The client's answer at a breakpoint."
                                                   :requires {"key" "The key of the breakpoint's request."
                                                              "input" "The answer."}
                                                   :optional {}
                                                   :returns {"status" "done"}}
                                                  "debug-instrumented-defs"
                                                  {:doc "The instrumented vars by namespace."
                                                   :requires {}
                                                   :optional {}
                                                   :returns {"list" "Each namespace followed by its instrumented vars."}}}
                                       op [op (str "cider/" op)]]
                                   [op desc]))})
