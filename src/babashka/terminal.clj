(ns babashka.terminal
  (:import [org.jline.terminal.spi SystemStream TerminalProvider]))

(set! *warn-on-reflection* true)

(defn- load-provider []
  ;; Try FFM first (works on Windows native, macOS, Linux glibc)
  ;; Fall back to exec (works on Linux musl, Windows Git Bash)
  (try
    (TerminalProvider/load "ffm")
    (catch Throwable _
      (TerminalProvider/load "exec"))))

(def ^:private provider (delay (load-provider)))

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
