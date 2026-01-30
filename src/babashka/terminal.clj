(ns babashka.terminal
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.terminal.spi SystemStream TerminalProvider]))

(set! *warn-on-reflection* true)

;; Get first available provider in default order: FFM, JNI, Exec
(def ^:private provider
  (delay (first (.getProviders (TerminalBuilder/builder) nil (IllegalStateException.)))))

(defn- system-stream? [^SystemStream stream]
  (.isSystemStream ^TerminalProvider @provider stream))

(defn tty?
  "Returns true if the given file descriptor is associated with a terminal.
  fd should be :stdin, :stdout, or :stderr."
  [fd]
  (case fd
    :stdin (system-stream? SystemStream/Input)
    :stdout (system-stream? SystemStream/Output)
    :stderr (system-stream? SystemStream/Error)
    (throw (IllegalArgumentException. (str "Invalid file descriptor: " fd ". Expected :stdin, :stdout, or :stderr.")))))
