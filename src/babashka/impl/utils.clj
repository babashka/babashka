(ns babashka.impl.utils
  (:import [java.net Socket ConnectException]))

(defn wait-for-it
  ([host port]
   (wait-for-it host port nil))
  ([host port {:keys [:timeout :pause]}]
   (let [t0 (System/currentTimeMillis)]
     (loop []
       (let [v (try (Socket. host port)
                    (catch ConnectException _e
                      (let [t1 (System/currentTimeMillis)]
                        (if (and timeout (>= (- t1 t0) timeout))
                          (throw (ex-info
                                  (format "timeout while waiting for %s:%s" host port)
                                  {}))
                          :wait-for-it.impl/try-again))))]
         (when (identical? :wait-for-it.impl/try-again v)
           (Thread/sleep (or pause 100))
           (recur)))))))

(def utils-bindings {'utils/wait-for-it wait-for-it})

(comment
  (wait-for-it "localhost" 80)
  (wait-for-it "localhost" 80 {:timeout 1000})
  (wait-for-it "google.com" 80)
  )
