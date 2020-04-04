(ns babashka.impl.bencode.core
  "A netstring and bencode implementation for Clojure."
  {:author "Meikel Brandmeyer"
   :no-doc true}
  (:require [clojure.java.io :as io])
  (:import [java.io ByteArrayOutputStream
            EOFException
            InputStream
            IOException
            OutputStream
            PushbackInputStream]))

;; Copyright (c) Meikel Brandmeyer. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

;; # Motivation
;;
;; In each and every application, which contacts peer processes via some
;; communication channel, the handling of the communication channel is
;; obviously a central part of the application. Unfortunately introduces
;; handling of buffers of varying sizes often bugs in form of buffer
;; overflows and similar.
;;
;; A strong factor in this situation is of course the protocol which goes
;; over the wire. Depending on its design it might be difficult to estimate
;; the size of the input up front. This introduces more handling of message
;; buffers to accomodate for inputs of varying sizes. This is particularly
;; difficult in languages like C, where there is no bounds checking of array
;; accesses and where errors might go unnoticed for considerable amount of
;; time.
;;
;; To address these issues D. Bernstein developed the so called
;; [netstrings][net]. They are especially designed to allow easy construction
;; of the message buffers, easy and robust parsing.
;;
;; BitTorrent extended this to the [bencode][bc] protocol which also
;; includes ways to encode numbers and collections like lists or maps.
;;
;; *wire* is based on these ideas.
;;
;; [net]: http://cr.yp.to/proto/netstrings.txt
;; [bc]:  http://wiki.theory.org/BitTorrentSpecification#Bencoding
;;
;; # Netstrings
;;
;; Now let's start with the basic netstrings. They consist of a byte count,
;; followed a colon and the binary data and a trailing comma. Examples:
;;
;;     13:Hello, World!,
;;     10:Guten Tag!,
;;     0:,
;;
;; The initial byte count allows to efficiently allocate a sufficiently
;; sized message buffer. The trailing comma serves as a hint to detect
;; incorrect netstrings.
;;
;; ## Low-level reading
;;
;; We will need some low-level reading helpers to read the bytes from
;; the input stream. These are `read-byte` as well as `read-bytes`. They
;; are split out, because doing such a simple task as reading a byte is
;; mild catastrophe in Java. So it would add some clutter to the algorithm
;; `read-netstring`.
;;
;; On the other hand they might be also useful elsewhere.
;;
;; To remove some magic numbers from the code below.

(set! *warn-on-reflection* true)

(def #^{:const true} i     105)
(def #^{:const true} l     108)
(def #^{:const true} d     100)
(def #^{:const true} comma 44)
(def #^{:const true} minus 45)

;; These two are only used boxed. So we keep them extra here.

(def e     101)
(def colon 58)

(defn #^{:private true} read-byte
  #^long [#^InputStream input]
  (let [c (.read input)]
    (when (neg? c)
      (throw (EOFException. "Invalid netstring. Unexpected end of input.")))
    ;; Here we have a quirk for example. `.read` returns -1 on end of
    ;; input. However the Java `Byte` has only a range from -128 to 127.
    ;; How does the fit together?
    ;;
    ;; The whole thing is shifted. `.read` actually returns an int
    ;; between zero and 255. Everything below the value 128 stands
    ;; for itself. But larger values are actually negative byte values.
    ;;
    ;; So we have to do some translation here. `Byte/byteValue` would
    ;; do that for us, but we want to avoid boxing here.
    (if (< 127 c) (- c 256) c)))

(defn #^{:private true :tag "[B"} read-bytes
  #^Object [#^InputStream input n]
  (let [content (byte-array n)]
    (loop [offset (int 0)
           len    (int n)]
      (let [result (.read input content offset len)]
        (when (neg? result)
          (throw
           (EOFException.
            "Invalid netstring. Less data available than expected.")))
        (when (not= result len)
          (recur (+ offset result) (- len result)))))
    content))

;; `read-long` is used for reading integers from the stream as well
;; as the byte count prefixes of byte strings. The delimiter is \:
;; for byte count prefixes and \e for integers.

(defn #^{:private true} read-long
  #^long [#^InputStream input delim]
  (loop [n (long 0)]
    ;; We read repeatedly a byte from the input…
    (let [b (read-byte input)]
      ;; …and stop at the delimiter.
      (cond
        (= b minus) (- (read-long input delim))
        (= b delim) n
        :else       (recur (+ (* n (long 10)) (- (long b) (long  48))))))))

;; ## Reading a netstring
;;
;; Let's dive straight into reading a netstring from an `InputStream`.
;;
;; For convenience we split the function into two subfunctions. The
;; public `read-netstring` is the normal entry point, which also checks
;; for the trailing comma after reading the payload data with the
;; private `read-netstring*`.
;;
;; The reason we need the less strict `read-netstring*` is that with
;; bencode we don't have a trailing comma. So a check would not be
;; beneficial here.
;;
;; However the consumer doesn't have to care. `read-netstring` as
;; well as `read-bencode` provide the public entry points, which do
;; the right thing. Although they both may reference the `read-netstring*`
;; underneath.
;;
;; With this in mind we define the inner helper function first.

(declare #^"[B" string>payload
         #^String string<payload)

(defn #^{:private true} read-netstring*
  [input]
  (read-bytes input (read-long input colon)))

;; And the public facing API: `read-netstring`.

(defn #^"[B" read-netstring
  "Reads a classic netstring from input—an InputStream. Returns the
  contained binary data as byte array."
  [input]
  (let [content (read-netstring* input)]
    (when (not= (read-byte input) comma)
      (throw (IOException. "Invalid netstring. ',' expected.")))
    content))

;; Similarly the `string>payload` and `string<payload` functions
;; are defined as follows to simplify the conversion between strings
;; and byte arrays in various parts of the code.

(defn #^{:private true :tag "[B"} string>payload
  [#^String s]
  (.getBytes s "UTF-8"))

(defn #^{:private true :tag String} string<payload
  [#^"[B" b]
  (String. b "UTF-8"))

;; ## Writing a netstring
;;
;; This opposite operation – writing a netstring – is just as important.
;;
;; *Note:* We take here a byte array, just as we returned a byte
;; array in `read-netstring`. The netstring should not be concerned
;; about the actual contents. It just sees binary data.
;;
;; Similar to `read-netstring` we also split `write-netstring` into
;; the entry point itself and a helper function.

(defn #^{:private true} write-netstring*
  [#^OutputStream output #^"[B" content]
  (doto output
    (.write (string>payload (str (alength content))))
    (.write (int colon))
    (.write content)))

(defn write-netstring
  "Write the given binary data to the output stream in form of a classic
  netstring."
  [#^OutputStream output content]
  (doto output
    (write-netstring* content)
    (.write (int comma))))

;; # Bencode
;;
;; However most of the time we don't want to send simple blobs of data
;; back and forth. The data sent between the communication peers usually
;; have some structure, which has to be carried along the way to the
;; other side. Here [bencode][bc] come into play.
;;
;; Bencode defines additionally to netstrings easily parseable structures
;; for lists, maps and numbers. It allows to communicate information
;; about the data structure to the peer on the other side.
;;
;; ## Tokens
;;
;; The data is encoded in tokens in bencode. There are several types of
;; tokens:
;;
;;  * A netstring without trailing comma for string data.
;;  * A tag specifiyng the type of the following tokens.
;;    The tag may be one of these:
;;     * `\i` to encode integers.
;;     * `\l` to encode lists of items.
;;     * `\d` to encode maps of item pairs.
;;  * `\e` to end the a previously started tag.
;;
;; ## Reading bencode
;;
;; Reading bencode encoded data is basically parsing a stream of tokens
;; from the input. Hence we need a read-token helper which allows to
;; retrieve the next token.

(defn #^{:private true} read-token
  [#^PushbackInputStream input]
  (let [ch (read-byte input)]
    (cond
      (= (long e) ch) nil
      (= i ch) :integer
      (= l ch) :list
      (= d ch) :map
      :else    (do
                 (.unread input (int ch))
                 (read-netstring* input)))))

;; To read the bencode encoded data we walk a long the sequence of tokens
;; and act according to the found tags.

(declare read-integer read-list read-map)

(defn read-bencode
  "Read bencode token from the input stream."
  [input]
  (let [token (read-token input)]
    (case token
      :integer (read-integer input)
      :list    (read-list input)
      :map     (read-map input)
      token)))

;; Of course integers and the collection types are have to treated specially.
;;
;; Integers for example consist of a sequence of decimal digits.

(defn #^{:private true} read-integer
  [input]
  (read-long input e))

;; *Note:* integers are an ugly special case, which cannot be
;; handled with `read-token` or `read-netstring*`.
;;
;; Lists are just a sequence of other tokens.

(declare token-seq)

(defn #^{:private true} read-list
  [input]
  (vec (token-seq input)))

;; Maps are sequences of key/value pairs. The keys are always
;; decoded into strings. The values are kept as is.

(defn #^{:private true} read-map
  [input]
  (->> (token-seq input)
       (into {} (comp (partition-all 2)
                      (map (fn [[k v]]
                             [(string<payload k) v]))))))

;; The final missing piece is `token-seq`. This a just a simple
;; sequence which reads tokens until the next `\e`.

(defn #^{:private true} token-seq
  [input]
  (->> #(read-bencode input)
       repeatedly
       (take-while identity)))

;; ## Writing bencode
;;
;; Writing bencode is similar easy as reading it. The main entry point
;; takes a string, map, sequence or integer and writes it according to
;; the rules to the given OutputStream.

(defmulti write-bencode
  "Write the given thing to the output stream. “Thing” means here a
  string, map, sequence or integer. Alternatively an ByteArray may
  be provided whose contents are written as a bytestring. Similar
  the contents of a given InputStream are written as a byte string.
  Named things (symbols or keywords) are written in the form
  'namespace/name'."
  (fn [_output thing]
    (cond
      (bytes? thing) :bytes
      (instance? InputStream thing) :input-stream
      (integer? thing) :integer
      (string? thing)  :string
      (symbol? thing)  :named
      (keyword? thing) :named
      (map? thing)     :map
      (or (nil? thing) (coll? thing) (.isArray (class thing))) :list
      :else (type thing))))

(defmethod write-bencode :default
  [output x]
  (throw (IllegalArgumentException. (str "Cannot write value of type " (class x)))))

;; The following methods should be pretty straight-forward.
;;
;; The easiest case is of course when we already have a byte array.
;; We can simply pass it on to the underlying machinery.

(defmethod write-bencode :bytes
  [output bytes]
  (write-netstring* output bytes))

;; For strings we simply write the string as a netstring without
;; trailing comma after encoding the string as UTF-8 bytes.

(defmethod write-bencode :string
  [output string]
  (write-netstring* output (string>payload string)))

;; Streaming does not really work, since we need to know the
;; number of bytes to write upfront. So we read in everything
;; for InputStreams and pass on the byte array.

(defmethod write-bencode :input-stream
  [output stream]
  (let [bytes (ByteArrayOutputStream.)]
    (io/copy stream bytes)
    (write-netstring* output (.toByteArray bytes))))

;; Integers are again the ugly special case.

(defmethod write-bencode :integer
  [#^OutputStream output n]
  (doto output
    (.write (int i))
    (.write (string>payload (str n)))
    (.write (int e))))

;; Symbols and keywords are converted to a string of the
;; form 'namespace/name' or just 'name' in case its not
;; qualified. We do not add colons for keywords since the
;; other side might not have the notion of keywords.

(defmethod write-bencode :named
  [output thing]
  (let [nspace (namespace thing)
        name   (name thing)]
    (->> (str (when nspace (str nspace "/")) name)
         string>payload
         (write-netstring* output))))

;; Lists as well as maps work recursively to print their elements.

(defmethod write-bencode :list
  [#^OutputStream output lst]
  (.write output (int l))
  (doseq [elt lst]
    (write-bencode output elt))
  (.write output (int e)))

;; However, maps are a bit special because their keys are sorted
;; lexicographically based on their byte string represantation.

(declare lexicographically)

(defmethod write-bencode :map
  [#^OutputStream output m]
  (let [translation (into {} (map (juxt string>payload identity) (keys m)))
        key-strings (sort lexicographically (keys translation))
        >value      (comp m translation)]
    (.write output (int d))
    (doseq [k key-strings]
      (write-netstring* output k)
      (write-bencode output (>value k)))
    (.write output (int e))))

;; However, since byte arrays are not `Comparable` we need a custom
;; comparator which we can feed to `sort`.

(defn #^{:private true} lexicographically
  [#^"[B" a #^"[B" b]
  (let [alen (alength a)
        blen (alength b)
        len  (min alen blen)]
    (loop [i 0]
      (if (== i len)
        (- alen blen)
        (let [x (- (int (aget a i)) (int (aget b i)))]
          (if (zero? x)
            (recur (inc i))
            x))))))
