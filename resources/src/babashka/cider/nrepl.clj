(ns cider.nrepl
  "Middleware descriptors and related utility functions.

  While normally middleware descriptors live alongside middleware
  definitions, cider-nrepl separates those. The rationale behind this
  is to avoid loading each middleware definition until its first usage.
  For this purpose we're defining each middleware as a wrapper that
  defers the loading of the actual middleware."
  (:require
   [cider.nrepl.version :as version]
   [cider.nrepl.middleware :as mw]
   [cider.nrepl.middleware.util.cljs :as cljs]
   ;; BB-PATCH needs the private clojure.core/print-object, absent in sci
   #_[cider.nrepl.print-method] ;; we load this namespace, so it's directly available to clients
   ;; BB-PATCH compliment does not run in sci, bb has its own completion
   #_[compliment.core :as compliment]
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :refer [wrap-caught]]
   [nrepl.middleware.print :refer [wrap-print wrap-print-optional-arguments]]
   [nrepl.middleware.session :refer [session]]
   [nrepl.misc :refer [with-session-classloader]]
   [nrepl.server :as nrepl-server]
   ;; BB-PATCH orchard.java needs the JDK compiler API
   #_[orchard.java]))

(def min-clojure-version
  "The minimum Clojure version needed by cider-nrepl to work properly.
  Having an enforced minimum version can help users and maintainers alike diagnose issues more quickly,
  avoiding problematic code paths in our middleware, and in clients like cider.el."
  {:major 1
   :minor 10})

;; Make sure we're running a supported Clojure version
(when (or (< (-> *clojure-version* :major long)
             (-> min-clojure-version :major long))

          (and (= (-> *clojure-version* :major long)
                  (-> min-clojure-version :major long))
               (< (-> *clojure-version* :minor long)
                  (-> min-clojure-version :minor long))))
  (let [msg (format "cider-nrepl requires a newer Clojure version (found: %s, minimum required: %s)."
                    *clojure-version* min-clojure-version)]
    (try
      (.println System/err msg)
      (finally (throw (ex-info msg {}))))))

;; Perform the underlying dynamic `require`s asap, and also not within a
;; separate thread, since `require` is not thread-safe. We try it on LruMap
;; which we know to be on the classpath, shipped with Orchard.
;; BB-PATCH
#_(try (orchard.java/source-info 'mx.cider.orchard.LruMap)
     (catch Exception _))

(defn warmup-caches!
  "Warm up some of the dependency caches to improve UX performance for first hits.
  The warmups should be non-invasive (avoid unpredictable side-effects like e.g.
  loading classes). Currently only warms up Compliment, others are TBD."
  []
  (try
    ;; This call removes the delay from the next `.prefix` completion.
    nil #_(compliment/completions ".sub")
    (catch Throwable t
      (println "Error during" `warmup-caches! ":" (str t)))))

;; BB-PATCH no compliment caches to warm up
#_(defonce ^:private warmup-once
  (.start (Thread. ^Runnable warmup-caches!)))

;;; Functionality for deferred middleware loading
;;
;; cider-nrepl depends on many libraries, so loading every middleware namespace
;; at startup once cost tens of seconds. We avoid that by deferring the load of
;; each middleware's actual handler until the first request it handles arrives.
;;
;; `requiring-resolve` does the work the bespoke delay/atom/lock machinery used
;; to: it serializes the `require` (the thread-safety we used to guard with a
;; manual lock - see cider#2092) and the namespace system caches the result, so
;; resolving an already-loaded handler is just a var lookup.

(defn- resolve-or-fail [sym]
  (or (resolve sym)
      (throw (IllegalArgumentException. (format "Cannot resolve %s" sym)))))

(defn- deferred-handler
  "Require and resolve the deferred handler named by `sym` (a fully-qualified
  symbol). Loads it within `session`'s classloader so middleware that depend on
  classpath added at runtime (e.g. via pomegranate) still resolve. Throws if
  `sym` can't be resolved. `session` is passed positionally because nREPL <1.7
  takes it as `with-session-classloader`'s first argument; newer nREPL ignores
  it (binding the current thread's context classloader instead)."
  [sym session]
  (or (with-session-classloader session
        (requiring-resolve sym))
      (throw (IllegalArgumentException. (str "Cannot resolve middleware handler " sym)))))

(defmacro ^{:arglists '([name handler-fn descriptor]
                        [name handler-fn trigger-it descriptor])}
  def-wrapper
  "Define delayed middleware (e.g. wrap-xyz).
  `handler-fn` is an unquoted name of a function that takes two arguments -
  `handler` and `message`. It is called only when certain conditions are met as
  expressed by the optional `trigger-it` argument. `trigger-it` can be either a
  function or a set of ops (strings). When a function, it must take a `msg` and
  return truthy value when `handler-fn` should run. When `trigger-it` is missing,
  `handle-fn` is called when :op of `msg` is one of keys of the :handles slot of
  the `descriptor`. When `trigger-it` is a set it should contain extra ops,
  besides those in :handles slot, on which `handle-fn` is
  triggered. `descriptor` is passed directly to the nREPLs `set-descriptor!`."
  [name handler-fn & [trigger-it descriptor]]
  (let [[descriptor trigger-it] (if descriptor
                                  [descriptor trigger-it]
                                  [trigger-it descriptor])
        trigger-it (eval trigger-it)
        descriptor (update (eval descriptor) :requires (fnil conj #{}) #'nrepl.middleware.session/session)
        ;; Clojure-only ops set `:clojure-only?` so they reply with a clear
        ;; message instead of a JVM-only result when a cljs REPL is active. The
        ;; flag is stripped before the descriptor reaches nREPL.
        clojure-only? (:clojure-only? descriptor)
        descriptor (dissoc descriptor :clojure-only?)
        cond (if (or (nil? trigger-it) (set? trigger-it))
               (let [ops-set (into (-> descriptor :handles keys set) trigger-it)]
                 `(~ops-set (:op ~'msg)))
               `(~trigger-it ~'msg))
        invoke `((deferred-handler '~handler-fn (:session ~'msg)) ~'h ~'msg)
        run-handler (if clojure-only?
                      `(if (cljs/grab-cljs-env ~'msg)
                         (cljs/respond-clojure-only ~'msg)
                         ~invoke)
                      invoke)
        doc (or (:doc descriptor) "")]
    (assert descriptor)
    `(do
       (defn ~name ~doc [~'h]
         (fn [~'msg]
           (if (and ~cond (not (:inhibit-cider-middleware ~'msg)))
             ~run-handler
             (~'h ~'msg))))
       (set-descriptor! #'~name ~descriptor))))

(defn with-deprecated-aliases
  "Expand a map of `cider/`-namespaced op descriptors so it also contains a
  deprecated, unnamespaced alias for each op. Each alias carries the same
  metadata as its primary, with a deprecation notice prepended to its `:doc`.
  This keeps the legacy op names working (see #710) without hand-duplicating
  every descriptor. Ops not starting with `cider/` are passed through unchanged."
  [handles]
  (reduce-kv
   (fn [acc op descriptor]
     (if (.startsWith ^String op "cider/")
       (let [bare (subs op (count "cider/"))]
         (assoc acc
                op descriptor
                bare (update descriptor :doc
                             #(str "Deprecated: use `" op "` instead. " %))))
       (assoc acc op descriptor)))
   {}
   handles))

;;; Deferred Middleware Wrappers
;;
;; Each middleware is defined in its own namespace, but here we're defining
;; deferred versions of the middleware handlers, that load the actual middleware
;; handlers on demand (the first time some middleware op gets invoked). This
;; makes the code a bit more complex, but improves a lot the startup time
;; as almost nothing gets loaded during REPL boot time.

(def-wrapper wrap-content-type cider.nrepl.middleware.content-type/handle-content-type
  #{"eval"}
  {:doc "Middleware that adds `content-type` annotations to the result of the the eval op."
   :requires #{#'wrap-print}
   :expects #{"eval" "load-file"}
   :handles {"content-type"
             {:doc "Enhances the `eval` op by adding `content-type` and `body` to certain `eval` responses. Not an op in itself.

Depending on the type of the return value of the evaluation this middleware may kick in and include a representation of the result in the response, together with a MIME/Media type to indicate how it should be handled by the client. Comes with implementations for `URI`, `URL`, `File`, and `java.awt.Image`. URIs and URLs are only annotated when their scheme names fetchable content (`file`, `http` or `https`); other schemes (`mailto:`, `jar:`, ...) print as usual. More type handlers can be provided by the user by extending the `cider.nrepl.middleware.content-type/content-type-response` multimethod. This dispatches using `clojure.core/type`, so `:type` metadata on plain Clojure values can be used to provide custom handling."
              :returns {"body" "The rich response document, if applicable."
                        "content-type" "The Media type (MIME type) of the reponse, structured as a pair, `[type {:as attrs}]`. When present, the response carries no `value` - the body replaces it."
                        "content-transfer-encoding" "The encoding of the response body (Optional, currently only one possible value: `\"base64\"`)."}
              :optional {"content-type" "If present and non-nil, try to detect and handle content-types."}}}})

(def-wrapper wrap-slurp cider.nrepl.middleware.slurp/handle-slurp
  {:doc "Middleware that handles slurp requests."
   :handles (with-deprecated-aliases
              {"cider/slurp"
               {:doc "Slurps a URL from the nREPL server, returning MIME data. Reads at most `cider.nrepl.middleware.slurp/*max-content-size*` bytes (4MB by default); larger resources yield a size-only placeholder. A failed fetch yields a plain-text explanation instead of an error."
                :returns {"content-type" "A MIME type for the response, if one can be detected."
                          "content-transfer-encoding" "The encoding (if any) for the content."
                          "body" "The slurped content body."}}})})

(def-wrapper wrap-apropos cider.nrepl.middleware.apropos/handle-apropos
  {:clojure-only? true
   :doc "Middleware that handles apropos requests"
   :handles (with-deprecated-aliases
              {"cider/apropos"
               {:doc "Return a sequence of vars whose name matches the query pattern, or if specified, having the pattern in their docstring."
                :requires {"query" "The search query."}
                :optional {"filter-regexps" "All vars from namespaces matching any regexp from this list would be dropped from the result."}
                :returns {"apropos-matches" "A list of matching symbols."}}})})

(def-wrapper wrap-classpath cider.nrepl.middleware.classpath/handle-classpath
  {:doc "Middleware that provides the java classpath."
   :handles (with-deprecated-aliases
              {"cider/classpath"
               {:doc "Obtain a list of entries in the Java classpath."
                :returns {"classpath" "A list of the Java classpath entries."}}})})

(def-wrapper wrap-complete cider.nrepl.middleware.complete/handle-complete
  {:doc "Middleware providing completion support."
   :requires (cljs/maybe-add-piggieback #{#'session})
   :handles (with-deprecated-aliases
              {"cider/complete"
               {:doc "Return a list of symbols matching the specified (partial) symbol."
                :requires {"ns" "The namespace is which to look for completions (falls back to *ns* if not specified)"
                           "prefix" "The prefix for completion candidates"
                           "session" "The current session"}
                :optional {"context" "Completion context for compliment."
                           "sort-order" "Sorting order of candidates. Possible values: by-name, by-length."
                           "extra-metadata" "List of extra-metadata fields. Possible values: arglists, doc."}
                :returns {"completions" "A list of possible completions"}}
               "cider/complete-doc"
               {:doc "Retrieve documentation suitable for display in completion popup"
                :requires {"ns" "The symbol's namespace"
                           "sym" "The symbol to lookup"}
                :returns {"completion-doc" "Symbol's documentation"}}
               "cider/complete-flush-caches"
               {:doc "Forces the completion backend to repopulate all its caches"}})})

;; `wrap-debug` has to be sandwiched between `load-file` and `eval`. First
;; `load-file` transforms its message into an `eval`, then `wrap-debug` attaches
;; its instrumenting functions to the message, and finally `eval` does the work.
(def-wrapper wrap-debug cider.nrepl.middleware.debug/handle-debug
  #{"eval"}
  {:doc "Provide instrumentation and debugging functionality."
   :expects  #{"eval"}
   :requires (cljs/maybe-add-piggieback #{#'wrap-print #'session "load-file"})
   :handles (with-deprecated-aliases
              {"cider/debug-input"
               {:doc "Read client input on debug action."
                :requires {"input" "The user's reply to the input request."
                           "key" "The corresponding input request key."}
                :returns  {"status" "done"}}
               "cider/init-debugger"
               {:doc "Initialize the debugger so that `breakpoint` works correctly. This usually does not respond immediately. It sends a response when a breakpoint is reached or when the message is discarded."
                :requires {"id" "A message id that will be responded to when a breakpoint is reached."}}
               "cider/debug-instrumented-defs"
               {:doc "Return an alist of definitions currently thought to be instrumented on each namespace. Due to Clojure's versatility, this could include false positives, but there will not be false negatives. Instrumentations inside protocols are not listed."
                :returns {"status" "done"
                          "list"   "The alist of (NAMESPACE . VARS) that are thought to be instrumented."}}
               "cider/debug-middleware"
               {:doc "Debug a code form or fall back on regular eval."
                :requires {"id"    "A message id that will be responded to when a breakpoint is reached."
                           "code"  "Code to debug, there must be a #dbg or a #break reader macro in it, or nothing will happen."
                           "file"  "File where the code is located."
                           "ns"    "Passed to \"eval\"."
                           "point" "Position in the file where the provided code begins."}
                :returns {"status" "\"done\" if the message will no longer be used, or \"need-debug-input\" during debugging sessions"}}})})

(def-wrapper wrap-enlighten cider.nrepl.middleware.enlighten/handle-enlighten
  :enlighten
  {:expects #{"eval" #'wrap-debug}})

(def-wrapper wrap-format cider.nrepl.middleware.format/handle-format
  {:doc "Middleware providing support for formatting Clojure code and EDN data."
   :requires #{#'wrap-print}
   :handles (with-deprecated-aliases
              {"cider/format-code"
               {:doc "Reformats the given Clojure code, returning the result as a string. The project's `.cljfmt.edn`/`.cljfmt.clj` (if any) is applied automatically, with the supplied `options` taking precedence."
                :requires {"code" "The code to format."}
                :optional {"options" "Configuration map passed to the formatter. For the default cljfmt formatter it is layered on top of the project's cljfmt configuration."
                           "formatter" "The fully-qualified name of the formatting function to use, e.g. `cider.nrepl.middleware.format/zprint`. Defaults to cljfmt. The function takes `[code options]` and returns the formatted code."}
                :returns {"formatted-code" "The formatted code."}}
               "cider/format-edn"
               {:doc "Reformats the given EDN data, returning the result as a string."
                :requires {"edn" "The data to format."}
                :optional wrap-print-optional-arguments
                :returns {"formatted-edn" "The formatted data."}}})})

(def fragments-desc
  "It's a vector of fragments, where fragment is a map with `:type` ('text' or 'html') and `:content` plain text or html markup, respectively")

(def fragments-doc
  {"doc-fragments"                (str "May be absent. Represents the body of a Java doc comment, including the first sentence and excluding any block tags. " fragments-desc)
   "doc-first-sentence-fragments" (str "May be absent. Represents the first sentence of a Java doc comment. " fragments-desc)
   "doc-block-tags-fragments"     (str "May be absent. Represent the 'param', 'returns' and 'throws' sections a Java doc comment. " fragments-desc)})

(def info-params
  {"sym"                "The symbol to lookup"
   "ns"                 "The current namespace"
   "context"            "A Compliment completion context, just like the ones already passed for the \"complete\" op,
with the difference that the symbol at point should be entirely replaced by \"__prefix__\".
For Java interop queries, it helps inferring the precise type of the object the `:sym` or `:member` refers to,
making the results more accurate (and less numerous)."
   "class"              "A Java class. If `:ns` is passed, it will be used for fully-qualifying the class, if necessary."
   "member"             "A Java class member."
   "var-meta-allowlist" "The metadata keys from vars to be returned. Currently only affects `:clj`.
Defaults to the value of `orchard.meta/var-meta-allowlist`.
If specified, the value will be concatenated to that of `orchard.meta/var-meta-allowlist`."})

(def info-returns
  {"arglists-str"  "The argument list(s) as a formatted string, if applicable."
   "added"         "The Clojure version when the var was added, if available."
   "annotated-arglists" "The argument lists annotated with parameter/return types, for Java members."
   "argtypes"      "The argument types of a Java method, as a list of class names."
   "class"         "The Java class name, for Java members."
   "column"        "The column number where the symbol is defined."
   "doc"           "The docstring of the symbol, if available."
   "file"          "Either a URI or a relative path where the symbol is defined."
   "file-url"      "A URL (file:// or http(s)://) to the symbol's source location, if resolvable."
   "forms-str"     "The forms for special forms, as a formatted string."
   "javadoc"       "URL to Javadoc documentation, for Java members."
   "line"          "The line number where the symbol is defined."
   "macro"         "\"true\" if the symbol is a macro."
   "member"        "The Java class member name, for Java members."
   "modifiers"     "Set of modifiers (e.g. public, static) for Java members."
   "name"          "The unqualified name of the symbol."
   "ns"            "The namespace the symbol belongs to."
   "parameter-types" "The parameter types of a Java method, as a list of class names."
   "protocol"      "The protocol that declares the symbol, for protocol methods."
   "resource"      "Relative path to the resource file."
   "returns"       "The return type for Java methods."
   "see-also"      "A list of related symbol names."
   "special-form"  "\"true\" if the symbol is a special form."
   "spec"          "The spec definition, if a spec exists for the symbol."
   "static"        "\"true\" if the symbol is a static Java member."
   "status"        "done"
   "throws"        "The checked exceptions a Java method declares, as a list of class names."
   "type"          "The kind of symbol (e.g. function, macro, special-form, var, class)."
   "url"           "URL to reference documentation, for special forms."
   "candidates"    "A map of class names to member info, for ambiguous Java member lookups."})

(def eldoc-returns
  {"eldoc"     "A list of argument lists. Each argument list is a list of strings. Absent for variables."
   "type"      "The type of symbol: \"function\", \"variable\", \"macro\", or \"special-form\"."
   "name"      "The unqualified name of the symbol."
   "ns"        "The namespace the symbol belongs to. Absent for Java members."
   "class"     "A list of class names, for Java members."
   "member"    "The Java method name, for Java members."
   "docstring" "The docstring of the symbol, if available. May be absent."
   "status"    "done"})

(def-wrapper wrap-info cider.nrepl.middleware.info/handle-info
  {:requires (cljs/maybe-add-piggieback #{#'session})
   :handles (merge
             (with-deprecated-aliases
               {"cider/info"
                {:doc "Return a map of information about the specified symbol."
                 :optional info-params
                 :returns (merge info-returns fragments-doc)}
                "cider/eldoc"
                {:doc "Return a map of eldoc information about the specified symbol, suitable for displaying function signatures and short documentation in the editor."
                 :optional info-params
                 :returns (merge eldoc-returns fragments-doc)}
                "cider/eldoc-datomic-query"
                {:doc "Return a map containing the inputs of the datomic query."
                 :requires {"sym" "The symbol to lookup"
                            "ns" "The current namespace"}
                 :returns {"status" "done"
                           "inputs" "The query's `:in` clause inputs, as a list of argument-name vectors."}}})
             ;; `cider/classify-symbols` is newer and has no deprecated alias.
             {"cider/classify-symbols"
              {:doc "Classify the given symbols by what kind of operator each is in the given namespace."
               :requires {"symbols" "A list of symbol strings to classify."}
               :optional {"ns" "The namespace in which to resolve the symbols. Defaults to 'user."}
               :returns {"status" "done"
                         "classification" "A map from each symbol to its kind (\"macro\", \"inline\", \"special\" or \"function\"). Symbols that don't resolve are omitted."}}})})

(def inspector-returns
  {"status" "\"done\""
   "value" "The inspector result. Contains a specially-formatted string that can be `read` and then rendered client-side."
   "path" "Printed representation of current inspector path."})

(def-wrapper wrap-inspect cider.nrepl.middleware.inspect/handle-inspect
  #{"eval"}
  {:doc "Add a value inspector option to the eval op. Passing a non-nil value
           in the `:inspect` slot will cause the last value returned by eval to
           be inspected. Returns a string representation of the resulting
           inspector's state in the `:value` slot."
   :requires #{"clone" #'wrap-caught #'wrap-print}
   ;; shadow-cljs's middleware, by an op it handles, as in `wrap-test`, so that
   ;; its ClojureScript evaluations come back through here.
   :expects (cljs/maybe-add-piggieback #{"eval" "shadow-remote-init"})
   :handles (merge
             (with-deprecated-aliases
               {"cider/inspect-print-current-value"
                {:doc "Print the current value of the inspector."
                 :requires {"session" "The current session"}
                 :optional wrap-print-optional-arguments
                 :returns inspector-returns}
                "cider/inspect-pop"
                {:doc "Moves one level up in the inspector stack."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-push"
                {:doc "Inspects the inside value specified by index."
                 :requires {"idx" "Index of the internal value currently rendered."
                            "session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-next-sibling"
                {:doc "Increment the index of the last 'nth in the path by 1,
if applicable, and re-render the updated value."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-previous-sibling"
                {:doc "Decrement the index of the last 'nth in the path by 1,
if applicable, and re-render the updated value."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-refresh"
                {:doc "Updates inspector with the provided config and re-renders the current value."
                 :requires {"session" "The current session"}
                 :optional {"page-size" "New page size"
                            "max-atom-length" "New max length of single rendered value"
                            "max-coll-size" "New max size of rendered collection"
                            "max-nested-depth" "New max nested depth of rendered collection"
                            "view-mode" "Mode of viewing the value - either `:normal` or `:object`"
                            "pretty-print" "Set to true to pretty-print values within the inspector"
                            "sort-maps" "Set to true to sort maps by their keys when inspecting a map"
                            "only-diff" "Set to true to only display values that differ when inspecting a diff"}
                 :returns inspector-returns}
                "cider/inspect-toggle-pretty-print"
                {:doc "Toggles the pretty printing of values in the inspector."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-toggle-view-mode"
                {:doc "Toggles the viewing mode of the inspector. This influences the way how inspector is rendering the current value. `:normal` is the default. When view mode is `:table`, the value will be rendered as a table (only supported for sequences of maps or tuples). When view mode is `:object`, any value will be rendered as a Java object (fields shown as is). View mode is automatically reset back to normal when navigating to child values."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-display-analytics"
                {:doc "Calculate and render analytics section for the currently inspected object."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-next-page"
                {:doc "Jumps to the next page in paginated collection view."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-prev-page"
                {:doc "Jumps to the previous page in paginated collection view."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-clear"
                {:doc "Clears the state state of the inspector."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-def-current-value"
                {:doc "Define the currently inspected value as a var with the given var-name in the provided namespace."
                 :requires {"session" "The current session"
                            "ns" "Namespace to define var on"
                            "var-name" "The var name"}
                 :returns inspector-returns}
                "cider/inspect-tap-current-value"
                {:doc "Send the currently inspected value to the Clojure tap>."
                 :requires {"session" "The current session"}
                 :returns inspector-returns}
                "cider/inspect-tap-indexed"
                {:doc "Send the currently inspected sub-value at `idx` to the Clojure tap>."
                 :requires {"session" "The current session"
                            "idx" "Index of the internal value to be tapped"}
                 :returns inspector-returns}})
             ;; These ops are deprecated in favour of `cider/inspect-refresh` (a
             ;; *different* op), so they keep their bespoke notice and stay
             ;; hand-written rather than going through `with-deprecated-aliases`.
             {"cider/inspect-set-page-size"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Sets the page size in paginated view to specified value."
               :requires {"page-size" "New page size."
                          "session" "The current session"}
               :returns inspector-returns}
              "inspect-set-page-size"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Sets the page size in paginated view to specified value."
               :requires {"page-size" "New page size."
                          "session" "The current session"}
               :returns inspector-returns}
              "cider/inspect-set-max-atom-length"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Set the max length of nested atoms to specified value."
               :requires {"max-atom-length" "New max length."
                          "session" "The current session"}
               :returns inspector-returns}
              "inspect-set-max-atom-length"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Set the max length of nested atoms to specified value."
               :requires {"max-atom-length" "New max length."
                          "session" "The current session"}
               :returns inspector-returns}
              "cider/inspect-set-max-coll-size"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Set the number of nested collection members to display before truncating."
               :requires {"max-coll-size" "New collection size."
                          "session" "The current session"}
               :returns inspector-returns}
              "inspect-set-max-coll-size"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Set the number of nested collection members to display before truncating."
               :requires {"max-coll-size" "New collection size."
                          "session" "The current session"}
               :returns inspector-returns}
              "cider/inspect-set-max-nested-depth"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Set the maximum nested levels to display before truncating."
               :requires {"max-nested-depth" "New nested depth."
                          "session" "The current session"}
               :returns inspector-returns}
              "inspect-set-max-nested-depth"
              {:doc "[DEPRECATED - use `cider/inspect-refresh` instead] Set the maximum nested levels to display before truncating."
               :requires {"max-nested-depth" "New nested depth."
                          "session" "The current session"}
               :returns inspector-returns}})})

(def-wrapper wrap-log cider.nrepl.middleware.log/handle-log
  {:doc "Middleware that captures log events and makes them inspect-able."
   :requires #{#'session #'wrap-print}
   :handles
   {"cider/log-add-appender"
    {:doc "Add an appender to a log framework."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."
                "filters" "A map from filter name to filter condition."
                "size" "The number of events the appender keeps in memory."
                "threshold" "The threshold in percent used to cleanup events."}
     :optional {"logger" "The name of the logger to attach to."}
     :returns {"status" "done"
               "cider/log-add-appender" "The appender that was added."}}

    "cider/log-add-consumer"
    {:doc "Add a consumer to an appender of a log framework."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."
                "filters" "A map from filter name to filter condition."}
     :returns {"status" "done"
               "cider/log-add-consumer" "The consumer that was added."}}

    "cider/log-clear-appender"
    {:doc "Clear all events of a log appender."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."}
     :returns {"status" "done"
               "cider/log-clear-appender" "The appender that was cleared."}}

    "cider/log-exceptions"
    {:doc "Return the exceptions and their frequencies for the given framework and appender."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."}
     :returns {"status" "done"
               "cider/log-exceptions" "A map from exception name to event frequency."}}

    "cider/log-frameworks"
    {:doc "Return the available log frameworks."
     :returns {"status" "done"
               "cider/log-frameworks" "A list of log frameworks."}}

    "cider/log-format-event"
    {:doc "Format a log event."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the log appender."
                "event" "The id of the log event."}
     :optional wrap-print-optional-arguments
     :returns {"status" "done"
               "cider/log-format-event" "The formatted log event."}}

    "cider/log-inspect-event"
    {:doc "Inspect a log event."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."
                "event" "The id of the event to inspect."}
     :returns {"status" "done"
               "value" "The inspection result."
               "path" "The current position within the inspected structure, as a sequence of path segments."}}

    "cider/log-levels"
    {:doc "Return the log levels and their frequencies for the given framework and appender."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."}
     :returns {"status" "done"
               "cider/log-levels" "A map from log level to event frequency."}}

    "cider/log-loggers"
    {:doc "Return the loggers and their frequencies for the given framework and appender."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."}
     :returns {"status" "done"
               "cider/log-loggers" "A map from logger name to event frequency."}}

    "cider/log-remove-appender"
    {:doc "Remove an appender from a log framework."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."}
     :returns {"status" "done"
               "cider/log-remove-appender" "The removed appender."}}

    "cider/log-remove-consumer"
    {:doc "Remove a consumer from the appender of a log framework."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."
                "consumer" "The name of the consumer."}
     :returns {"status" "done"
               "cider/log-remove-consumer" "The removed consumer."}}

    "cider/log-update-appender"
    {:doc "Update the appender of a log framework."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."
                "filters" "A map from filter name to filter condition."
                "size" "The number of events the appender keeps in memory."
                "threshold" "The threshold in percent used to cleanup events."}
     :returns {"status" "done"
               "cider/log-update-appender" "The updated appender."}}

    "cider/log-update-consumer"
    {:doc "Update the consumer of a log appender."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."
                "consumer" "The name of the consumer."
                "filters" "A map from filter name to filter condition."}
     :returns {"status" "done"
               "cider/log-update-consumer" "The consumer that was updated."}}

    "cider/log-search"
    {:doc "Search the log events of an appender."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."}
     :optional {"filters" "A map from filter name to filter condition."
                "limit" "Number of log events to return."}
     :returns {"status" "done"
               "cider/log-search" "The list of log events matching the search."}}

    "cider/log-threads"
    {:doc "Return the threads and their frequencies for the given framework and appender."
     :requires {"framework" "The id of the log framework."
                "appender" "The name of the appender."}
     :returns {"status" "done"
               "cider/log-threads" "A map from thread name to event frequency."}}}})

(def-wrapper wrap-macroexpand cider.nrepl.middleware.macroexpand/handle-macroexpand
  {:doc "Macroexpansion middleware."
   :requires (cljs/maybe-add-piggieback #{#'session})
   :expects #{"eval"}
   :handles (with-deprecated-aliases
              {"cider/macroexpand"
               {:doc "Produces macroexpansion of some form using the given expander."
                :requires {"code" "The form to macroexpand."}
                :optional {"ns" "The namespace in which to perform the macroexpansion. Defaults to 'user for Clojure and 'cljs.user for ClojureScript."
                           "expander" "The macroexpansion function to use. Possible values are \"macroexpand-1\", \"macroexpand\", \"macroexpand-step\", or \"macroexpand-all\". Defaults to \"macroexpand\"."
                           "display-namespaces" "How to print namespace-qualified symbols in the result. Possible values are \"qualified\" to leave all namespaces qualified, \"none\" to elide all namespaces, or \"tidy\" to replace namespaces with their aliases in the given namespace. Defaults to \"qualified\"."
                           "print-meta" "If truthy, also print metadata of forms."}
                :returns {"expansion" "The macroexpanded form."}}})})

(def-wrapper wrap-ns cider.nrepl.middleware.ns/handle-ns
  {:doc "Provide ns listing and browsing functionality."
   :requires (cljs/maybe-add-piggieback #{#'session})
   :handles (merge
             (with-deprecated-aliases
               {"cider/ns-list"
                {:doc "Return a sorted list of all namespaces."
                 :returns {"status" "done" "ns-list" "The sorted list of all namespaces."}
                 :optional {"filter-regexps" "All namespaces matching any regexp from this list would be dropped from the result."}}
                "cider/ns-list-vars-by-name"
                {:doc "Return a list of vars named `name` amongst all namespaces."
                 :requires {"name" "The name to use."}
                 :returns {"status" "done" "var-list" "The list obtained."}}
                "cider/ns-vars"
                {:doc "Returns a sorted list of public vars in a namespace."
                 :requires {"ns" "The namespace to browse."}
                 :optional {"var-query" "The search query for vars. Only \"private?\" is supported for ClojureScript."}
                 :returns {"status" "done" "ns-vars" "The sorted list of public vars in a namespace."}}
                "cider/ns-vars-with-meta"
                {:doc "Returns a map of [var-name] to [var-metadata] for public vars in a namespace."
                 :requires {"ns" "The namespace to use."}
                 :optional {"var-query" "The search query for vars. Only \"private?\" is supported for ClojureScript."}
                 :returns {"status" "done" "ns-vars-with-meta" "The map of [var-name] to [var-metadata] for public vars in a namespace."}}
                "cider/ns-path"
                {:doc "Returns the path to the file containing ns."
                 :requires {"ns" "The namespace to find."}
                 :returns {"status" "done"
                           "path" "The path to the file containing ns. Please favor `:url` in ClojureScript, but fall back to `:path`."
                           "url" "The Java URL indicating the file containing ns. Please favor this attribute over `:path` when possible. If this value is nil, you can fall back to `:path`."}}
                "cider/ns-load-all"
                {:doc "Loads all project namespaces."
                 :returns {"status" "done" "loaded-ns" "The list of ns that were loaded."}}
                "cider/ns-aliases"
                {:doc "Returns a map of [ns-alias] to [ns-name] in a namespace."
                 :requires {"ns" "The namespace to use."}
                 :returns {"status" "done" "ns-aliases" "The map of [ns-alias] to [ns-name] in a namespace."}}})
             ;; These are newer and have no deprecated aliases, which would
             ;; shadow refactor-nrepl's `resolve-missing` and `clean-ns`.
             {"cider/resolve-missing"
              {:doc "Return the namespaces and classes that could provide a symbol the namespace can't resolve yet: loaded namespaces with a public var of that name, to require, and classes of that name, to import. There are none when the namespace can already use the symbol, and a fully qualified symbol gets only its own namespace."
               :requires {"symbol" "The symbol to resolve, as written (e.g. `join`, `str/join`, `Date`, `Date.` or `Instant/now`)."}
               :optional {"ns" "The namespace the symbol is used in."}
               :returns {"status" "done"
                         "candidates" "A list of maps with the candidate's `name` and `type`: `ns` or (ClojureScript only) `macro` for a namespace to require, `class` for a class to import, and `type` for a `deftype` or `defrecord` class, which also has the `ns` that defines it."}}
              "cider/resolve-alias"
              {:doc "Return the namespaces an alias could stand for, judging by the project's source files: the namespaces they give that alias, the most used first. Only when none of those works on the platform do the preferred namespaces and the project namespaces named after the alias count. There are none when the namespace already has the alias, or the alias names a namespace or a class, or it's `js` in ClojureScript."
               :requires {"alias" "The alias, e.g. `str` for `str/join`."}
               :optional {"ns" "The namespace the alias is used in."
                          "platform" "`clj`, `cljs`, or `cljc`, where a candidate has to work for both. Defaults to `cljs` in a ClojureScript session and to `clj` otherwise."
                          "preferred" "A list of namespaces to offer when the project's source files don't give the alias to one, as long as they're there to require. A JavaScript module comes quoted, as in a libspec."}
               :returns {"status" "done"
                         "candidates" "A list of maps with the candidate's `ns`, as written in a libspec (so a JavaScript module comes quoted), and its `source`: `project`, with the `count` of the source files that give the namespace this alias, `preferred` or `suggested`."}}
              "cider/clean-ns"
              {:doc "Return what the ns form at the start of `code` requires, refers and imports that the rest of `code` doesn't use, going by reading it, without loading anything. A name counts as used wherever it comes up. Requires with no alias or names to refer, `:refer :all`, `:rename`, prefix lists and whatever is in a reader conditional count as used. A libspec whose alias goes unused, but whose namespace is used fully qualified or defines a class that gets imported, only loses the alias."
               :requires {"code" "The code, from the ns form on, e.g. the rest of a source file."}
               :optional {"line" "The line `code` starts on, 1 by default, so that a read error tells the line in the file."
                          "platform" "`clj`, `cljs`, or `cljc` for both. Without it, less goes, as the code might be ClojureScript, where the alias of a JavaScript module is also a value, and a class used fully qualified still needs its import."}
               :returns {"status" "done, with `cider/clean-ns-error` when `code` doesn't read or has no ns form"
                         "libspecs" "A list of maps with a libspec's `ns`, as written (so a JavaScript module comes quoted), its `clause` (`require` or `require-macros`), and either `remove` (`true`) when the whole libspec can go, or the parts of it that can: its `as` or `as-alias` alias, and the names in its `refer` or `refer-macros` list."
                         "imports" "A list of the fully qualified names of the imported classes that can go."
                         "error" "Why `code` couldn't be cleaned."
                         "line" "The line where reading `code` failed."}}})})

(def-wrapper wrap-out cider.nrepl.middleware.out/handle-out
  {:requires #{#'session}
   :expects (cljs/maybe-add-piggieback #{"eval"})
   :handles (with-deprecated-aliases
              {"cider/out-subscribe"
               {:doc "Change #'*out* so that it also prints to active sessions, even outside an eval scope."}
               "cider/out-unsubscribe"
               {:doc "Change #'*out* so that it no longer prints to active sessions outside an eval scope."}})})

(def-wrapper wrap-profile cider.nrepl.middleware.profile/handle-profile
  {:clojure-only? true
   :doc     "Middleware for manual profiling"
   :handles {"cider/profile-toggle-var" {:doc      "Toggle profiling of a given var."
                                         :requires {"sym" "The symbol to profile"
                                                    "ns"  "The current namespace"}
                                         :returns  {"status" "Done"
                                                    "value"  "'profiled' if profiling enabled, 'unprofiled' if disabled"}}
             "cider/profile-toggle-ns"  {:doc      "Toggle profiling of given namespace."
                                         :requires {"ns" "The current namespace"}
                                         :returns  {"status" "Done"
                                                    "value"  "'profiled' if profiling enabled, 'unprofiled' if disabled"}}
             "cider/profile-summary"    {:doc      "Return profiling summary optimized for viewing through CIDER inspector."
                                         :requires {}
                                         :returns  {"status" "Done"
                                                    "value"  "Profile summary as inspectable data structure."
                                                    "path"   "The current position within the inspected structure, as a sequence of path segments."}}
             "cider/profile-clear"      {:doc      "Clear profiling data."
                                         :requires {}
                                         :returns  {"status" "Done"}}}})

(def code-reloading-before-after-opts
  {"before" "The namespace-qualified name of a zero-arity function to call before reloading."
   "after" "The namespace-qualified name of a zero-arity function to call after reloading."})

(def-wrapper wrap-refresh cider.nrepl.middleware.refresh/handle-refresh
  {:clojure-only? true
   :doc "Refresh middleware."
   :requires #{"clone" #'wrap-print}
   :handles (with-deprecated-aliases
              {"cider/refresh"
               {:doc "Reloads all changed files in dependency order."
                :optional (merge wrap-print-optional-arguments
                                 {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."}
                                 code-reloading-before-after-opts)
                :returns {"reloading" "List of namespaces that will be reloaded."
                          "status" "`:ok` if reloading was successful; otherwise `:error`."
                          "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
                          "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."
                          "before" "The name of the before-hook function, echoed when it is invoked."
                          "after" "The name of the after-hook function, echoed when it is invoked."
                          "out" "Output produced on stdout/stderr by the before/after hooks."}}
               "cider/refresh-all"
               {:doc "Reloads all files in dependency order."
                :optional (merge wrap-print-optional-arguments
                                 {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
                                  "before" "The namespace-qualified name of a zero-arity function to call before reloading."
                                  "after" "The namespace-qualified name of a zero-arity function to call after reloading."})
                :returns {"reloading" "List of namespaces that will be reloaded."
                          "status" "`:ok` if reloading was successful; otherwise `:error`."
                          "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
                          "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."
                          "before" "The name of the before-hook function, echoed when it is invoked."
                          "after" "The name of the after-hook function, echoed when it is invoked."
                          "out" "Output produced on stdout/stderr by the before/after hooks."}}
               "cider/refresh-clear"
               {:doc "Clears the state of the refresh middleware. This can help recover from a failed load or a circular dependency error."}})})

(def-wrapper wrap-reload cider.nrepl.middleware.reload/handle-reload
  {:clojure-only? true
   :doc "Reload middleware."
   :requires #{"clone" #'wrap-print}
   :handles {"cider.clj-reload/reload"
             {:doc "Reloads all changed files in dependency order,
using the io.github.tonsky/clj-reload library. It is bundled with cider-nrepl.
If that dependency is already in present your project and clj-reload.core/init has been invoked beforehand,
those configured directories will be honored."
              :optional code-reloading-before-after-opts
              :returns {"progress" "Description of current namespace being unloaded/loaded."
                        "status" "`:ok` if reloading was successful; otherwise `:error`."
                        "error" "A sequence of all causes of the thrown exception when `status` is `:error`."}}
             "cider.clj-reload/reload-all"
             {:doc "Reloads all files in dependency order."
              :optional code-reloading-before-after-opts
              :returns {"progress" "Description of current namespace being unloaded/loaded."
                        "status" "`:ok` if reloading was successful; otherwise `:error`."
                        "error" "A sequence of all causes of the thrown exception when `status` is `:error`."}}
             "cider.clj-reload/reload-clear"
             {:doc "Clears the state of clj-reload. This can help recover from a failed load or a circular dependency error."
              :returns {"progress" "Description of the clearing operation's progress."}}}})

(def-wrapper wrap-resource cider.nrepl.middleware.resource/handle-resource
  {:doc "Middleware that provides the path to resource."
   :handles (with-deprecated-aliases
              {"cider/resource"
               {:doc "Obtain the path to a resource."
                :requires {"name" "The name of the resource in question."}
                :returns {"resource-path" "The file path to a resource."}}
               "cider/resources-list"
               {:doc "Obtain a list of all resources on the classpath."
                :returns {"resources-list" "The list of resources."}}})})

(def-wrapper wrap-spec cider.nrepl.middleware.spec/handle-spec
  {:clojure-only? true
   :doc "Middleware that provides `clojure.spec` browsing functionality."
   :requires (cljs/maybe-add-piggieback #{})
   :handles (with-deprecated-aliases
              {"cider/spec-list" {:doc "Return a sorted list of all specs in the registry"
                                  :returns {"status" "done"
                                            "spec-list" "The sorted list of all specs in the registry with their descriptions"}
                                  :optional {"filter-regex" "Only the specs that matches filter prefix regex will be returned "}}
               "cider/spec-form" {:doc "Return the form of a given spec"
                                  :requires {"spec-name" "The spec namespaced keyword we are looking for"}
                                  :returns {"status" "done"
                                            "spec-form" "The spec form"}}
               "cider/spec-example" {:doc "Return a string with a pretty printed example for a spec"
                                     :requires {"spec-name" "The spec namespaced keyword we want the example for"}
                                     :returns {"status" "done"
                                               "example" "The pretty printed spec example string"}}})})

(def stacktrace-returns
  "Per-cause keys streamed by the stacktrace ops, one message per exception cause."
  {"status" "\"done\", or \"no-error\" if `*e` is nil"
   "class" "The class name of the exception cause."
   "message" "The message of the exception cause, if any."
   "phase" "The compilation phase of the cause, for compiler exceptions; otherwise absent."
   "stacktrace" "The analyzed stack frames of the cause, as a list of frame maps."})

(def-wrapper wrap-stacktrace cider.nrepl.middleware.stacktrace/handle-stacktrace
  {:doc "Middleware that handles stacktrace requests, sending
           cause and stack frame info for the most recent exception."
   :requires (cljs/maybe-add-piggieback #{#'session #'wrap-print})
   :expects #{}
   :handles (merge
             (with-deprecated-aliases
               {"cider/analyze-last-stacktrace" {:doc "Return messages describing each cause and stack frame of the most recent exception."
                                                 :optional wrap-print-optional-arguments
                                                 :returns stacktrace-returns}})
             ;; `inspect-last-exception`'s alias body references the unnamespaced op
             ;; names, and the `stacktrace` ops are deprecated in favour of a
             ;; *different* op, so both stay hand-written.
             {"cider/inspect-last-exception" {:doc "Returns an Inspector response for the last exception that has been processed through `cider/analyze-last-stacktrace` for the current nrepl session.
Assumes that `cider/analyze-last-stacktrace` has been called first, returning \"no-error\" otherwise."
                                              :requires {"index" "0 for inspecting the top-level exception, 1 for its ex-cause, 2 for its ex-cause's ex-cause, and so on."}
                                              :optional {"ex-data" "When equal to \"true\", inspect ex-data of the exception instead of full exception."}
                                              :returns {"status" "\"done\", or \"no-error\" if `cider/analyze-last-stacktrace` wasn't called beforehand (or the `index` was out of bounds)."
                                                        "value" "A value, as produced by the Inspector middleware."
                                                        "path" "The current position within the inspected structure, as a sequence of path segments."}}
              "inspect-last-exception" {:doc "Deprecated: use `cider/inspect-last-exception` instead. Returns an Inspector response for the last exception that has been processed through `analyze-last-stacktrace` for the current nrepl session.
Assumes that `analyze-last-stacktrace` has been called first, returning \"no-error\" otherwise."
                                        :requires {"index" "0 for inspecting the top-level exception, 1 for its ex-cause, 2 for its ex-cause's ex-cause, and so on."}
                                        :optional {"ex-data" "When equal to \"true\", inspect ex-data of the exception instead of full exception."}
                                        :returns {"status" "\"done\", or \"no-error\" if `analyze-last-stacktrace` wasn't called beforehand (or the `index` was out of bounds)."
                                                  "value" "A value, as produced by the Inspector middleware."}}
              "cider/stacktrace" {:doc "Deprecated: use `cider/analyze-last-stacktrace` instead. Return messages describing each cause and
stack frame of the most recent exception."
                                  :optional wrap-print-optional-arguments
                                  :returns stacktrace-returns}
              "stacktrace" {:doc "Deprecated: use `cider/analyze-last-stacktrace` instead. Return messages describing each cause and
stack frame of the most recent exception."
                            :optional wrap-print-optional-arguments
                            :returns stacktrace-returns}})})

(def timing-info-return-doc {"status" "Either done or indication of an error"
                             "elapsed-time" "a report of the elapsed time spent running all the given namespaces. The structure is `:elapsed-time {:ms <integer> :humanized <string>}`."
                             "ns-elapsed-time" "a report of the elapsed time spent running each namespace. The structure is `:ns-elapsed-time {<ns as keyword> {:ms <integer> :humanized <string>}}`."
                             "var-elapsed-time" "a report of the elapsed time spent running each var. The structure is `:var-elapsed-time {<ns as keyword> {<var as keyword> {:ms <integer> :humanized <string>}}}`."
                             "results" "A map of test results keyed by namespace and var name: `{<ns keyword> {<var keyword> [<assertion> ...]}}`. Each assertion map contains: `:type` (pass/fail/error), `:ns`, `:var`, `:index` (0-based assertion index within the var), `:context` (testing context string or nil), `:message`, `:file`, `:line`. For `:fail`: `:expected` and `:actual` (pretty-printed strings), and optionally `:diffs` and `:gen-input`. For `:error`: `:error` (the exception) and `:line`. Each assertion may also include `:elapsed-time {:ms <integer> :humanized <string>}` when it is the only assertion in its var."
                             "summary" "A map of test run counts: `{:ns <int> :var <int> :test <int> :pass <int> :fail <int> :error <int>}`."
                             "testing-ns" "Streamed while the run is in progress: the namespace currently being tested."
                             "gen-input" "The minimal failing input found by a `test.check` property test, when a generative test fails."})

(def fail-fast-doc {"fail-fast" "If equals to the string \"true\", the tests will be considered complete after the first test has failed or errored."})

(def stream-optional-doc {"stream" "If equals to the string \"true\", progress is sent as `test-event` messages while the tests run, ahead of the final report."})

(def stream-return-doc {"test-event" "Only sent when `stream` is \"true\". A map with a `:type` of `begin-ns` (with `:ns`), `end-var` (with `:ns`, `:var`, the var's non-passing `:results` in the same shape as the final report's, and the running `:summary`) or `end-ns` (with `:ns` and `:elapsed-time`)."})

(def test-run-optional-doc (merge wrap-print-optional-arguments stream-optional-doc))

(def test-output-return-doc {"out" "What the tests print to `*out*`, as they run (on nREPL 1.3 and later)."
                             "err" "What the tests print to `*err*`, as they run (on nREPL 1.3 and later)."})

(def test-run-return-doc (merge fail-fast-doc timing-info-return-doc stream-return-doc test-output-return-doc))

(def-wrapper wrap-test cider.nrepl.middleware.test/handle-test
  {:doc "Middleware that handles testing requests."
   ;; Expect piggieback so that, for ClojureScript, the `eval` op we synthesize
   ;; to run the tests flows down into the piggieback middleware, and
   ;; shadow-cljs's, named by an op it handles, so that this does nothing
   ;; without shadow-cljs. Its var would get its middleware added to any stack.
   :expects (cljs/maybe-add-piggieback #{"shadow-remote-init"})
   :requires #{#'session #'wrap-print}
   :handles (merge
             (with-deprecated-aliases
               {"cider/test-var-query"
                {:doc "Run tests specified by the `var-query` and return results. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
                 :requires {"var-query" "A search query specifying the test vars to execute. See Orchard's var query documentation for more details."}
                 :optional test-run-optional-doc
                 :returns test-run-return-doc}
                "cider/test-all"
                {:doc "Run all tests in the project. If `load?` is truthy, all project namespaces are loaded; otherwise, only tests in presently loaded namespaces are run. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
                 :optional test-run-optional-doc
                 :returns test-run-return-doc}
                "cider/test-stacktrace"
                {:doc "Return exception cause and stack frame info for an erring test via the `stacktrace` middleware. The error to be retrieved is referenced by namespace, var name, and assertion index within the var."
                 :optional wrap-print-optional-arguments}
                "cider/retest"
                {:doc "Rerun all tests that did not pass when last run. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
                 :optional test-run-optional-doc
                 :returns test-run-return-doc}})
             ;; `cider/test` is deprecated in favour of a *different* op
             ;; (`cider/test-var-query`), so it keeps its bespoke notice.
             {"cider/test"
              {:doc "[DEPRECATED - use `cider/test-var-query` instead] Run tests in the specified namespace and return results. This accepts a set of `tests` to be run; if nil, runs all tests. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
               :optional test-run-optional-doc
               :returns test-run-return-doc}
              "test"
              {:doc "[DEPRECATED - use `cider/test-var-query` instead] Run tests in the specified namespace and return results. This accepts a set of `tests` to be run; if nil, runs all tests. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
               :optional test-run-optional-doc
               :returns test-run-return-doc}})})

(def-wrapper wrap-trace cider.nrepl.middleware.trace/handle-trace
  {:clojure-only? true
   :doc     "Toggle tracing of a given var."
   :handles (merge
             (with-deprecated-aliases
               {"cider/toggle-trace-var"
                {:doc      "Toggle tracing of a given var."
                 :requires {"sym" "The symbol to trace"
                            "ns"  "The current namespace"}
                 :returns  {"var-status" "The result of tracing operation"
                            "var-name"   "The fully-qualified name of the traced/untraced var"}}
                "cider/toggle-trace-ns"
                {:doc      "Toggle tracing of a given ns."
                 :requires {"ns" "The namespace to trace"}
                 :returns  {"ns-status" "The result of tracing operation"}}})
             ;; These ops are new and never had unnamespaced aliases.
             {"cider/list-traced"
              {:doc     "List the vars and namespaces that are currently traced."
               :returns {"traced-vars" "A list of the fully-qualified names of the traced vars"
                         "traced-nses" "A list of the names of the traced namespaces"}}
              "cider/untrace-all"
              {:doc     "Untrace every currently traced var and namespace."
               :returns {"untraced-count" "The number of vars that were untraced"}}
              "cider/trace-subscribe"
              {:doc     "Open a streaming subscription that delivers a message for each traced call and return until `cider/trace-unsubscribe`. While subscribed, trace output is routed to the subscriber instead of the REPL."
               :returns {"cider/trace-subscribe" "The id of the subscription, to be passed to `cider/trace-unsubscribe`"
                         "cider/trace-event" "A trace event, with `id`, `phase` (\"call\", \"return\" or \"throw\"), `name`, `depth`, and `args` (on a call), `value` (on a return) or `error`, the class and message of what the call threw (on a throw)"}}
              "cider/trace-unsubscribe"
              {:doc      "Stop streaming trace events for the given subscription."
               :requires {"subscription" "The id of the subscription to remove"}
               :returns  {"cider/trace-unsubscribe" "The id of the removed subscription"}}})})

(def-wrapper wrap-tap cider.nrepl.middleware.tap/handle-tap
  {:doc     "Stream values sent to `tap>` to the client and inspect them."
   :handles {"cider/tap-subscribe"
             {:doc     "Open a streaming subscription that delivers a summary of each value sent to `tap>` until `cider/tap-unsubscribe`. Works in ClojureScript too (by polling the runtime), but cljs tapped values aren't inspectable."
              :returns {"cider/tap-subscribe" "The id of the subscription, to be passed to `cider/tap-unsubscribe`"
                        "cider/tap-value" "A tapped value summary, with `summary`, `type`, when counted `count`, and (Clojure only) an `idx` for `cider/tap-inspect`"}}
             "cider/tap-unsubscribe"
             {:doc      "Stop streaming tapped values for the given subscription."
              :requires {"subscription" "The id of the subscription to remove"}
              :returns  {"cider/tap-unsubscribe" "The id of the removed subscription"}}
             "cider/tap-inspect"
             {:doc      "Start an inspector session on a retained tapped value."
              :requires {"idx" "The index of the tapped value to inspect"}
              :returns  {"value" "The inspector rendering of the tapped value"}}}})

(def-wrapper wrap-tracker cider.nrepl.middleware.track-state/handle-tracker
  mw/ops-that-can-eval
  {:doc "Under its normal operation mode, enhances the `eval` op by notifying the client of the current REPL state.
You can also request to compute the info directly by requesting the \"cider/get-state\" op."
   :requires #{#'session}
   :expects (cljs/maybe-add-piggieback mw/ops-that-can-eval)
   :handles {"cider/get-state"
             {:doc "Return the current REPL state: its type and any namespaces changed since the last notification."
              :returns {"status" "done"
                        "repl-type" "`:clj` or `:cljs`."
                        "changed-namespaces" "A map of namespaces to `{:aliases ,,, :interns ,,,}`"}}}})

(def-wrapper wrap-undef cider.nrepl.middleware.undef/handle-undef
  {:clojure-only? true
   :doc "Middleware to undefine a symbol in a namespace."
   :handles
   (with-deprecated-aliases
     {"cider/undef" {:doc "Undefine a symbol"
                     :requires {"sym" "The symbol to undefine"
                                "ns" "The namespace is which to resolve sym (falls back to *ns* if not specified)"}
                     :returns {"status" "done"
                               "undef" "The symbol that was undefined."}}
      "cider/undef-all" {:doc "Undefine all aliases and symbols in a namespace"
                         :requires {"ns" "The namespace to operate on"}
                         :returns {"status" "done"
                                   "undef-all" "The namespace whose mappings were cleared."}}})})

(def-wrapper wrap-version cider.nrepl.middleware.version/handle-version
  {:doc "Provides CIDER-nREPL version information."
   :describe-fn (fn [_] {:cider-version version/version}) ;; For the "describe" op. Merged into `:aux`.
   :handles
   {"cider-version"
    {:doc "Returns the version of the CIDER-nREPL middleware."
     :requires {}
     :returns {"cider-version" "CIDER-nREPL's version map."
               "status" "done"}}}})

(def-wrapper wrap-xref cider.nrepl.middleware.xref/handle-xref
  {:clojure-only? true
   :doc "Middleware that provides find references functionality."
   :handles (merge
             (with-deprecated-aliases
               {"cider/fn-refs"
                {:doc "Look up functions that reference a particular function."
                 :requires {"sym" "The symbol to lookup"
                            "ns" "The current namespace"}
                 :returns {"fn-refs" "A list of function references, with a `:name :doc :file :file-url :line :column` structure."
                           "status" "done"}}
                "cider/fn-deps"
                {:doc "Look up the function dependencies of particular function."
                 :requires {"sym" "The symbol to lookup"
                            "ns" "The current namespace"}
                 :returns {"fn-deps" "A list of function deps, with a `:name :doc :file :file-url :line :column` structure."
                           "status" "done"}}})
             ;; These ops are new and never had unnamespaced aliases.
             {"cider/who-implements"
              {:doc "Look up the implementations of a protocol or the dispatch values of a multimethod."
               :requires {"sym" "The symbol to lookup"
                          "ns" "The current namespace"}
               :returns {"who-implements" "A map with `:kind` (\"protocol\", \"multimethod\" or \"other\"); for a protocol an `:impls` list of `:name :file :file-url :line :column` maps, for a multimethod a `:dispatch-values` list."
                         "status" "done"}}
              "cider/type-protocols"
              {:doc "Look up the protocols a type implements."
               :requires {"sym" "The type to lookup"
                          "ns" "The current namespace"}
               :returns {"type-protocols" "A list of protocols, with a `:name :doc :file :file-url :line :column` structure."
                         "status" "done"}}
              "cider/protocols-with-method"
              {:doc "Look up the protocols that declare a method of a given name."
               :requires {"method" "The method name to lookup"}
               :returns {"protocols-with-method" "A list of protocols, with a `:name :doc :file :file-url :line :column` structure."
                         "status" "done"}}})})

(def-wrapper wrap-clojuredocs cider.nrepl.middleware.clojuredocs/handle-clojuredocs
  {:doc "Middleware to find a documents from ClojureDocs."
   :handles (with-deprecated-aliases
              {"cider/clojuredocs-refresh-cache"
               {:doc "Reload exported documents file from ClojureDocs, and store it as a cache."
                :requires {}
                :optional {"export-edn-url" "EDN file URL exported from ClojureDocs. Defaults to \"https://github.com/clojure-emacs/clojuredocs-export-edn/raw/master/exports/export.compact.edn\"."}
                :returns {"status" "\"ok\" if reloading was successful"}}
               "cider/clojuredocs-lookup"
               {:doc "Return a map of information in ClojureDocs."
                :requires {"ns" "The namespace where `sym` will be resolved."
                           "sym" "The symbol to lookup."}
                :returns {"clojuredocs" "A map of information in ClojureDocs."
                          "status" "\"no-doc\" if there is no document matching to `ns` and `symbol`."}}})})

;;; CIDER's nREPL Handler
;;
;; Here everything comes together. We define an nREPL handler
;; that includes all of CIDER's middleware. Note that
;; end users might opt to build custom handlers that don't
;; include every middleware available.

(def cider-middleware mw/cider-middleware) ;; for backwards compatibility

(def cider-nrepl-handler
  "CIDER's nREPL handler."
  ;; Automatically adding Piggieback middleware here because there is no way for
  ;; the user to specify it in this particular handler construction, and thus
  ;; the warnings get printed. Servers started through nrepl.cmdline should
  ;; still specify Piggieback explicitly.
  (->> (cljs/maybe-add-piggieback-mware-sym mw/cider-middleware)
       (mapv resolve-or-fail)
       (apply nrepl-server/default-handler)))
