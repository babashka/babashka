(ns babashka.interop-test
  (:require
   [babashka.test-utils :as test-utils]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is testing]]))

(defn bb [input & args]
  (test-utils/normalize
   (edn/read-string
    {:readers *data-readers*
     :eof nil}
    (apply test-utils/bb (when (some? input) (str input)) (map str args)))))

(deftest vthreads-test
  (testing "can invoke methods on java.lang.VirtualThread"
    (is (= "" (bb nil "(set-agent-send-off-executor! (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)) @(future (.getName (Thread/currentThread)))"))))
  (is (= [false true]
         (bb nil (pr-str '(do
                            (def t (Thread. (fn [])))
                            (def vt (Thread/startVirtualThread (fn [])))
                            [(.isVirtual t) (.isVirtual vt)])))))
  (is (bb nil (pr-str '(instance?
                        java.util.concurrent.Executor
                        (java.util.concurrent.Executors/newThreadPerTaskExecutor (-> (Thread/ofVirtual) (.name "fusebox-thread-" 1) (.factory))))))))

(deftest threads-test
  (is (bb nil (pr-str '(-> (Thread/ofPlatform)
                           (.daemon)
                           (.start (fn []))
                           (.isDaemon))))))

(deftest domain-sockets-test
  (is (= :success (bb nil (slurp "test-resources/domain_sockets.bb")))))

(deftest byte-channels-test
  (is (= :success (bb nil (slurp "test-resources/bytechannel_and_related_classes.bb")))))

(deftest proxy-inputstream-outputstream-test
  (is (= :success (bb nil (slurp "test-resources/proxy_inputstream_outputstream.bb")))))

(deftest map-entry-create-test
  (is (true? (bb nil "(= (first {1 2})
                         (clojure.lang.MapEntry. 1 2)
                         (clojure.lang.MapEntry/create 1 2))"))))

(deftest X509Certificate-test
  (is (true? (bb nil "(import java.security.cert.X509Certificate)
(import java.security.cert.CertificateFactory)
(require '[clojure.java.io :as io])
(defn x509-certificate
  ^X509Certificate
  [f]
  (let [input   (io/input-stream f)
        factory (CertificateFactory/getInstance \"X.509\")]
    (.generateCertificate factory input)))
(def cert (x509-certificate (io/file \"test-resources/certificate.crt\")))
(some? (.getSubjectX500Principal cert))
"))))

(deftest ECDH-test
  (is (true? (bb nil "
(import
 '[java.security KeyPairGenerator MessageDigest]
 '[java.security.spec ECGenParameterSpec]
 '[javax.crypto KeyAgreement]
 '[javax.crypto.spec SecretKeySpec])

(def keypair-algo \"EC\")
(def keypair-curve \"secp256r1\")
(def key-agreement-algo \"ECDH\") ; Elliptic Curve Diffie-Hellman
(def key-digest-algo \"SHA-256\")
(def key-encryption-algo \"AES\")

(defn keypair
  \"Generates a new key pair with the given alias, using the keypair-algo and keypair-curve\"
  []
  (let [keygen (KeyPairGenerator/getInstance keypair-algo)]
    (.initialize keygen (ECGenParameterSpec. keypair-curve))
    (.generateKeyPair keygen)))

(defn symmetric-key
  \"Generates a symmetric key using Elliptic Curve Diffie-Hellman based on a given local private and a remote public key\"
  [private-key public-key]
  ; Derive shared secret
  (let [shared-secret
        (let [key-agreement (KeyAgreement/getInstance key-agreement-algo)]
          (.init key-agreement private-key)
          (.doPhase key-agreement public-key true)
          (.generateSecret key-agreement))
        symmetric-key
        (let [message-digest (MessageDigest/getInstance key-digest-algo)
              hash-bytes (.digest message-digest shared-secret)
              key-bytes (byte-array (subvec (vec hash-bytes) 0 32))] ; extracts the first 256 bits for AES key
          (SecretKeySpec. key-bytes key-encryption-algo))]
    symmetric-key))

(let [[kp1 kp2] [(keypair) (keypair)]
      [private public] [(.getPrivate kp1) (.getPublic kp2)]
      symmetric (symmetric-key private public)]
  (some? (.getAlgorithm symmetric)))
"))))

(deftest IntStream-test
  (is (pos? (bb nil "(.count (.codePoints \"woof🐕\"))"))))

(deftest Thread-sleep-test
  (is (bb nil "(Thread/sleep (int 1))
               (Thread/sleep (java.time.Duration/ofMillis 1))
               true")))

(deftest SSL-test
  (is (= :user/success
         (bb nil "(try (.createSocket (javax.net.ssl.SSLSocketFactory/getDefault) \"localhost\" 4444) (catch java.net.ConnectException e ::success))")))
  (is (bb nil " (.startHandshake (.createSocket (javax.net.ssl.SSLSocketFactory/getDefault) \"clojure.org\" 443)) ::success")))

(deftest jio-line-number-reader-test
  (is (= 2 (bb nil "(def rdr (java.io.LineNumberReader. (java.io.StringReader. \"foo\nbar\")))
                    (binding [*in* rdr] (read-line) (read-line)) (.getLineNumber rdr)"))))

(deftest FI-coercion
  (is (true? (bb nil "(= [1 3] (into [] (doto (java.util.ArrayList. [1 2 3]) (.removeIf even?))))")))
  (is (true? (bb nil "(= \"abcabc\" (.computeIfAbsent (java.util.HashMap.) \"abc\" #(str % %)))")))
  (is (true? (bb nil "(= '(\\9) (-> \"a9-\" seq .stream (.filter Character/isDigit) stream-seq!))")))
  (is (true? (bb nil "(require (quote [clojure.java.io :as jio])) (import [java.io File] [java.nio.file Path Files DirectoryStream$Filter]) (pos? (count (seq (Files/newDirectoryStream (.toPath (jio/file \".\"))
                      #(-> ^Path % .toFile .isDirectory)))))")))
  (is (true? (bb nil "(import [java.util Collection] [java.util.stream Stream] [java.util.function Predicate])
                      (= '(100 100 100 100 100) (->> (Stream/generate (constantly 100)) stream-seq! (take 5)))")))
  (is (true? (bb nil "(import [java.util Collection] [java.util.stream Stream] [java.util.function Predicate])
                      (= '(1 2 3 4 5 6 7 8 9 10) (->> (Stream/iterate 1 inc) stream-seq! (take 10)))"))))

(deftest regression-test
  (is (true? (bb nil "(let [x \"f\"] (String/.startsWith \"foo\" x))"))))

(deftest clojure-1_12-interop-test
  (is (= [1 2 3] (bb nil "(map Integer/parseInt [\"1\" \"2\" \"3\"])")))
  (is (= [1 2 3] (bb nil "(map String/.length [\"1\" \"22\" \"333\"])")))
  (is (= ["1" "22" "333"] (bb nil "(map String/new [\"1\" \"22\" \"333\"])")))
  (is (= 3 (bb nil "(String/.length \"123\")")))
  (is (= "123" (bb nil "(String/new \"123\")"))))

(deftest clojure-1_12-array-test
  (is (true? (bb nil "(instance? Class long/1)"))))

(deftest keygen-test
  (is (true?
        (bb nil
            '(do (ns keygen
                   (:import [java.security KeyPairGenerator Signature]))

                 (defn generate-key-pair
                   "Generates a public/private key pair."
                   []
                   (let [keygen (KeyPairGenerator/getInstance "RSA")]
                     (.initialize keygen 2048)
                     (.generateKeyPair keygen)))

                 (defn create-signature
                   "Signs the given message using the private key."
                   [private-key message]
                   (let [signature (Signature/getInstance "SHA256withRSA")]
                     (.initSign signature private-key)
                     (.update signature (.getBytes message "UTF-8"))
                     (.sign signature)))

                 (defn verify-signature
                   "Verifies the given signed data using the public key."
                   [public-key message signed-data]
                   (let [signature (Signature/getInstance "SHA256withRSA")]
                     (.initVerify signature public-key)
                     (.update signature (.getBytes message "UTF-8"))
                     (.verify signature signed-data)))

                 (let [key-pair (generate-key-pair)
                       private-key (.getPrivate key-pair)
                       public-key (.getPublic key-pair)
                       message "This is a secret message"
                       signed-data (create-signature private-key message)]
                   (verify-signature public-key message signed-data))))))

  (is (true?
        (bb nil '(do (import
                       '[java.security KeyPairGenerator]
                       '[java.security.spec ECGenParameterSpec])

                     (def keypair-algo "EC")
                     (def keypair-curve "secp256r1")

                     (defn keypair
                       "Generates a new key pair with the given alias, using the keypair-algo and keypair-curve"
                       []
                       (let [keygen (KeyPairGenerator/getInstance keypair-algo)]
                         (.initialize keygen (ECGenParameterSpec. keypair-curve))
                         (.generateKeyPair keygen)))

                     (let [kp (keypair)
                           pk (.getPublic kp)]
                       (bytes? (.getEncoded pk))))))))

;; RT iter test
(deftest clojure-RT-iter-test
  (is (= (iterator-seq (.iterator [1 2 3]))
         (bb nil '(do (ns test
                        (:import [clojure.lang RT]))
                      (iterator-seq (clojure.lang.RT/iter [1 2 3])))))))

(deftest posix-file-attributes
  (when-not test-utils/windows?
    (is (true?
           (bb nil
               '(do
                  (import
                   [java.nio.file Files LinkOption Path]
                   [java.nio.file.attribute PosixFileAttributes])
                  (->> (Files/readAttributes (Path/of "test-resources/posix-file-attributes.txt"
                                                      (into-array String []))
                                             PosixFileAttributes
                                             ^"[Ljava.nio.file.LinkOption;"
                                             (into-array LinkOption []))
                       .permissions
                       (instance? java.util.Set))))))))

(deftest posix-file-attributes-principals
  (when-not test-utils/windows?
    (is (true?
           (bb nil
               '(do
                  (import
                   [java.nio.file Files LinkOption Path]
                   [java.nio.file.attribute GroupPrincipal PosixFileAttributes
                    UserPrincipal])
                 (let [attributes (Files/readAttributes (Path/of "test-resources/posix-file-attributes.txt"
                                                                 (into-array String []))
                                                        PosixFileAttributes
                                                        ^"[Ljava.nio.file.LinkOption;"
                                                        (into-array LinkOption []))
                       owner (.owner attributes)
                       group (.group attributes)]
                   (and (instance? UserPrincipal owner)
                        (instance? GroupPrincipal group)))))))))
(deftest filesystem-not-found-exception
  (is (true?
       (bb nil
           '(do
             (import [java.net URI]
                     [java.nio.file FileSystemNotFoundException FileSystems])
             (let [uri (URI. (str "jar:file:/tmp/foo-"
                                  (random-uuid)
                                  "-standalone.jar!/tmp/bar-"
                                  (random-uuid)))]
               (try
                 (FileSystems/getFileSystem uri)
                 (catch FileSystemNotFoundException _
                   true))))))))

(deftest extended-attributes
  (is (true?
       (bb nil
           '(do
             (import
              [java.nio.file Files LinkOption Path]
              [java.nio.file.attribute UserDefinedFileAttributeView])
             (instance? UserDefinedFileAttributeView
                        (Files/getFileAttributeView (Path/of "test-resources/extended-attributes.txt"
                                                             (into-array String []))
                                                    UserDefinedFileAttributeView
                                                    ^"[Ljava.nio.file.LinkOption;"
                                                    (into-array LinkOption []))))))))

;; exercise a sampling of the superclass resolutions from the :public-class fn in
;; babashka.impl.classes/gen-class-map
(deftest public-class-resolutions
  (testing "Charset"
    (is (= "UTF-8" (bb nil "(.displayName (java.nio.charset.Charset/forName  \"UTF-8\"))"))))
  (testing "InputStream"
    (is (zero? (bb nil "(with-open [is (java.io.InputStream/nullInputStream)]
                           (.available is))"))))
  (testing "Throwable"
    ; compare output from ex-message to calling .getMessage
    (let [return-throwable "(try (yaml/parse-string \"abc: def: ghi\") (catch Exception e e))"]
      (is (= (bb nil (str "(ex-message " return-throwable ")"))
             (bb nil (str "(.getMessage " return-throwable ")"))))))
  (testing "jsoup Element"
    (is (= "form" (bb nil "(.tagName (first (.getElementsByTag (org.jsoup.Jsoup/parseBodyFragment \"<form></form>\") \"form\")))")))))

(deftest cached-thread-pool
  (is (= 3 (bb nil "(import '(java.util.concurrent Executors ExecutorService))
                    (let [executor (Executors/newCachedThreadPool)
                          fut (.submit ^ExecutorService executor ^Callable (fn [] 3))]
                      (try (.get fut) (finally (.shutdown executor))))")))
  (is (nil? (bb nil "(import '(java.util.concurrent Executors ExecutorService))
                     (let [executor (Executors/newCachedThreadPool)
                           fut (.submit ^ExecutorService executor ^Runnable (fn [] 3))]
                       (try (.get fut) (finally (.shutdown executor))))"))))

(deftest break-iterator-test
  (is (= 1 (bb nil "(load-file \"test-resources/break_iterator_test.clj\")"))))

(deftest clojure-lang-Var-binding-frame-test
  (is (= [43 42 43 42] (bb nil "(def ^:dynamic *test-var* 42)
   (def results (atom []))
   (binding [*test-var* *test-var*]
    (let [current-frame (clojure.lang.Var/cloneThreadBindingFrame)
          frame (clojure.lang.Var/cloneThreadBindingFrame)]
      (assert (not (identical? current-frame frame)))
      (binding [*test-var* 43]
        (let [inner-frame (clojure.lang.Var/getThreadBindingFrame)]
          (swap! results conj *test-var*)
          (clojure.lang.Var/resetThreadBindingFrame frame)
          (swap! results conj *test-var*)
          (clojure.lang.Var/resetThreadBindingFrame inner-frame)
          (swap! results conj *test-var*)))
      (swap! results conj *test-var*)))
   @results"))))

(deftest clojure-lang-Var-intern-test
  (bb nil "(ns foo) (ns bar)
(assert (var? (clojure.lang.Var/intern (the-ns 'foo) 'dude)))
(assert (var? (clojure.lang.Var/intern (the-ns 'foo) 'dude 2)))
"))

(deftest TextNormalizer-test
  (bb nil "(load-file \"test-resources/text_normalizer_test.clj\")"))

(deftest non-daemon-thread-test
  (when test-utils/native?
    (is (< 500 (bb nil "(import '(java.time Instant Duration))

(defn jvm-uptime-seconds []
  (let [start (-> (java.lang.ProcessHandle/current)
                  .info
                  .startInstant
                  .get)
        now   (Instant/now)]
    (.toMillis (Duration/between start now))))

(.start (doto (Thread. (fn [] (Thread/sleep 500)
                         (println (jvm-uptime-seconds))))
          (.setDaemon false)))")))
    (is (nil? (bb nil "--force-exit" "(import '(java.time Instant Duration))

(defn jvm-uptime-seconds []
  (let [start (-> (java.lang.ProcessHandle/current)
                  .info
                  .startInstant
                  .get)
        now   (Instant/now)]
    (.toMillis (Duration/between start now))))

(.start (doto (Thread. (fn [] (Thread/sleep 500)
                         (println (jvm-uptime-seconds))))
          (.setDaemon false)))")))
    (is (nil? (bb nil "(import '(java.time Instant Duration))
(defn jvm-uptime-seconds []
  (let [start (-> (java.lang.ProcessHandle/current)
                  .info
                  .startInstant
                  .get)
        now   (Instant/now)]
    (.toMillis (Duration/between start now))))

(.start (doto (Thread. (fn [] (Thread/sleep 500)
                         (println (jvm-uptime-seconds))))
;; DIFFERENT
(.setDaemon true)))")))))

(deftest clojure-lang-MultiFn-addMethod-test
  (is (= [2 0] (bb nil "
(def results (atom []))
(defmulti x (fn[_] :inc))
(.addMethod x :inc inc)
(swap! results conj (x 1))
(.addMethod x :inc dec)
(swap! results conj (x 1))
@results"))))

(deftest java-security-setProperty-test
  (when test-utils/native?
    (is (= 37
           (bb nil '(do (java.security.Security/setProperty "jdk.tls.disabledAlgorithms" "SSLv3, TLSv1, TLSv1.1, DTLSv1.0, RC4, DES, MD5withRSA, DH keySize < 1024, EC keySize < 224, 3DES_EDE_CBC, anon, NULL, ECDH, rsa_pkcs1_sha1 usage HandshakeSignature, ecdsa_sha1 usage HandshakeSignature, dsa_sha1 usage HandshakeSignature")
                        (count (.getSupportedCipherSuites (javax.net.ssl.SSLSocketFactory/getDefault)))))))
    (when-not test-utils/windows?
      (is (= 37 (bb nil "(System/setProperty \"java.security.properties\" \"test-resources/java.security\")
                       (import '[javax.net.ssl SSLSocketFactory]) (count (.getSupportedCipherSuites (javax.net.ssl.SSLSocketFactory/getDefault)))"))))))

(deftest java-security-DigestOutputStream-test
  (is (true? (bb nil "(ns script
  (:import [java.io OutputStream]
           [java.security DigestOutputStream MessageDigest]))

(let [data (byte-array [0x61 0x62 0x63])
      sink-digest (MessageDigest/getInstance \"SHA256\")
      standalone-digest (MessageDigest/getInstance \"SHA256\")]

  (with-open [sink (DigestOutputStream. (OutputStream/nullOutputStream) sink-digest)]
    (.write sink data))

  (.update standalone-digest data)

  (= (seq (.digest standalone-digest)) (seq (.digest sink-digest))))"))))
