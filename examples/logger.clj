(ns logger)

(defmacro log [& msgs]
  (let [m (meta &form)
        _ns (ns-name *ns*) ;; can also be used for logging
        file *file*]
    `(binding [*out* *err*] ;; or bind to (io/writer log-file)
       (println (str ~file ":"
                  ~(:line m) ":"
                  ~(:column m))
         ~@msgs))))
