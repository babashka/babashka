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
             digest    (.digest algorithm (.getBytes s))
             size      (get {"SHA-256" 64} ::algo 32)]
         (format (str "%0" size "x") (java.math.BigInteger. 1 digest))))))

(deftest java-security-test
  (is (= "49f68a5c8493ec2c0bf489821c21fc3b" (bb (list 'do (signature "MD5") '(signature "hi")))))
  (is (= "c22b5f9178342609428d6f51b2c5af4c0bde6a42" (bb (list 'do (signature "SHA-1") '(signature "hi")))))
  (is (= "8f434346648f6b96df89dda901c5176b10a6d83961dd3c1ac88b59b2dc327aa4" (bb (list 'do (signature "SHA-256") '(signature "hi")))))
  (is (= "035afb1672de25549287fa4f6c108c1269c2a1d2390bf069520a95d1fec25e85" (bb (list 'do (signature "SHA-256") '(signature "654321f5fab07590a9e77e19ac4ccf53c8ab05f232b197432b62f2ec0677651bfc4c04"))))))
