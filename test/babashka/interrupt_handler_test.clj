(ns babashka.interrupt-handler-test
  {:no-doc true}
  (:import [java.nio.charset Charset])
  (:require [babashka.test-utils :as tu]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- stream-to-string
  ([in] (stream-to-string in (.name (Charset/defaultCharset))))
  ([in enc]
   (with-open [bout (java.io.StringWriter.)]
     (io/copy in bout :encoding enc)
     (.toString bout))))

(deftest interrupt-handler-test
  (let [script (.getPath (io/file "test" "babashka" "scripts" "interrupt_handler.bb"))
        pb (ProcessBuilder. (if tu/jvm?
                              ["lein" "bb" script]
                              ["./bb" script]))
        process (.start pb)
        output (.getInputStream process)]
    (is (= "bye1 :quit\nbye2 :quit2\n"  (stream-to-string output)))))
