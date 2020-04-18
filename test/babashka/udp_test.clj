(ns babashka.udp-test
  (:require [babashka.test-utils :as tu]
            [clojure.test :refer [deftest is]])
  (:import [java.io StringWriter]
           [java.net DatagramPacket DatagramSocket]))

(set! *warn-on-reflection* true)

(deftest udp-test
  (let [server (DatagramSocket. 8125)
        sw (StringWriter.)
        fut (future
              (let [buf (byte-array 1024)
                    packet (DatagramPacket. buf 1024)
                    _ (.receive server packet)
                    non-zero-bytes (filter #(not (zero? %)) (.getData packet))
                    non-zero-bytes (byte-array non-zero-bytes)]
                (binding [*out* sw]
                  (println (String. non-zero-bytes "UTF-8")))))]
    (while (not (realized? fut))
      (tu/bb nil
             "-e" "(load-file (io/file \"test-resources\" \"babashka\" \"statsd.clj\"))"
             "-e" "(require '[statsd-client :as c])"
             "-e" "(c/increment :foo)"))
    (is (= ":foo:1|c\n" (str sw)))))
