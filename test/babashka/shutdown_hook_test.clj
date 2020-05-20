(ns babashka.shutdown-hook-test
  {:no-doc true}
  #_(:import [java.nio.charset Charset])
  #_(:require [babashka.test-utils :as tu]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

#_(defn- stream-to-string
  ([in] (stream-to-string in (.name (Charset/defaultCharset))))
  ([in enc]
   (with-open [bout (java.io.StringWriter.)]
     (io/copy in bout :encoding enc)
     (.toString bout))))

#_(deftest shutdown-hook-test

  (let [script "(-> (Runtime/getRuntime) (.addShutdownHook (Thread. #(println \"bye\"))))"
        pb (ProcessBuilder. (if tu/jvm?
                              ["lein" "bb" "-e" script]
                              ["./bb" "-e" script]))
        process (.start pb)
        output (.getInputStream process)]
    (when-let [s (not-empty (stream-to-string (.getErrorStream process)))]
      (prn "ERROR:" s))
    (is (= "bye\n"  (stream-to-string output)))))
