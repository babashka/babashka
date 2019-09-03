(ns babashka.impl.utils
  (:import [java.net Socket ConnectException]))

(set! *warn-on-reflection* true)

(defn wait-for-it
  ([host port]
   (wait-for-it host port nil))
  ([^String host ^long port {:keys [:timeout :pause] :as opts}]
   (let [opts (merge {:host host
                      :port port}
                     opts)
         t0 (System/currentTimeMillis)]
     (loop []
       (let [v (try (Socket. host port)
                    (- (System/currentTimeMillis) t0)
                    (catch ConnectException _e
                      (let [took (- (System/currentTimeMillis) t0)]
                        (if (and timeout (>= took timeout))
                          (throw (ex-info
                                  (format "timeout while waiting for %s:%s" host port)
                                  (assoc opts :took took)))
                          :wait-for-it.impl/try-again))))]
         (if (identical? :wait-for-it.impl/try-again v)
           (do (Thread/sleep (or pause 100))
               (recur))
           (assoc opts :took v)))))))

(def utils-bindings {'utils/wait-for-it wait-for-it})

(comment
  (wait-for-it "localhost" 80)
  (wait-for-it "localhost" 80 {:timeout 1000})
  (wait-for-it "google.com" 80)
  )
