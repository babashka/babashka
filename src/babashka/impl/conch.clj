(ns babashka.impl.conch
  (:require
   [me.raynes.conch.low-level :as ll]
   [me.raynes.conch :as conch]))

(defn programs
  "Creates functions corresponding to progams on the PATH, named by names."
  [& names]
  `(do ~@(for [name names]
           `(defn ~name [& ~'args]
              (apply ~'conch/execute ~(str name) ~'args)))))

(defn defcommands
  "Creates functions corresponding to progams on the PATH, named by names."
  [& names]
  `(do ~@(for [name names]
           `(defn ~name [& ~'args]
              (apply ~'conch/proc ~(str name) ~'args)))))

(defn pipe [p1 p2]
  ;; TODO varargs
  (ll/stream-to p1 :out (:in p2))
  p2)

(defn out-str [p]
  (ll/done p)
  (ll/stream-to-string p :out))

(defn in-str [p s]
  (ll/feed-from-string p s))

(defn eof [p]
  (ll/done p))

(defn err-str [p]
  (ll/stream-to-string p :err))

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
   'conch/programs (with-meta programs {:sci/macro true})

   'proc/proc ll/proc
   'proc/defcommands (with-meta defcommands {:sci/macro true})
   'proc/pipe pipe
   'proc/out-str out-str
   'proc/in-str in-str
   'proc/eof eof})
