(import '[java.text Normalizer Normalizer$Form])

(defn normalize [text]
  (Normalizer/normalize text Normalizer$Form/NFC))

(def s (str "cafe" \u0301))

(assert (> (count s) (count (normalize s))))
