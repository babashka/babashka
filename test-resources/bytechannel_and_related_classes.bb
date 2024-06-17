(ns bytechannel-and-related-classes
  (:require [clojure.java.io :as io])
  (:import (java.nio.file OpenOption
                          StandardOpenOption)
           (java.nio.channels ByteChannel
                              FileChannel
                              ReadableByteChannel
                              WritableByteChannel
                              Channels)))

(when (and (let [ch (-> (.getBytes "Hello")
                        (java.io.ByteArrayInputStream.)
                        (Channels/newChannel))]
             (instance? ReadableByteChannel ch))
           (let [ch (-> (java.io.ByteArrayOutputStream.)
                        (Channels/newChannel))]
             (instance? WritableByteChannel ch))
           (with-open [ch (FileChannel/open (-> (io/file "README.md")
                                                (.toPath))
                                            (into-array OpenOption [StandardOpenOption/READ]))]
             (instance? ByteChannel ch)))
  (println :success))
