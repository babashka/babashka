(ns babashka.terminal
  (:import [org.jline.terminal.spi SystemStream TerminalProvider]))

(set! *warn-on-reflection* true)

(defn- system-stream? [^SystemStream stream]
  (let [provider (try
                   (TerminalProvider/load "ffm")
                   (catch Throwable e
                     (prn e)
                     (prn :falling-back-on-exec)
                     (TerminalProvider/load "exec")))]
    (.isSystemStream ^TerminalProvider provider stream)))

(defn tty?
  "Returns true if the given file descriptor is associated with a terminal.
  fd should be :stdin, :stdout, or :stderr."
  [fd]
  (case fd
    :stdin (system-stream? SystemStream/Input)
    :stdout (system-stream? SystemStream/Output)
    :stderr (system-stream? SystemStream/Error)
    (throw (IllegalArgumentException. (str "Invalid file descriptor: " fd ". Expected :stdin, :stdout, or :stderr.")))))

