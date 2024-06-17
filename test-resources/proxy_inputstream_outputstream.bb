(ns proxy-inputstream-outputstream
  (:import (java.io InputStream OutputStream)
           (java.nio ByteBuffer)))


;;
;; Accept a ByteBuffer and return an InputStream that reads data from
;; the given buffer.
;;


(defn buffer->input-stream ^InputStream [^ByteBuffer buffer]
  (proxy [InputStream] []
    (read
      ([]
       (if (.hasRemaining buffer)
         (-> (.get buffer)
             (bit-and 0xff))
         -1))
      ([b]
       (.read this b 0 (alength b)))
      ([b off len]
       (if (.hasRemaining buffer)
         (let [len (min (alength b)
                        (.remaining buffer))]
           (.get buffer b off len)
           len)
         -1)))))


;;
;; Accept a ByteBuffer and return an OutputStream that writes into the
;; buffer.
;;


(defn buffer->output-stream ^OutputStream [^ByteBuffer buffer]
  (proxy [OutputStream] []
    (write
      ([v]
       (if (bytes? v)
         (.put buffer ^bytes v)
         (.put buffer (-> (Integer. v) (.byteValue)))))
      ([v off len]
       (.put buffer ^bytes v 0 (alength v))))))


;;
;; Tests:
;;


(defn read-byte-by-byte-test []
  (let [in (-> (.getBytes "Hello")
               (ByteBuffer/wrap)
               (buffer->input-stream))]
    (and (= (.read in) (int \H))
         (= (.read in) (int \e))
         (= (.read in) (int \l))
         (= (.read in) (int \l))
         (= (.read in) (int \o))
         (= (.read in) -1))))

(defn read-byte-array []
  (let [in (-> (.getBytes "Hello")
               (ByteBuffer/wrap)
               (buffer->input-stream))
        buffer  (byte-array 10)
        len     (.read in buffer)]
    (and (= len 5)
         (= (String. buffer 0 len) "Hello"))))

(defn read-all []
  (let [in (-> (.getBytes "Hello")
               (ByteBuffer/wrap)
               (buffer->input-stream))
        data   (.readAllBytes in)]
    (= (String. data) "Hello")))

(defn write-byte-by-byte []
  (let [buffer (ByteBuffer/allocate 10)
        out    (buffer->output-stream buffer)]
    (.write out (int \H))
    (.write out (int \e))
    (.write out (int \l))
    (.write out (int \l))
    (.write out (int \o))
    (= (String. (.array buffer)
                0
                (.position buffer))
       "Hello")))

(defn write-byte-array []
  (let [buffer (ByteBuffer/allocate 10)
        out    (buffer->output-stream buffer)]
    (.write out (.getBytes "Hello"))
    (= (String. (.array buffer)
                0
                (.position buffer))
       "Hello")))

;;
;; Run all tests:
;;

(when (and (read-byte-by-byte-test)
           (read-byte-array)
           (read-all)
           (write-byte-by-byte)
           (write-byte-array))
  (println "Success")
  :success)
