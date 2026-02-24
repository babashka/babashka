(ns babashka.impl.clojure.repl
  {:no-doc true}
  (:require [babashka.fs :as fs]
            [babashka.impl.classpath :as cp]
            [babashka.impl.common :as common]
            [babashka.impl.print-deps :as print-deps]
            [clojure.string :as str]
            [sci.core :as sci]))

(set! *warn-on-reflection* true)

(def crns (sci/create-ns 'clojure.repl nil))

(def special-doc-var
  (sci/new-var
   'special-doc
   (let [m '{. {:url "java_interop#dot"
                :forms [(.instanceMember instance args*)
                        (.instanceMember Classname args*)
                        (Classname/staticMethod args*)
                        Classname/staticField]
                :doc "The instance member form works for both fields and methods.
  They all expand into calls to the dot operator at macroexpansion time."}
             def {:forms [(def symbol doc-string? init?)]
                  :doc "Creates and interns a global var with the name
  of symbol in the current namespace (*ns*) or locates such a var if
  it already exists.  If init is supplied, it is evaluated, and the
  root binding of the var is set to the resulting value.  If init is
  not supplied, the root binding of the var is unaffected."}
             do {:forms [(do exprs*)]
                 :doc "Evaluates the expressions in order and returns the value of
  the last. If no expressions are supplied, returns nil."}
             if {:forms [(if test then else?)]
                 :doc "Evaluates test. If not the singular values nil or false,
  evaluates and yields then, otherwise, evaluates and yields else. If
  else is not supplied it defaults to nil."}
             monitor-enter {:forms [(monitor-enter x)]
                            :doc "Synchronization primitive that should be avoided
  in user code. Use the 'locking' macro."}
             monitor-exit {:forms [(monitor-exit x)]
                           :doc "Synchronization primitive that should be avoided
  in user code. Use the 'locking' macro."}
             new {:forms [(Classname. args*) (new Classname args*)]
                  :url "java_interop#new"
                  :doc "The args, if any, are evaluated from left to right, and
  passed to the constructor of the class named by Classname. The
  constructed object is returned."}
             quote {:forms [(quote form)]
                    :doc "Yields the unevaluated form."}
             recur {:forms [(recur exprs*)]
                    :doc "Evaluates the exprs in order, then, in parallel, rebinds
  the bindings of the recursion point to the values of the exprs.
  Execution then jumps back to the recursion point, a loop or fn method."}
             set! {:forms [(set! var-symbol expr)
                           (set! (. instance-expr instanceFieldName-symbol) expr)
                           (set! (. Classname-symbol staticFieldName-symbol) expr)]
                   :url "vars#set"
                   :doc "Used to set thread-local-bound vars, Java object instance
fields, and Java class static fields."}
             throw {:forms [(throw expr)]
                    :doc "The expr is evaluated and thrown, therefore it should
  yield an instance of some derivee of Throwable."}
             try {:forms [(try expr* catch-clause* finally-clause?)]
                  :doc "catch-clause => (catch classname name expr*)
  finally-clause => (finally expr*)

  Catches and handles Java exceptions."}
             var {:forms [(var symbol)]
                  :doc "The symbol must resolve to a var, and the Var object
itself (not its value) is returned. The reader macro #'x expands to (var x)."}}]
     (fn [name-symbol]
       (when-let [doc (m name-symbol)]
         (assoc doc :name name-symbol :special-form true))))
   {:ns crns :private true}))

(def set-break-handler-var
  (sci/new-var
   'set-break-handler!
   (fn
     ([] nil)
     ([f]
      (sun.misc.Signal/handle
        (sun.misc.Signal. "INT")
        (proxy [sun.misc.SignalHandler] []
          (handle [signal]
            (f (str "-- caught signal " signal)))))))
   {:ns crns
    :doc "Register INT signal handler.  After calling this, Ctrl-C will cause\n  the given function f to be called with a single argument, the signal.\n  Uses thread-stopper if no function given."
    :arglists '([] [f])}))

(def source-fn-var
  (let [source-loader (delay
                        (try
                          (let [cp (print-deps/deps-classpath)
                                urls (into-array java.net.URL
                                                 (map #(-> (fs/file %) .toURI .toURL)
                                                      (str/split cp (re-pattern (System/getProperty "path.separator")))))]
                            (java.net.URLClassLoader. urls nil))
                          (catch Exception _ nil)))
        find-source (fn [^String file]
                      (or (when (fs/exists? file) (slurp file))
                          (when-let [res (.findResource ^babashka.impl.URLClassLoader @cp/the-url-loader file)]
                            (slurp res))
                          (when-let [loader @source-loader]
                            (when-let [res (.getResource ^java.net.URLClassLoader loader file)]
                              (slurp res)))))]
    (sci/new-var
     'source-fn
     (fn [x]
       (when-let [v (sci/resolve (common/ctx) x)]
         (let [{:keys [file line]} (meta v)]
           (when (and file line)
             (when-let [source (find-source file)]
               (let [lines (str/split source #"\n")
                     line (dec ^long line)
                     start (str/join "\n" (drop line lines))
                     reader (sci/source-reader start)
                     res (sci/parse-next+string (common/ctx) reader)]
                 (second res)))))))
     {:ns crns})))

(def repl-namespace
  {'special-doc special-doc-var
   'set-break-handler! set-break-handler-var
   'source-fn source-fn-var})
