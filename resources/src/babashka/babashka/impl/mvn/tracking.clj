(ns babashka.impl.mvn.tracking
  "Tracking files in the local repository, locked across threads and processes."
  {:no-doc true}
  (:require [babashka.fs :as fs])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.file OpenOption StandardOpenOption]))

(def ^:private locks (atom {}))

(defn lock-for
  "Returns the monitor for path."
  [path]
  (let [path (str (fs/normalize (fs/absolutize path)))]
    (or (get @locks path)
        (get (swap! locks update path #(or % (Object.))) path))))

(defn- read-channel [^FileChannel ch]
  (let [buf (ByteBuffer/allocate (int (.size ch)))]
    (.position ch 0)
    (loop []
      (when (and (.hasRemaining buf) (pos? (.read ch buf)))
        (recur)))
    (String. (.array buf) 0 (.position buf) "UTF-8")))

(defn read-tracking-file
  "Returns the contents of a tracking file, or nil if the file does not exist."
  [file]
  (let [path (fs/path file)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (lock-for (str path))
      (when (fs/exists? path)
        (with-open [ch (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ]))]
          (.lock ch 0 Long/MAX_VALUE true)
          (read-channel ch))))))

(defn update-tracking-file!
  "Replaces the contents of a tracking file with (f text). Passes \"\" to f for a new file."
  [file f]
  (let [path (fs/path file)]
    #_{:clj-kondo/ignore [:locking-suspicious-lock]}
    (locking (lock-for (str path))
      (with-open [ch (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ
                                                                     StandardOpenOption/WRITE
                                                                     StandardOpenOption/CREATE]))]
        (.lock ch)
        (let [old (read-channel ch)
              new (f old)]
          (when (not= old new)
            (.truncate ch 0)
            (let [buf (ByteBuffer/wrap (.getBytes ^String new "UTF-8"))]
              (loop []
                (when (.hasRemaining buf)
                  (.write ch buf)
                  (recur))))))))))
