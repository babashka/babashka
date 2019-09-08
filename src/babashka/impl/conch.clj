(ns babashka.impl.conch
  {:no-doc true}
  (:require
   [babashka.impl.me.raynes.conch.low-level :as ll]))

(def conch-namespace
  {;; low level API
   'proc ll/proc
   'destroy ll/destroy
   'exit-code ll/exit-code
   'flush ll/flush
   'done ll/done
   'stream-to ll/stream-to
   'feed-from ll/feed-from
   'stream-to-string ll/stream-to-string
   'stream-to-out ll/stream-to-out
   'feed-from-string ll/feed-from-string
   'read-line ll/read-line})
