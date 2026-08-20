;; scrypt via libcrypto's EVP_PBE_scrypt, replacing bb-workshop-conj26
;; spectre's shell-out to `openssl kdf ... SCRYPT`. Same parameters, same
;; expected output, timed against the shell-out.

(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[babashka.fs :as fs]
         '[babashka.process :as p])

;; version-pinned per-OS names: plain libcrypto.dylib is Apple's stub, which
;; aborts on load, so this NEEDS the OS-map form of load-library
(ffi/load-library {:mac "/opt/homebrew/opt/openssl@3/lib/libcrypto.3.dylib"
                   :linux "libcrypto.so.3"})

;; int EVP_PBE_scrypt(const char *pass, size_t passlen,
;;                    const unsigned char *salt, size_t saltlen,
;;                    uint64_t N, uint64_t r, uint64_t p, uint64_t maxmem,
;;                    unsigned char *key, size_t keylen)
(defcfn evp-pbe-scrypt "EVP_PBE_scrypt"
  [:string :size_t :pointer :size_t :uint64 :uint64 :uint64 :uint64 :pointer :size_t]
  :int)

(defn read-bytes ^bytes [p n]
  (let [out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte (ffi/read p :uint8 i))))
    out))

(defn scrypt-ffi
  ^bytes [^String passwd ^String salt n r p dk-len]
  (let [salt-ptr (ffi/string->ptr salt)
        key-ptr (ffi/alloc dk-len)
        maxmem (* 128 n r 2)]
    (try
      (let [rc (evp-pbe-scrypt passwd (count passwd)
                               salt-ptr (count salt)
                               n r p maxmem
                               key-ptr dk-len)]
        (when-not (= 1 rc)
          (throw (ex-info "EVP_PBE_scrypt failed" {:rc rc})))
        (read-bytes key-ptr dk-len))
      (finally
        (ffi/free salt-ptr)
        (ffi/free key-ptr)))))

;; the shell-out from bb-workshop-conj26 spectre.scrypt, for comparison
(defn hex ^String [^bytes b]
  (apply str (map #(format "%02x" (bit-and % 0xff)) b)))

(def openssl "/opt/homebrew/opt/openssl@3/bin/openssl")

(defn scrypt-shell
  ^bytes [^String passwd ^String salt n r p dk-len]
  (let [{:keys [out exit]}
        (p/sh {:out :bytes}
              openssl "kdf" "-keylen" (str dk-len) "-binary"
              "-kdfopt" (str "hexpass:" (hex (.getBytes passwd)))
              "-kdfopt" (str "hexsalt:" (hex (.getBytes salt)))
              "-kdfopt" (str "n:" n) "-kdfopt" (str "r:" r) "-kdfopt" (str "p:" p)
              "SCRYPT")]
    (assert (zero? exit))
    out))

(def expected
  "Hjq3C1jz1aJtpnIjb/tUJULap/wf6G82FkbpqFyHQZQhmv0II9mI56Y8A7mCrFoIiR2NBRlX2WElvbyvbRcZbA==")

(defn b64 [^bytes b] (String. (.encode (java.util.Base64/getEncoder) b)))

(let [t0 (System/nanoTime)
      via-ffi (scrypt-ffi "password" "salt" 32768 8 2 64)
      t1 (System/nanoTime)]
  (println "ffi result :" (if (= expected (b64 via-ffi)) "MATCHES test vector" "WRONG"))
  (println "ffi time   :" (format "%.0f ms" (/ (- t1 t0) 1e6))))

(when (fs/which openssl)
  (let [t0 (System/nanoTime)
        via-shell (scrypt-shell "password" "salt" 32768 8 2 64)
        t1 (System/nanoTime)]
    (println "shell result:" (if (= expected (b64 via-shell)) "MATCHES test vector" "WRONG"))
    (println "shell time  :" (format "%.0f ms" (/ (- t1 t0) 1e6)))))
