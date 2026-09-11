(ns nrepl.bencode
  "Stand-in for nREPL's nrepl.bencode: the compiled bencode.core does the
  parsing, this keeps nREPL's API on top of it."
  (:require [bencode.core :as bencode])
  (:import (java.io EOFException PushbackInputStream)))

(def read-bencode bencode/read-bencode)

(def write-bencode bencode/write-bencode)

(defn read-nrepl-message
  "Same as `read-bencode`, but ensure that the top-level value is a map as
  expected by the nREPL protocol."
  [^PushbackInputStream input]
  (let [first-byte (.read input)]
    (cond (= -1 first-byte)
          (throw (EOFException. "Invalid netstring. Unexpected end of input."))
          (= (int \d) first-byte)
          (do (.unread input first-byte)
              (read-bencode input))
          :else
          (throw (ex-info (format "nREPL message must be a map.
Wrong first byte: %s (must be %d)." first-byte (int \d)) {})))))
