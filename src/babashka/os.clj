(ns babashka.os)

(defmacro if-graal [then else]
  (if (resolve 'babashka.impl.Graal)
    then
    else))

(defn set-env
  ([name value] (set-env name value true))
  ([name value overwrite?]
   (if-graal
       (babashka.impl.Graal/setEnv name value overwrite?)
     (throw (UnsupportedOperationException. "set-env is only available in the native image.")))))

(defn get-env [name]
  (if-graal
      (babashka.impl.Graal/getEnv name)
    (throw (UnsupportedOperationException. "set-env is only available in the native image."))))
