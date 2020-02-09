(ns babashka.java-security-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.test :as test :refer [deftest is]]))

(defn bb [expr]
  (edn/read-string (apply test-utils/bb nil [(str expr)])))

(defn signature [algo]
  (clojure.walk/postwalk-replace {::algo algo}
    '(defn signature [^String s]
       (let [algorithm (java.security.MessageDigest/getInstance ::algo)
             digest (.digest algorithm (.getBytes s))]
         (format "%032x" (java.math.BigInteger. 1 digest))))))

(deftest java-security-test
  (is (= "49f68a5c8493ec2c0bf489821c21fc3b" (bb (list 'do (signature "MD5") '(signature "hi")))))
  (is (= "c22b5f9178342609428d6f51b2c5af4c0bde6a42" (bb (list 'do (signature "SHA-1") '(signature "hi")))))  
  (is (= "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4" (bb (list 'do (signature "SHA-256") '(signature "hi"))))))
