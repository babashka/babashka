(ns babashka.utils)

(defmacro if-graal [then else]
  (if (resolve 'babashka.impl.Graal)
    then
    else))

(defn set-env [name value]
  (if-graal
      (do
        (prn :setting name value)
        (babashka.impl.Graal/setEnv name value))
    (throw (UnsupportedOperationException. "set-env is only available in the native image."))))
