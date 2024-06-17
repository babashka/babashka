(ns bytechannel-and-related-classes
  (:import (java.io InputStream OutputStream)
           (java.nio ByteBuffer)
           (java.nio.channels ByteChannel
                              Channels)))

;;
;; Accept a ByteChannel and return an InputStream that reads data from
;; the given ByteChannel. Buffer data in internal ByteBuffer.
;;


(defn channel->input-stream ^InputStream [^ByteChannel ch]
  (let [buffer (doto (ByteBuffer/allocate 1024)
                 (.flip))
        eof?   (fn []
                 (if (.hasRemaining buffer)
                   ;; Buffer has data that can be read:
                   false
                   ;; Buffer is empty, try fill buffer from channel:
                   (do (.clear buffer)
                       (if (= (.read ch buffer) -1)
                         ;; Channel is also empty
                         true
                         ;; Successfully read data into buffer:
                         (do (.flip buffer)
                             false)))))]
    (proxy [InputStream] []
      (read
        ([]
         (if (eof?)
           -1
           (-> (.get buffer)
               (bit-and 0xff))))
        ([b]
         (.read this b 0 (alength b)))
        ([b off len]
         (if (eof?)
           -1
           (let [len (min (alength b)
                          (.remaining buffer))]
             (.get buffer b 0 len)
             len))))
      (close []
        (.close ch)))))


;;
;; Accept a ByteChannel and return an OutputStream that writes into the
;; channel
;;


(defn channel->output-stream ^OutputStream [^ByteChannel ch]
  (proxy [OutputStream] []
    (write
      ([v]
       (if (bytes? v)
         (.write ch (ByteBuffer/wrap v))
         (let [buffer (ByteBuffer/allocate 1)]
           (.put buffer (-> (Integer. v) (.byteValue)))
           (.flip buffer)
           (.write ch buffer))))
      ([v off len]
       (.write ch (ByteBuffer/wrap v off len))))
    (close []
      (.close ch))))


;;
;; Tests:
;;


(defn read-byte-by-byte-test []
  (let [in (-> (.getBytes "Hello")
               (java.io.ByteArrayInputStream.)
               (Channels/newChannel)
               (channel->input-stream))]
    (and (= (.read in) (int \H))
         (= (.read in) (int \e))
         (= (.read in) (int \l))
         (= (.read in) (int \l))
         (= (.read in) (int \o))
         (= (.read in) -1))))

(defn read-byte-array []
  (let [in     (-> (.getBytes "Hello")
                   (java.io.ByteArrayInputStream.)
                   (Channels/newChannel)
                   (channel->input-stream))
        buffer (byte-array 10)
        len    (.read in buffer)]
    (and (= len 5)
         (= (String. buffer 0 len) "Hello"))))

(defn read-all []
  (let [in   (-> (.getBytes "Hello")
                 (java.io.ByteArrayInputStream.)
                 (Channels/newChannel)
                 (channel->input-stream))
        data (.readAllBytes in)]
    (= (String. data) "Hello")))

(defn write-byte-by-byte []
  (let [buffer (java.io.ByteArrayOutputStream.)
        out    (-> (Channels/newChannel buffer)
                   (channel->output-stream))]
    (.write out (int \H))
    (.write out (int \e))
    (.write out (int \l))
    (.write out (int \l))
    (.write out (int \o))
    (.close out)
    (= (String. (.toByteArray buffer)) "Hello")))

(defn write-byte-array []
  (let [buffer (java.io.ByteArrayOutputStream.)
        out    (-> (Channels/newChannel buffer)
                   (channel->output-stream))]
    (.write out (.getBytes "Hello"))
    (.close out)
    (= (String. (.toByteArray buffer)) "Hello")))

(when (every? (fn [f] (f))
              [read-byte-by-byte-test
               read-byte-array
               read-all
               write-byte-by-byte
               write-byte-array])
  (println "Success")
  :success)
