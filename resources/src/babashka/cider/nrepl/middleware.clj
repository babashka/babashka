(ns cider.nrepl.middleware
  "This namespace mostly exists, so that
  it's easy to share the list of provided middleware.

  Eventually the deferred middleware loading code will be probably moved here as well."
  {:added "0.25"})

(def cider-middleware
  '[cider.nrepl/wrap-apropos
    cider.nrepl/wrap-classpath
    cider.nrepl/wrap-clojuredocs
    cider.nrepl/wrap-complete
    cider.nrepl/wrap-content-type
    cider.nrepl/wrap-debug
    cider.nrepl/wrap-enlighten
    cider.nrepl/wrap-format
    cider.nrepl/wrap-info
    cider.nrepl/wrap-inspect
    cider.nrepl/wrap-log
    cider.nrepl/wrap-macroexpand
    cider.nrepl/wrap-ns
    cider.nrepl/wrap-out
    cider.nrepl/wrap-slurp
    cider.nrepl/wrap-profile
    cider.nrepl/wrap-refresh
    cider.nrepl/wrap-reload
    cider.nrepl/wrap-resource
    cider.nrepl/wrap-spec
    cider.nrepl/wrap-stacktrace
    cider.nrepl/wrap-test
    cider.nrepl/wrap-tap
    cider.nrepl/wrap-trace
    cider.nrepl/wrap-tracker
    cider.nrepl/wrap-undef
    cider.nrepl/wrap-version
    cider.nrepl/wrap-xref])

(def ops-that-can-eval
  "Set of nREPL ops that can lead to code being evaluated."
  #{"eval" "load-file"
    "cider/refresh" "refresh" "cider/refresh-all" "refresh-all" "cider/refresh-clear" "refresh-clear"
    "cider.clj-reload/reload" "cider.clj-reload/reload-all" "cider.clj-reload/reload-clear"
    "cider/toggle-trace-var" "toggle-trace-var" "cider/toggle-trace-ns" "toggle-trace-ns"
    "cider/undef" "undef" "cider/undef-all" "undef-all"
    "cider/profile-toggle-var" "cider/profile-toggle-ns"})
