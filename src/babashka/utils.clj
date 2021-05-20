(ns babashka.utils)

(defmacro when-graal [& body]
  (when (resolve 'babashka.impl.Graal)
    `(do ~@body)))

(defn set-env [name value]
  (when-graal
      (babashka.impl.Graal/setEnv name value)))
