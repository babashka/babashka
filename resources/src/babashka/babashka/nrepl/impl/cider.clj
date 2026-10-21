(ns babashka.nrepl.impl.cider
  "Answers CIDER's test, stacktrace, inspector and version ops with
  cider-nrepl's replies, so CIDER needs no middleware on the classpath."
  {:no-doc true}
  (:require
   [cider.nrepl.middleware.inspect :as inspect-mw]
   [cider.nrepl.middleware.stacktrace :as stacktrace-mw]
   [cider.nrepl.middleware.test :as test-mw]
   [clojure.string :as str]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :as caught]
   [nrepl.middleware.print :as print]
   [nrepl.middleware.session :as session]
   [nrepl.transport :as t]))

(def version
  "The cider-nrepl version whose protocol these ops speak."
  (let [s "0.63.1"
        [major minor incremental] (map parse-long (str/split s #"\."))]
    {:major major :minor minor :incremental incremental :qualifier "" :version-string s}))

(def ^:private inspect-ops
  ["inspect-pop" "inspect-push" "inspect-next-sibling" "inspect-previous-sibling"
   "inspect-next-page" "inspect-prev-page" "inspect-refresh" "inspect-toggle-pretty-print"
   "inspect-toggle-view-mode" "inspect-display-analytics" "inspect-set-page-size"
   "inspect-set-max-atom-length" "inspect-set-max-coll-size" "inspect-set-max-nested-depth"
   "inspect-clear" "inspect-def-current-value" "inspect-tap-current-value" "inspect-tap-indexed"])

(defn wrap-cider
  "Middleware for the cider-nrepl ops babashka implements."
  [h]
  (fn [{:keys [op] :as msg}]
    (case (str/replace op #"^cider/" "")
      ("test" "test-var-query" "test-all" "retest" "test-stacktrace") (test-mw/handle-test h msg)
      ("analyze-last-stacktrace" "stacktrace" "inspect-last-exception") (stacktrace-mw/handle-stacktrace h msg)
      "cider-version" (t/respond-to msg :cider-version version :status :done)
      (inspect-mw/handle-inspect h msg))))

(set-descriptor! #'wrap-cider
                 {:requires #{#'session/session #'caught/wrap-caught #'print/wrap-print}
                  :expects #{"eval"}
                  :describe-fn (fn [_] {:cider-version version})
                  :handles (into {"test" {:doc "Runs the tests of a namespace, or the named ones."
                                          :requires {"ns" "The namespace."}
                                          :optional {"tests" "Test var names." "fail-fast" "\"true\" to stop at the first failure."}
                                          :returns {}}
                                  "test-var-query" {:doc "Runs the tests a var query selects." :requires {"var-query" "The query."} :optional {} :returns {}}
                                  "test-all" {:doc "Runs the tests of the project's namespaces." :requires {} :optional {"load?" "Loads the project's namespaces first."} :returns {}}
                                  "retest" {:doc "Reruns the tests that failed or errored last time." :requires {} :optional {} :returns {}}
                                  "test-stacktrace" {:doc "The causes of an erring test's exception." :requires {"ns" "" "var" "" "index" ""} :optional {} :returns {}}
                                  "analyze-last-stacktrace" {:doc "Returns the causes of the session's last exception." :requires {} :optional {} :returns {}}
                                  "stacktrace" {:doc "Alias for analyze-last-stacktrace." :requires {} :optional {} :returns {}}
                                  "inspect-last-exception" {:doc "Inspects a cause of the last analyzed exception, or its ex-data."
                                                            :requires {"index" "The index of the cause."}
                                                            :optional {"ex-data" "\"true\" to inspect the cause's ex-data."}
                                                            :returns {"value" "The rendered inspector." "path" "The path to the inspected value."}}
                                  "cider-version" {:doc "The cider-nrepl version these ops speak." :requires {} :optional {} :returns {"cider-version" ""}}
                                  "inspect-print-current-value" {:doc "Prints the inspected value." :requires {} :optional {} :returns {}}}
                                 (map (fn [op] [op {:doc "Inspector operation, see cider-nrepl."
                                                    :requires {} :optional {}
                                                    :returns {"value" "The rendered inspector." "path" "The path to the inspected value."}}]))
                                 inspect-ops)})
