(ns babashka.terminal
  ;; MB: relying on Jline's internals here, but we can always implement this
  ;; ourselves if this changes using Java's FFM stuff.
  (:import [org.jline.terminal.spi SystemStream]
           [org.jline.terminal.impl.ffm FfmTerminalProvider]))

(set! *warn-on-reflection* true)

(defn- system-stream? [^SystemStream stream]
  (.isSystemStream (FfmTerminalProvider.) stream))

(defn tty?
  "Returns true if the given file descriptor is associated with a terminal.
  fd should be :stdin, :stdout, or :stderr."
  [fd]
  (case fd
    :stdin (system-stream? SystemStream/Input)
    :stdout (system-stream? SystemStream/Output)
    :stderr (system-stream? SystemStream/Error)
    (throw (IllegalArgumentException. (str "Invalid file descriptor: " fd ". Expected :stdin, :stdout, or :stderr.")))))

