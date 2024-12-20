;;  Modified / stripped version of clojure.main for use with babashka on
;;  GraalVM.
;;
;; Copyright (c) Rich Hickey All rights reserved. The use and
;; distribution terms for this software are covered by the Eclipse Public
;; License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can be found
;; in the file epl-v10.html at the root of this distribution. By using this
;; software in any fashion, you are agreeing to be bound by the terms of
;; this license. You must not remove this notice, or any other, from this
;; software.

;; Originally contributed by Stephen C. Gilardi

(ns ^{:doc "Top-level main function for Clojure REPL and scripts."
      :author "Stephen C. Gilardi and Rich Hickey"
      :no-doc true}
    babashka.impl.clojure.main
  (:refer-clojure :exclude [with-bindings]))

(set! *warn-on-reflection* true)

(defn demunge
  "Given a string representation of a fn class,
  as in a stack trace element, returns a readable version."
  [fn-name]
  (clojure.lang.Compiler/demunge fn-name))

(defmacro with-bindings
  "Executes body in the context of thread-local bindings for several vars
  that often need to be set!: *ns* *warn-on-reflection* *math-context*
  *print-meta* *print-length* *print-level* *compile-path*
  *command-line-args* *1 *2 *3 *e"
  [& body]
  `(binding [*ns* *ns*
             *warn-on-reflection* *warn-on-reflection*
             *math-context* *math-context*
             *print-meta* *print-meta*
             *print-length* *print-length*
             *print-level* *print-level*
             *print-namespace-maps* true
             *data-readers* *data-readers*
             *default-data-reader-fn* *default-data-reader-fn*
             *command-line-args* *command-line-args*
             *unchecked-math* *unchecked-math*
             *assert* *assert*
             ;; clojure.spec.alpha/*explain-out* clojure.spec.alpha/*explain-out*
             *1 nil
             *2 nil
             *3 nil
             *e nil]
     ~@body))

(def ^{:doc "A sequence of lib specs that are applied to `require`
by default when a new command-line REPL is started."} repl-requires
  '[[clojure.repl :refer (source apropos pst dir doc find-doc)]
    [clojure.pprint :refer (pp pprint)]])

(defmacro with-read-known
  "Evaluates body with *read-eval* set to a \"known\" value,
   i.e. substituting true for :unknown if necessary."
  [& body]
  `(binding [*read-eval* (if (= :unknown *read-eval*) true *read-eval*)]
     ~@body))

(defn repl
  "Generic, reusable, read-eval-print loop. By default, reads from *in*,
  writes to *out*, and prints exception summaries to *err*. If you use the
  default :read hook, *in* must either be an instance of
  LineNumberingPushbackReader or duplicate its behavior of both supporting
  .unread and collapsing CR, LF, and CRLF into a single \\newline. Options
  are sequential keyword-value pairs. Available options and their defaults:
     - :init, function of no arguments, initialization hook called with
       bindings for set!-able vars in place.
       default: #()
     - :need-prompt, function of no arguments, called before each
       read-eval-print except the first, the user will be prompted if it
       returns true.
       default: (if (instance? LineNumberingPushbackReader *in*)
                  #(.atLineStart *in*)
                  #(identity true))
     - :prompt, function of no arguments, prompts for more input.
       default: repl-prompt
     - :flush, function of no arguments, flushes output
       default: flush
     - :read, function of two arguments, reads from *in*:
         - returns its first argument to request a fresh prompt
           - depending on need-prompt, this may cause the repl to prompt
             before reading again
         - returns its second argument to request an exit from the repl
         - else returns the next object read from the input stream
       default: repl-read
     - :eval, function of one argument, returns the evaluation of its
       argument
       default: eval
     - :print, function of one argument, prints its argument to the output
       default: prn
     - :caught, function of one argument, a throwable, called when
       read, eval, or print throws an exception or error
       default: repl-caught"
  [& options]
  (let [{:keys [init need-prompt prompt flush read eval print caught]}
        (apply hash-map options)
        request-prompt (Object.)
        request-exit (Object.)
        read-eval-print
        (fn []
          (try
            (let [input (read request-prompt request-exit)]
              (or (#{request-prompt request-exit} input)
                  (let [value (eval input)]
                    (set! *3 *2)
                    (set! *2 *1)
                    (set! *1 value)
                    (try (print value)
                         (catch Throwable e
                           (throw (ex-info (ex-message e)
                                           (assoc (meta input)
                                                  :file "<repl>"
                                                  :type :sci/error) e)))))))
            (catch Throwable e
              (let [e (ex-cause e)]
                (caught e)
                (set! *e e)))))]
    (with-bindings
      (try
        (init)
        (catch Throwable e
          (caught e)
          (set! *e e)))
      (prompt)
      (flush)
      (loop []
        (when-not
            (try (identical? (read-eval-print) request-exit)
                 (catch Throwable e
                   (caught e)
                   (set! *e e)
                   nil))
          (when (need-prompt)
            (prompt)
            (flush))
          (recur))))))
