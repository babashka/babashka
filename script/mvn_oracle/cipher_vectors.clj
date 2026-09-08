;; Ground truth for babashka.mvn.cipher: encrypt with plexus-cipher 2.0 and
;; decrypt through plexus-sec-dispatcher 2.0, the pair MIMA wires for
;; tools.deps. Prints EDN.
;; Run: clojure -Sdeps '{:deps {org.codehaus.plexus/plexus-cipher {:mvn/version "2.0"} org.codehaus.plexus/plexus-sec-dispatcher {:mvn/version "2.0"}}}' -M script/mvn_oracle/cipher_vectors.clj
(import '[org.sonatype.plexus.components.cipher DefaultPlexusCipher]
        '[org.sonatype.plexus.components.sec.dispatcher DefaultSecDispatcher])
(require '[clojure.java.io :as io])

(def cipher (DefaultPlexusCipher.))
(def master "s3cr3t-m4ster!")
(def master-blob (.encryptAndDecorate cipher master DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION))

(def sec-file (java.io.File/createTempFile "settings-security" ".xml"))
(spit sec-file (str "<settingsSecurity><master>" master-blob "</master></settingsSecurity>"))

(def dispatcher (doto (DefaultSecDispatcher. cipher)
                  (.setConfigurationFile (.getPath sec-file))))

(def plaintexts ["hunter2" "p@ss{word}" "with\\{escaped" "ünïcödé ✓" "" "x"])

(def cases
  (for [p plaintexts
        :let [blob (.encryptAndDecorate cipher p master)]]
    {:plain p
     :blob blob
     :dispatcher (.decrypt dispatcher blob)
     :in-text (.decrypt dispatcher (str "before " blob " after"))}))

(prn {:master-key DefaultSecDispatcher/SYSTEM_PROPERTY_SEC_LOCATION
      :master master
      :master-blob master-blob
      :not-encrypted (.decrypt dispatcher "plain-password")
      :escaped-braces (.decrypt dispatcher "\\{not-a-blob\\}")
      :cases (vec cases)})
