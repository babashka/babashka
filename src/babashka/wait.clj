(ns babashka.wait
  (:require [clojure.java.io :as io]
            [sci.core :as sci])
  (:import [java.net Socket SocketException]))

(set! *warn-on-reflection* true)

(defn wait-for-port
  "Waits for TCP connection to be available on host and port. Options map
  supports `:timeout` and `:pause`. If `:timeout` is provided and reached,
  `:default`'s value (if any) is returned. The `:pause` option determines
  the time waited between retries."
  ([host port]
   (wait-for-port host port nil))
  ([^String host ^long port {:keys [:default :timeout :pause] :as opts}]
   (let [opts (merge {:host host
                      :port port}
                     opts)
         t0 (System/currentTimeMillis)]
     (loop []
       (let [v (try (.close (Socket. host port))
                    (- (System/currentTimeMillis) t0)
                    (catch SocketException _e
                      (let [took (- (System/currentTimeMillis) t0)]
                        (if (and timeout (>= took timeout))
                          :wait-for-port.impl/timed-out
                          :wait-for-port.impl/try-again))))]
         (cond (identical? :wait-for-port.impl/try-again v)
               (do (Thread/sleep (or pause 100))
                   (recur))
               (identical? :wait-for-port.impl/timed-out v)
               default
               :else
               (assoc opts :took v)))))))

(defn wait-for-path
  "Waits for file path to be available. Options map supports `:default`,
  `:timeout` and `:pause`. If `:timeout` is provided and reached, `:default`'s
  value (if any) is returned. The `:pause` option determines the time waited
  between retries."
  ([path]
   (wait-for-path path nil))
  ([^String path {:keys [:default :timeout :pause] :as opts}]
   (let [opts (merge {:path path}
                     opts)
         t0 (System/currentTimeMillis)]
     (loop []
       (let [v (when (not (.exists (io/file path)))
                 (let [took (- (System/currentTimeMillis) t0)]
                   (if (and timeout (>= took timeout))
                     :wait-for-path.impl/timed-out
                     :wait-for-path.impl/try-again)))]
         (cond (identical? :wait-for-path.impl/try-again v)
               (do (Thread/sleep (or pause 100))
                   (recur))
               (identical? :wait-for-path.impl/timed-out v)
               default
               :else
               (assoc opts :took
                      (- (System/currentTimeMillis) t0))))))))

(def wns (sci/create-ns 'babashka.wait nil))

(def wait-namespace
  {'wait-for-port (sci/copy-var wait-for-port wns)
   'wait-for-path (sci/copy-var wait-for-path wns)})

(comment
  (wait-for-port "localhost" 80)
  (wait-for-port "localhost" 80 {:timeout 1000})
  (wait-for-port "google.com" 80)

  (wait-for-path "/tmp/hi")
  (wait-for-path "/tmp/there" {:timeout 1000})

  )
