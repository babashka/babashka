(ns babashka.impl.nrepl-server-test
  (:require
   [babashka.impl.bencode.core :as bencode]
   [babashka.impl.nrepl-server :refer [start-server! stop-server!]]
   [babashka.test-utils :as tu]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :as t :refer [deftest is]]
   [sci.impl.opts :refer [init]]))

(set! *warn-on-reflection* true)

(defn nrepl-command [expr expected]
  (with-open [socket (java.net.Socket. "127.0.0.1" 1667)
              in (.getInputStream socket)
              in (java.io.PushbackInputStream. in)
              os (.getOutputStream socket)]
    (bencode/write-bencode os {"op" "eval" "code" expr})
    (let [msg (bencode/read-bencode in)
          value (get msg "value")
          s (String. value)]
      (is (str/includes? s expected)
          (format "\"%s\" does not contain \"%s\""
                  s expected)))))

(deftest nrepl-server-test
  (try
    (if tu/jvm?
      (future
        (start-server! (init {:env (atom {})
                              :features #{:bb}}) "0.0.0.0:1667"))
      (future
        (sh "bash" "-c"
            "./bb --nrepl-server 0.0.0.0:1667")))
    ;; wait for server to be available
    (when tu/native?
      (while (not (zero? (:exit
                          (sh "bash" "-c"
                              "lsof -t -i:1667"))))))
    (is (nrepl-command "(+ 1 2 3)" "6"))
    (finally
      (if tu/jvm?
        (stop-server!)
        (sh "bash" "-c"
            "kill -9 $(lsof -t -i:1667)")))))

;;;; Scratch

(comment
  )
