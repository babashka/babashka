(import java.net.UnixDomainSocketAddress
        java.net.StandardProtocolFamily
        [java.nio.channels ServerSocketChannel SocketChannel])

(require '[clojure.java.io :as io])

(def sockaddr (UnixDomainSocketAddress/of
               (-> (doto (io/file "/tmp/sock")
                     (.deleteOnExit))
                   str)))

;; server
(def server
  (future
    (let [ch (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind ch sockaddr)
      (.accept ch))))

(Thread/sleep 100)

  ;; client
(let [ch (SocketChannel/open StandardProtocolFamily/UNIX)
      ch (loop [retry 0]
           (let [v (try (.connect ch sockaddr)
                        (catch Exception e e))]
             (if (instance? Exception v)
               (if (< retry 10)
                 (do (Thread/sleep 100)
                     (recur (inc retry)))
                 (throw v))
               v)))]
  #_(prn :ch ch)
  #_(.close ch))

@server

(when-not (System/getProperty "babashka.version")
  (shutdown-agents))

:success
