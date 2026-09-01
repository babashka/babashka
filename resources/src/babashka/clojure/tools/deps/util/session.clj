;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:skip-wiki true}
  clojure.tools.deps.util.session
  "Maintains session resources during or across runs of the resolver"
  (:import
    [java.util.concurrent ConcurrentMap ConcurrentHashMap]
    [java.util.function Function]))

(def session (ConcurrentHashMap.)) ;; should never be nil

(defn retrieve
  "Read the current value of key from the session. If absent, and if-absent-fn
  is supplied, invoke the fn, set it in the session (if there is one),
  and return the value."
  ([key]
   (.get ^ConcurrentMap session key))
  ([key if-absent-fn]
   (.computeIfAbsent ^ConcurrentMap session key
     (reify Function
       (apply [_f _k]
         (if-absent-fn))))))

(defn retrieve-local
  "Like retrieve, but scoped to a thread-specific key, so never shared across threads."
  ([key]
   (retrieve {:thread (.getId (Thread/currentThread)) :key key}))
  ([key if-absent-fn]
   (retrieve {:thread (.getId (Thread/currentThread)) :key key} if-absent-fn)))

(defmacro with-session
  "Create a new empty session and execute the body"
  [& body]
  `(let [prior# session]
     (alter-var-root #'session (constantly (ConcurrentHashMap.)))
     (try
       ~@body
       (finally
         (alter-var-root #'session (constantly prior#))))))
