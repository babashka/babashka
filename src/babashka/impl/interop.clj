(ns babashka.impl.interop
  {:no-doc true})

(defn invokeMethod [^Object obj method args]
  (let [class (.getClass obj)
        method (.getMethod class method (into-array (map #(.getClass ^Object %) args)))]
    (.invoke method obj (into-array args))))

(defn dot-macro [_ _ & [instance-expr [method-symbol & args]]]
  (invokeMethod instance-expr (str method-symbol) args))
