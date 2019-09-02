(ns babashka.impl.conch
  (:require
   [me.raynes.conch.low-level :as ll]
   [me.raynes.conch :as conch]))

(defn programs
  "Creates functions corresponding to progams on the PATH, named by names."
  {:sci/macro true}
  [& names]
  `(do ~@(for [name names]
           `(defn ~name [& ~'args]
              (apply ~'conch/execute ~(str name) ~'args)))))

(def conch-bindings
  {;; low level API
   'conch/proc ll/proc
   'conch/destroy ll/destroy
   'conch/exit-code ll/exit-code
   'conch/flush ll/flush
   'conch/done ll/done
   'conch/stream-to ll/stream-to
   'conch/feed-from ll/feed-from
   'conch/stream-to-string ll/stream-to-string
   'conch/stream-to-out ll/stream-to-out
   'conch/feed-from-string ll/feed-from-string
   'conch/read-line ll/read-line
   ;; high level API
   'conch/execute conch/execute
   'conch/programs (with-meta programs (meta #'programs))})
