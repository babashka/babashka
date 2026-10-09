(ns cider.nrepl.middleware.debug
  "Expression-based debugger for clojure code"
  {:author "Artur Malabarba"}
  (:require
   [cider.nrepl.middleware.inspect :refer [swap-inspector!]]
   [cider.nrepl.middleware.util :as util :refer [respond-to]]
   #_[cider.nrepl.middleware.util.cljs :as cljs] ;; BB-PATCH no ClojureScript environment in bb
   [cider.nrepl.middleware.util.eval]
   [cider.nrepl.middleware.util.instrument :as ins]
   [cider.nrepl.middleware.util.nrepl :refer [notify-client]]
   [nrepl.middleware.interruptible-eval :as ieval :refer [*msg*]]
   [orchard.info :as info]
   [orchard.inspect :as inspect]
   [orchard.meta :as m]
   [orchard.print]
   [orchard.stacktrace :as stacktrace])
  (:import #_(cider.nrepl.middleware DebugSupport) #_(clojure.lang Compiler$LocalBinding) ;; BB-PATCH no DebugSupport class or Compiler in sci
(java.util UUID)))

;;;; # The Debugger
;;;
;;; The debugger is divided into two parts, instrument.clj and
;;; debug.clj.
;;;
;;; - instrument.clj (which see), found in the util/ subdir, is
;;;   responsible for navigating a code data structure and
;;;   instrumenting all sexps of interest.
;;;
;;; - debug.clj is the debugger per se. It doesn't actually "look" at
;;;   the code it's being run on. It simply implements the breakpoint
;;;   logic as well as a number of functions to interact with the user
;;;   according to breakpoints placed by instrument.clj.
;;;
;;;   After the repl is started, before the debugger can be used, a
;;;   message must be sent by the client with the "init-debugger" op
;;;   (only one of these is necessary througout a session).
;;;   Afterwards, code can be instrumented by calling
;;;   `instrument-and-eval` on it (through the regular "eval" op).
;;;
;;;   Finally, when a breakpoint is reached due to running
;;;   instrumented code, an `need-debug-input` message is sent to the
;;;   client in response to the message used during initialization.
;;;   Execution of the code will halt until this message is replied.
;;;   It may specify a :prompt, it will specify an :input-type and a
;;;   :key, and it expects an :input key in the reply. The :key must
;;;   be contained in the reply, and :input-type may be:
;;;   - a vector of keywords, in which case one must be returned (but
;;;     note that the repl may convert these to non-keyword strings);
;;;   - the keyword :expression, in which case a single sexp must be
;;;     returned (as a string).

;;;; ## Internal breakpoint logic
;;;
;;; Variables and functions used for navigating between breakpoints.
(def ^:dynamic *skip-breaks*
  "Map used to determine whether to skip a breakpoint.
  Don't set or examine this directly, it is bound in the session binding map,
  use `skip-breaks!` and `skip-breaks?` instead. Its value is reset at the
  beginning each eval session."
  (atom nil))

(defn- random-uuid-str
  "UUID generator."
  []
  (str (UUID/randomUUID)))

(defn- seq=
  "To deal with, eg: (= () nil) => true"
  [a b]
  (= (seq a) (seq b)))

(defn skip-breaks?
  "True if the breakpoint at coordinates should be skipped.

  The `*skip-breaks*` map stores a `mode`, `coordinates`, the `code` that it
  applies to, and a `force?` flag.

  Behaviour depends on the `mode`:
   - :all - return true, skipping all breaks
   - :trace - return false, skip nothing
   - :deeper - return true if the given coordinates are deeper than the
               coordinates stored in `*skip-breaks*`, in the same code
   - :before - return true if the given coordinates represent a place before
               the coordinates in `*skip-breaks*`, in the same code

  For :deeper and :before, if we are not in the same code (i.e. we have stepped
  into another instrumented function and code argument doesn't match old code in
  *skip-breaks*), then return the value of `force?`."
  [coor STATE__]
  (or (and STATE__ @(:skip STATE__))
      (if (seq coor)
        (when-let [{mode :mode skip-coords :coor
                    code-to-skip :code force? :force?} @*skip-breaks*]
          (let [same-defn? (identical? code-to-skip (get-in STATE__ [:msg :code]))]
            (case mode
              ;; From :continue, skip everything.
              :all    true
              ;; From :trace, never skip.
              :trace  false
              ;; From :out, skip some breaks.
              :deeper (if same-defn?
                        (let [parent (take (count skip-coords) coor)]
                          (and (seq= skip-coords parent)
                               (> (count coor) (count parent))))
                        force?)
              ;; From :here, skip some breaks.
              :before (if same-defn?
                        (ins/coord< coor skip-coords)
                        force?))))
        ;; We don't breakpoint top-level sexps, because their return value
        ;; is already displayed anyway.
        true)))

(defn skip-breaks!
  "Set the value of *skip-breaks* for the top-level breakpoint.
  Additional arguments depend on mode, and should be:
   - empty for :all or :trace
   - coordinates, code, and force for :deeper or :before
  See `skip-breaks?`."
  ([mode]
   (skip-breaks! mode nil nil nil))
  ([mode coor code force?]
   (reset! *skip-breaks*
           (case mode
             (nil false)       nil
             (:all :trace)     {:mode mode}
             (:deeper :before) {:mode mode :coor coor :code code :force? force?}))))

(defn- abort!
  "Stop current eval thread.
  This does not quit the repl, it only interrupts an eval operation."
  []
  (if (map? *msg*)
    (do
      (respond-to *msg* :value 'QUIT)
      #_(when-let [t (:thread (meta (:session *msg*)))] ;; BB-PATCH stop the eval the way Thread.stop does on the JVM
        ;; n.u.threading namespace appeared in nREPL 1.3, don't fail when on
        ;; earlier nREPL versions.
        ((requiring-resolve 'nrepl.util.threading/interrupt-stop) t))
(throw (ThreadDeath.)))
    ;; We can't really abort if there's no *msg*, so we do our best
    ;; impression of that. This is only used in some panic situations,
    ;; the user won't be offered the :quit option if there's no *msg*.
    (skip-breaks! :all)))

(defn- filter-env
  "Remove internal vars and macro locals with __ in their names."
  [locals]
  (remove (fn [[k]] (re-find  #"__" (name k))) locals))

;;; Politely borrowed from clj-debugger.
(defn locals-capturer
  "Turn a macro's &env into code that produces a map of locals."
  [env]
  `(-> {}
       ~@(for [[sym bind] (filter-env env)
               #_#_:when (instance? Compiler$LocalBinding bind)] ;; BB-PATCH sci's &env maps each local to a symbol
           #_`(DebugSupport/assoc ~(name sym) ~(.sym ^Compiler$LocalBinding bind)) ;; BB-PATCH
`(assoc '~sym ~sym))))

;;;; ## Getting user input
;;;
;;; `wrap-debug` receives an initial message from the client, stores
;;; it in `debugger-message`, and `breakpoint` sends replies on it when
;;; asking for input.

(defonce ^{:doc "Message used to communicate with the client. Stored by the
 \"init-debugger\" op, and used by `read-debug-input` to ask for debug input through
 the :need-debug-input status."}
  debugger-message (atom nil))

(defonce ^{:doc "Map atom holding all unprocessed debug inputs.
This is where the \"debug\" op stores replies received for debug
input requests. `read-debug-input` will halt until it finds its input in
this map (identified by a key), and will `dissoc` it afterwards."}
  promises (atom {}))

(defonce print-options (atom nil))
(defonce step-in-to-next? (atom false))

(def ^:private nrepl-1-5+?
  (cider.nrepl.middleware.util.nrepl/satisfies-version? 1 5))

(defn pr-short
  "Like `pr-str` but limited in length and depth."
  [x]
  (binding [*print-length* (or (:length @print-options)
                               *print-length*)
            *print-level*  (or (:level @print-options)
                               *print-level*)
            orchard.print/*max-atom-length* 500
            ;; CIDER overlay currently truncates values at (* 3 (window-width)).
            ;; Total length of 2000 is enough to never produce less than that.
            orchard.print/*max-total-length* 2000]
    ;; TODO: Make it possible to use a random print function here
    (orchard.print/print-str x)))

(defn- locals-for-message
  "Prepare a map of local variables for sending through the repl."
  [locals]
  (mapv (partial mapv pr-short) locals))

(defn debugger-send
  "Send a response through debugger-message."
  [& r]
  (when (not @debugger-message)
    (throw (Exception. "Debugger not initialized!")))
  (try
    (apply respond-to @debugger-message r)
    (catch java.net.SocketException _
      (reset! debugger-message nil))))

(defmacro try-if-let
  "Try binding `sym` to `val` end eval `success-expr` or `error-expr` on error.
  On error send an eval-error message through `debugger-message` channel."
  {:style/indent 1}
  [[sym val] success-expr error-expr]
  `(let [error# (gensym)
         ~sym (try
                ~val
                ;; Borrowed from `interruptible-eval/evaluate`.
                (catch Exception e#
                  (let [root-ex# (#'clojure.main/root-cause e#)]
                    (when-not (instance? ThreadDeath root-ex#)
                      (debugger-send
                       {:status :eval-error
                        :causes [(let [causes# (stacktrace/analyze e#)]
                                   (when (coll? causes#) (last causes#)))]})))
                  error#))]
     (if (= error# ~sym)
       ~error-expr
       ~success-expr)))

(defn- read-debug-input
  "Like `read`, but reply is sent through `debugger-message`."
  [dbg-state input-type prompt]
  (let [key (random-uuid-str)
        input (promise)]
    (swap! promises assoc key input)
    (debugger-send (-> dbg-state
                       (assoc :status :need-debug-input
                              :key key
                              :prompt prompt
                              :input-type input-type)
                       (update :locals locals-for-message)))
    (binding [*ns* (find-ns (symbol (:original-ns dbg-state)))]
      (try (read-string @input)
           (finally (swap! promises dissoc key))))))

(def ^:dynamic *tmp-locals*
  "Many objects don't have reader representation and we cannot simply splice
  them in `eval-with-locals`. Instead, we use this temporary dynamic var to bind
  them during `eval`.")

(defn- eval-with-locals
  "`eval` form wrapped in a let of the locals map.
  If an exception is thrown, it is caught and sent to the client, and this
  function returns nil. `dbg-state` is a metadata map received from `break'."
  [form dbg-state]
  (let [ns (ns-name *ns*)]
    (try
      (binding [*tmp-locals* (:locals dbg-state)
                ;; evaluate in the instrumentation ns
                *ns* (find-ns (symbol (:original-ns dbg-state)))]
        (eval `(let ~(vec (mapcat #(list % `(get *tmp-locals* '~%))
                                  (keys *tmp-locals*)))
                 ~form)))
      (finally
        (try
          ;; Restore original ns in case it was changed by the evaluated
          ;; code. See https://github.com/clojure-emacs/cider/issues/1604.
          (in-ns ns)
          (catch IllegalStateException _))))))

(defn- read-eval-expression
  "Read and eval an expression from the client.
  `dbg-state` is a map received from `break`, and `prompt` is added into
  the :prompt key."
  ([prompt dbg-state] (read-eval-expression prompt dbg-state nil))
  ([prompt dbg-state code]
   (eval-with-locals (or code (read-debug-input dbg-state :expression prompt))
                     dbg-state)))

(defn- debug-inspect
  "Inspect `inspect-value`."
  [inspect-value]
  (binding [*print-length* nil
            *print-level* nil]
    (->> (swap-inspector! @debugger-message inspect/start inspect-value)
         :rendered pr-str)))

(defn- debug-stacktrace
  "Send the stacktrace of `value` if it is an exception.
  Otherwise, create a dummy exception to view the call stack at the current location."
  [value]
  (debugger-send
   {:status :stack
    :causes (if (instance? Throwable value)
              (stacktrace/analyze value)
              [{:class      "StackTrace"
                :message    "Harmless user-requested stacktrace"
                :stacktrace (-> (Exception. "Dummy")
                                stacktrace/analyze
                                last :stacktrace)}])}))

(def debug-commands
  "An unsorted set of commands supported by the debugger."
  #{:continue
    :continue-all
    :eval
    :here
    :in
    :inject
    :locals
    :next
    :out
    :inspect
    :inspect-prompt
    :quit
    :stacktrace
    :trace})

(defn read-debug-command
  "Read and take action on a debugging command.
  Ask for one of the following debug commands using `read-debug-input`:

    next: Return value.
    continue: Skip the current breakpoint.
    continue-all: Skip breakpoints for the remainder of this eval session.
    in: Step into a function
    out: Skip breakpoints in the current sexp.
    here: Skip all breakpoints up till specified coordinate `coord`
    inspect: Inspect the current expression
    inspect-prompt: Prompt for an expression to evaluate and inspect it.
    locals: Inspect local variables.
    inject: Evaluate an expression and return it.
    eval: Evaluate an expression, display result, and prompt again.
    stacktrace: Print the current stacktrace, and prompt again.
    trace: Continue, printing intermediate expressions and their values.
    quit: Abort current eval session.

  Response received can be any one of these values. It can also be a map
  whose :response entry is one of these values, which can thus be used to
  provide additional parameters. For instance, if this map has a :code entry,
  its value is used for operations such as :eval, which would otherwise
  interactively prompt for an expression."
  [coor value locals STATE__]
  (loop [value     value
         dbg-state (assoc (:msg STATE__)
                          :debug-value (pr-short value)
                          :coor coor
                          :locals locals)]
    (let [commands     (cond-> debug-commands
                         (not (map? *msg*))         (disj :quit)
                         (nil? (:locals dbg-state)) (disj :eval :inject :locals :inspect)
                         #_#_(cljs/grab-cljs-env *msg*) identity) ;; BB-PATCH
          response-raw (read-debug-input dbg-state commands nil)
          dbg-state    (dissoc dbg-state :inspect)

          {:keys [code coord response force?]} (if (map? response-raw)
                                                 response-raw
                                                 {:response response-raw})]
      (reset! step-in-to-next? false)
      (case response
        :next       value
        :in         (do (reset! step-in-to-next? true)
                        value)
        :continue   (do (reset! (:skip STATE__) true)
                        value)
        :continue-all (do (skip-breaks! :all)
                          value)
        :out        (do (skip-breaks! :deeper (butlast (:coor dbg-state)) (:code dbg-state) force?)
                        value)
        :here       (do (skip-breaks! :before coord (:code dbg-state) force?)
                        value)
        :stacktrace (do (debug-stacktrace value)
                        (recur value dbg-state))
        :trace      (do (skip-breaks! :trace)
                        value)
        :locals     (->> (debug-inspect (:locals dbg-state))
                         (assoc dbg-state :inspect)
                         (recur value))
        :inspect    (->> (debug-inspect value)
                         (assoc dbg-state :inspect)
                         (recur value))
        :inspect-prompt #_:clj-kondo/ignore (try-if-let [val (read-eval-expression "Inspect value: " dbg-state code)]
                                              (->> (debug-inspect val)
                                                   (assoc dbg-state :inspect)
                                                   (recur value))
                                              (recur value dbg-state))
        :inject     #_:clj-kondo/ignore (try-if-let [val (read-eval-expression "Expression to inject: " dbg-state code)]
                                          val
                                          (recur value dbg-state))
        :eval       #_:clj-kondo/ignore (try-if-let [val (read-eval-expression "Expression to evaluate: " dbg-state code)]
                                          (recur value (assoc dbg-state :debug-value (pr-short val)))
                                          (recur value dbg-state))
        :quit       (abort!)
        (do (abort!)
            (throw (ex-info "Invalid input from `read-debug-input`."
                            {:response-raw response-raw})))))))

(defn print-step-indented [^long depth form value]
  (print (apply str (repeat (dec depth) "| ")))
  (binding [*print-length* 4
            *print-level*  2]
    (pr form))
  (println "=>" (pr-short value)))

(declare debug-reader)

(declare ^:dynamic *top-level-form-meta*) ;; BB-PATCH bound below in instrument-var-for-step-in

(defn instrument-var-for-step-in
  "Attach an instrumented version of the function in `v` as metadata to `v`,
  leaving the contents of the var uninstrumented."
  [v]
  (when-not (::instrumented (meta v))
    (when-let [{:keys [ns file form] :as var-meta} (m/var-code v)]
      (let [full-path (util/transform-value (:file (info/file-info file)))]
        (binding [*ns*   (find-ns ns)
                  *file* file
                  *msg*  (-> *msg*
                             (merge var-meta)
                             (assoc :file full-path))
                  ;; BB-PATCH report the stepped-into var's code, file and line, not the outer eval's
*top-level-form-meta* {:line (:line var-meta)
                       :column (:column var-meta)
                       :ns (find-ns ns)
                       ::form-info {:code (:code var-meta)
                                    :file full-path}}]
          (let [iform `(with-initial-debug-bindings
                         ~(ins/instrument-tagged-code (debug-reader form)))]
            ;; (ins/print-form iform true)
            (eval iform)
            (let [instrumented @v]
              (eval form)
              (alter-meta! v assoc ::instrumented instrumented))))))))

(defn safe-to-debug?
  "Some namespaces are not safe to debug, because doing so can cause a stack
  overflow that crashes the nrepl process."
  [ns]
  (not (#{'clojure.core} (ns-name ns))))

(defn step-in?
  "Return true if we can and should step in to the function in the var `v`.
  The \"should\" part is determined by the value in `step-in-to-next?`, which
  gets set to true by the user sending the \"step in\" command."
  [v coor code]
  (when (and @step-in-to-next?
             (not (skip-breaks? coor code))
             (not= :trace (:mode @*skip-breaks*))
             (not (:instrumented (meta v))))
    (try
      (instrument-var-for-step-in v)
      true
      (catch Exception e
        (notify-client @debugger-message (.getMessage e) :error)
        false))))

(defn looks-step-innable?
  "Decide whether a form looks like a call to a function that we could
  instrument and step into.
  You should prefer the second arity with the `&env` argument
  to handle a local shadowing correctly."
  ([form]
   (looks-step-innable? nil form))
  ([&env form]
   (when (and (seq? form) (symbol? (first form)))
     (let [v (resolve &env (first form)) ; Note: special forms resolve to nil
           m (meta v)]
       ;; We do not go so far as to actually try to read the code of the function
       ;; at this point, which is at macroexpansion time.
       (and v
            (safe-to-debug? (:ns m))
            (not (:macro m))
            (not (:inline m)))))))

;;;  ## Breakpoint logic

(def ^:dynamic *tmp-forms* (atom {}))
(def ^:dynamic *do-locals* true)
#_:clj-kondo/ignore
(def ^:dynamic ^:private *found-debugger-tag*)
#_:clj-kondo/ignore
(def ^{:dynamic true
       :doc "Per-form metadata (line/column and `::form-info`) of the top-level
  form being instrumented.  When bound, `with-initial-debug-bindings` uses it for
  accurate per-form coordinates; otherwise it falls back to the whole-eval
  `*msg*`.  Bound by the debugger's instrumenting eval and by
  `enlighten/eval-with-enlighten`.  Must stay unbound by default - giving it a
  root value would make `(bound? ...)` always true and break the fallback."}
  *top-level-form-meta*)

(defmacro with-initial-debug-bindings
  "Let-wrap `body` with STATE__ map containing code, file, line, column etc.
  STATE__ is an anaphoric variable available to all breakpoint macros. Ends with
  __ to avid conflicts with user locals and to signify that it's an internal
  variable which is cleaned in `locals-capturer` along other clojure's
  temporaries."
  {:style/indent 0}
  [& body]
  ;; NOTE: *msg* is the message that instrumented the function,
  `(let [~'STATE__ {:msg ~(if (bound? #'*top-level-form-meta*)
                            (let [{:keys [line column ns], form-info ::form-info}
                                  *top-level-form-meta*
                                  {:keys [code file original-id]} form-info]
                              {:code code
                               ;; Passing clojure.lang.Namespace object
                               ;; as :original-ns breaks nREPL in bewildering
                               ;; ways.
                               ;; NOTE: column numbers in the response map
                               ;; start from 1 according to Clojure.
                               ;; This is not a bug and should be converted to
                               ;; 0-based indexing by the client if necessary.
                               :original-ns (str (or ns *ns*))
                               :original-id original-id
                               :file file, :line line, :column column})
                            (let [{:keys [code file line column ns id]} *msg*]
                              {:code code
                               :original-ns (str (or ns *ns*))
                               :original-id id
                               :file file, :line line, :column column}))
                    ;; the coor of first form is used as the debugger session id
                    :session-id (atom nil)
                    :skip (atom false)
                    :forms @*tmp-forms*}]
     ~@body))

(defmacro breakpoint-with-initial-debug-bindings
  {:style/indent 1}
  [form dbg-state original-form]
  `(with-initial-debug-bindings
     (breakpoint
      ~form ~dbg-state ~original-form)))

(defmacro breakpoint-if-interesting-with-initial-debug-bindings
  {:style/indent 1}
  [form dbg-state original-form]
  `(with-initial-debug-bindings
     (breakpoint-if-interesting
      ~form ~dbg-state ~original-form)))

(defmacro breakpoint-if-exception-with-initial-debug-bindings
  {:style/indent 1}
  [form dbg-state original-form]
  `(with-initial-debug-bindings
     (breakpoint-if-exception
      ~form ~dbg-state ~original-form)))

(defn break
  "Breakpoint function.
  Send the result of form and its coordinates to the client and wait for
  response with `read-debug-command`'."
  [coor val locals STATE__]
  (if-let [first-coor @(:session-id STATE__)]
    ;; Check if the instrumented function is being evaluated
    ;; from the root again.
    (when (= first-coor coor)
      ;; Clear any previously set skip state.
      (reset! (:skip STATE__) false)
      (skip-breaks! false))
    (reset! (:session-id STATE__) coor))
  (cond
    (skip-breaks? coor STATE__) val
    ;; The length of `coor` is a good indicator of current code
    ;; depth.
    (= (:mode @*skip-breaks*) :trace)
    (do (print-step-indented (count coor) (get-in STATE__ [:forms coor]) val)
        val)
    ;; Most common case - ask for input.
    :else
    (read-debug-command coor val locals STATE__)))

(defn apply-instrumented-maybe
  "Apply var-fn or its instrumented version to args."
  [var-fn args coor STATE__]
  (let [stepin (step-in? var-fn coor STATE__)]
    (apply #_(if stepin ;; BB-PATCH call the var if its source was not found
               (::instrumented (meta var-fn))
               var-fn)
(or (when stepin (::instrumented (meta var-fn)))
    var-fn)
           args)))

(defmacro expand-break
  "Internal macro to avoid code repetition in `breakpoint-if-interesting`."
  [form {:keys [coor]} original-form]
  (let [val-form (if (looks-step-innable? &env form)
                   (let [[fn-sym & args] form]
                     `(apply-instrumented-maybe (var ~fn-sym) [~@args] ~coor ~'STATE__))
                   form)
        locals (when *do-locals*
                 (locals-capturer &env))]
    ;; Keep original forms in a separate atom to save some code
    ;; size. Unfortunately same trick wouldn't work for locals.
    (swap! *tmp-forms* assoc coor original-form)
    #_`(DebugSupport/doBreak ~coor ~val-form ~locals ~'STATE__) ;; BB-PATCH no DebugSupport class
`(break ~coor ~val-form ~locals ~'STATE__)))

(def irrelevant-return-value-forms
  "Set of special-forms whose return value we don't care about.
  When instrumenting, these will not be wrapped in a breakpoint."
  '#{def fn* deftype* reify* monitor-enter monitor-exit})

(defn- uninteresting-form?
  "Checks if a form is uninteresting and should be skipped
  for instrumentation. Uninteresting forms are symbols that resolve to `clojure.core`
  (taking locals into account), and sexps whose head is present in
  `irrelevant-return-value-forms`."
  [&env form]
  (or (and (symbol? form)
           (try
             (-> (resolve &env form) meta :ns
                 ns-name #{'clojure.core 'schema.core})
             (catch Exception _ nil)))
      (and (seq? form)
           (irrelevant-return-value-forms (first form)))))

(defmacro breakpoint
  "Wrap form in a breakpoint unconditionally."
  [form {:keys [coor] :as dbg-state} original-form]
  (let [condition (:break/when (meta form))]
    (if condition
      ;; If there is a condition and it is falsy, we need to skip
      ;; the current level (:deeper than parent coor), but only
      ;; once. Next time, we need to test the condition again.
      `(let [old-breaks# @*skip-breaks*]
         (when-not ~condition
           (skip-breaks! :deeper ~(vec (butlast coor)) (:code (:msg ~'STATE__)) false))
         (try
           (expand-break ~form ~dbg-state ~original-form)
           ;; in case :continue-all was requested in a deeper level
           ;; we don't want go back to the old-breaks
           (finally (when (not= :all (:mode @*skip-breaks*))
                      (reset! *skip-breaks* old-breaks#)))))
      `(expand-break ~form ~dbg-state ~original-form))))

(defmacro breakpoint-if-interesting
  "Wrap form in a breakpoint if it looks interesting.
  Uninteresting forms are symbols that resolve to `clojure.core`
  (taking locals into account), and sexps whose head is present in
  `irrelevant-return-value-forms`. Used as :breakfunction in `tag-form`."
  [form dbg-state original-form]
  (if (uninteresting-form? &env form)
    form
    `(breakpoint ~form ~dbg-state ~original-form)))

(defmacro breakpoint-if-exception
  "Wrap form in a try-catch that has breakpoint on exception.
  Used as :breakfunction in `tag-form`."
  [form dbg-state original-form]
  `(try ~form
        (catch Throwable ex#
          (let [~'STATE__ (assoc-in ~'STATE__ [:msg :caught-msg] (.getMessage ex#))
                break-result#  (expand-break ex# ~dbg-state ~original-form)]
            (if (= ex# break-result#)
              ;; if they continued then rethrow the exception
              (throw ex#)
              ;; otherwise return the value they injected
              break-result#)))))

;;; ## Data readers
;;
;; Set in `src/data_readers.clj`.

(defn- found-debugger-tag []
  (when (bound? #'*found-debugger-tag*)
    (set! *found-debugger-tag* true)))

(defn breakpoint-reader
  "#break reader. Mark `form` for breakpointing."
  [form]
  (found-debugger-tag)
  (ins/tag-form form #'breakpoint-with-initial-debug-bindings true))

(defn debug-reader
  "#dbg reader. Mark all forms in `form` for breakpointing.
  `form` itself is also marked."
  [form]
  (found-debugger-tag)
  (ins/tag-form (ins/tag-form-recursively form #'breakpoint-if-interesting)
                #'breakpoint-if-interesting-with-initial-debug-bindings
                ;; Force the top-level form to be wrapped even when it's a bare
                ;; collection literal (which isn't "interesting" on its own):
                ;; otherwise its interior breakpoints reference the STATE__
                ;; anaphor that only the top-level wrapper binds (#1016).
                true))

(defn break-on-exception-reader
  "#exn reader. Wrap `form` in try-catch and break only on exception"
  [form]
  (found-debugger-tag)
  (ins/tag-form form #'breakpoint-if-exception-with-initial-debug-bindings true))

(defn debug-on-exception-reader
  "#dbgexn reader. Mark all forms in `form` for breakpointing on exception.
  `form` itself is also marked."
  [form]
  (found-debugger-tag)
  (ins/tag-form (ins/tag-form-recursively form #'breakpoint-if-exception)
                #'breakpoint-if-exception-with-initial-debug-bindings
                ;; See `debug-reader` (#1016).
                true))

(defn- method-too-large?
  "True if `e` (or its cause) is the JVM's \"Method code too large!\" error, which
  the instrumented code can trip when a form is big enough to blow the 64KB method
  limit once breakpoints are woven in."
  [^Throwable e]
  (boolean
   (some #(some->> ^Throwable % .getMessage (re-matches #".*Method code too large!.*"))
         [e (.getCause e)])))

(defn instrument-and-eval [form]
  (with-bindings (if nrepl-1-5+? {#'*top-level-form-meta* (meta form)} {})
    ;; Always establish the STATE__ bindings around the whole instrumented form.
    ;; `#dbg`/`#break` also bind STATE__ per-call via their top-level breakfunction
    ;; (which shadows this one), but forms that don't - notably the `#light`
    ;; enlighten reader, whose `light-form` references STATE__ directly - would
    ;; otherwise hit an unbound STATE__ (#1017). Mirrors `eval-with-enlighten`.
    (let [form1 `(with-initial-debug-bindings
                   ~(ins/instrument-tagged-code form))]
      (try
        (binding [*tmp-forms* (atom {})]
          (eval form1))
        (catch java.lang.RuntimeException e
          (if (method-too-large? e)
            (do (notify-client *msg*
                               (str "Method code too large!\n"
                                    "Locals and evaluation in local context won't be available.")
                               :warning)
                ;; Re-try without locals, which produces smaller code.
                (try
                  (binding [*tmp-forms* (atom {})
                            *do-locals* false]
                    (eval form1))
                  (catch java.lang.RuntimeException e2
                    ;; Still too large even without locals: we can't instrument
                    ;; it at all. Report a clear error and leave the form
                    ;; unevaluated - better than a raw compiler stacktrace, and
                    ;; better than silently evaluating it uninstrumented, which
                    ;; looks like debugging is on when it isn't (#750).
                    (if (method-too-large? e2)
                      (do (notify-client *msg*
                                         (str "This form is too large to instrument for debugging - "
                                              "it exceeds the JVM's per-method bytecode limit even "
                                              "without local capture. It was not evaluated; break it "
                                              "into smaller functions to debug it.")
                                         :error)
                          nil)
                      (throw e2)))))
            (throw e)))))))

(def ^:dynamic *debug-data-readers*
  "Reader macros like #dbg which cause code to be instrumented when present."
  '#{dbg dbg! break break! light})

;;; ## Middleware
(defn- maybe-debug
  "Return msg, prepared for debugging if code contains debugging macros."
  [{:keys [code ns] :as msg}]
  ;; The best way of checking if there's a #break reader-macro in
  ;; `code` is by reading it, in which case it toggles `has-debug?`.
  (let [has-debug?  (atom false)
        ;; Don't return nil in reader (https://dev.clojure.org/jira/browse/CLJ-1138)
        fake-reader (fn [x] (reset! has-debug? true) x)]
    (binding [*ns* (find-ns (symbol (or ns "user")))
              *data-readers* (->> (repeat fake-reader)
                                  (zipmap *debug-data-readers*)
                                  (merge *data-readers*))]
      (try
        ;; new-line in REPL always throws; skip for debug convenience
        (when (> (count code) 3)
          (read-string {:read-cond :allow} code))
        (catch Exception _e)))
    (if @has-debug?
      ;; Technically, `instrument-and-eval` acts like a regular eval
      ;; if there are no debugging macros. But we still only use it
      ;; when we know it's necessary.
      (assoc msg :eval "cider.nrepl.middleware.debug/instrument-and-eval")
      ;; If there was no reader macro, fallback on regular eval.
      msg)))

(defn- maybe-debug-nrepl-1-5+
  "Alternative implementation of `maybe-debug` that is only supported with nREPL
  1.5+ or higher. This version supports forms compiled by `load-file` and
  doesn't perform double read like the older version."
  [msg]
  (let [read-fn
        (fn [options reader]
          (binding [*found-debugger-tag* false
                    ;; BB-PATCH bb does not read data_readers.clj from bundled sources
*data-readers* (merge *data-readers*
                      {'dbg debug-reader
                       'break breakpoint-reader
                       'dbg! debug-on-exception-reader
                       'break! break-on-exception-reader})]
            ;; Read the form normally and then check if the flag turned on that
            ;; tells us the form contains any debugger reader tags.
            (let [[form code] (ins/comment-trimming-read+string options reader)]
              (if *found-debugger-tag*
                ;; Attach the original (but cleaned up) source code for the
                ;; instrumenter to set up correct debugger state later.
                (vary-meta form assoc
                           ::form-info {:code code
                                        :file (:file msg)
                                        :original-id (:id msg)})
                form))))]
    (cond-> (assoc msg ::ieval/read-fn read-fn)
      ;; Install the debugger's eval function only when the message doesn't
      ;; carry one already. nREPL ignores the `:eval` slot (which the
      ;; enlighten middleware uses) whenever `::ieval/eval-fn` is set, so
      ;; setting ours unconditionally would disable the other middleware.
      (not (or (::ieval/eval-fn msg) (:eval msg)))
      (assoc ::ieval/eval-fn (cider.nrepl.middleware.util.eval/eval-dispatcher
                              instrument-and-eval ::form-info)))))

(defn- initialize
  "Initialize the channel used for debug-input requests."
  [{:keys [:nrepl.middleware.print/options] :as msg}]
  (when (map? @debugger-message)
    (debugger-send :status :done))
  ;; The above is just bureaucracy. The below is important.
  (reset! @#'print-options options)
  (reset! debugger-message msg))

(defn- instrumented-defs-reply
  "Reply to `msg` with an alist of instrumented defs on the \"list\" entry."
  [msg]
  (let [defs (->> (all-ns)
                  (map #(cons (ns-name %) (ins/list-instrumented-defs %)))
                  (filter second))]
    (respond-to msg :status :done, :list (util/transform-value defs))))

(defn handle-debug
  [handler {:keys [op input session] :as msg}]
  (case op
    "eval" (do (when (instance? clojure.lang.Atom session)
                 (swap! session assoc #'*skip-breaks* (atom nil)))
               (handler (if nrepl-1-5+?
                          (maybe-debug-nrepl-1-5+ msg)
                          (maybe-debug msg))))
    ("cider/debug-instrumented-defs" "debug-instrumented-defs") (instrumented-defs-reply msg)
    ("cider/debug-input" "debug-input") (when-let [pro (@promises (:key msg))]
                                          (deliver pro input))
    ("cider/init-debugger" "init-debugger") (initialize msg)
    ;; else
    (handler msg)))
