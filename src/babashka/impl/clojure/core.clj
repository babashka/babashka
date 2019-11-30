(ns babashka.impl.clojure.core
  {:no-doc true}
  (:refer-clojure :exclude [future])
  (:require [sci.opts :as opts]))

(defn future
  [_ _ & body]
  `(~'future-call (fn [] ~@body)))

(defn __close!__ [^java.io.Closeable x]
  (.close x))

(defn with-open*
  [_ _ bindings & body]
  (cond
    (= (count bindings) 0) `(do ~@body)
    (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                              (try
                                (with-open ~(subvec bindings 2) ~@body)
                                (finally
                                  (~'__close!__ ~(bindings 0)))))
    :else (throw (IllegalArgumentException.
                  "with-open only allows Symbols in bindings"))))

#_(defn binding*
  "This macro only works with symbols that evaluate to vars themselves. See `*in*` and `*out*` below."
  [_ _ bindings & body]
  `(do
     (let []
       (push-thread-bindings (hash-map ~@bindings))
       (try
         ~@body
         (finally
           (pop-thread-bindings))))))

;; this works now!
"(def w (java.io.StringWriter.)) (push-thread-bindings {clojure.core/*out* w}) (try (println \"hello\") (finally (pop-thread-bindings))) (prn \">\" (str w))"

;; this also works now! "(def w (java.io.StringWriter.)) (binding [clojure.core/*out* w] (println \"hello\")) (str w)"

#_(defn with-out-str*
  [_ _ & body]
  `(let [s# (java.io.StringWriter.)]
     (clojure.core/binding [*out* s#]
       ~@body
       (str s#))))

#_(defn with-in-str*
  [_ _ s & body]
  `(with-open [s# (-> (java.io.StringReader. ~s) clojure.lang.LineNumberingPushbackReader.)]
     (clojure.core/binding [*in* s#]
       ~@body)))

(def core-extras
  {;;'*in* #'*in*
   ;; '*out* #'*out*
   'file-seq file-seq
   'future-call future-call
   'future (with-meta future {:sci/macro true})
   'future-cancel future-cancel
   'future-cancelled? future-cancelled?
   'future-done? future-done?
   'future? future?
   'agent agent
   'send send
   'send-off send-off
   'promise promise
   'deliver deliver
   'shutdown-agents shutdown-agents
   'slurp slurp
   'spit spit
   'pmap pmap
   ;; 'pr pr
   ;; 'prn prn
   ;; 'print print
   ;; 'println println
   ;; 'println-str println-str
   ;; 'flush flush
   ;; 'read-line read-line
   '__close!__ __close!__
   'with-open (with-meta with-open* {:sci/macro true})
   ;; 'with-out-str (with-meta with-out-str* {:sci/macro true})
   ;; 'with-in-str (with-meta with-in-str* {:sci/macro true})
   })
