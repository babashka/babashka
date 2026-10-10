(ns babashka.nrepl.impl.debug
  "Loads cider-nrepl's debug middleware on the first session."
  {:no-doc true}
  (:require
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.print :as print]
   [nrepl.middleware.session :as session]))

(def ^:private handle-debug
  (delay (requiring-resolve 'cider.nrepl.middleware.debug/handle-debug)))

(defn load!
  "Loads the CIDER debugger. Returns its handler."
  []
  @handle-debug)

(defn wrap-debug
  "Passes every message through the CIDER debugger once it is loaded."
  [h]
  (fn [msg]
    (if (realized? handle-debug)
      (@handle-debug h msg)
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
