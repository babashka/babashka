;; QR codes in the terminal via libqrencode over babashka.ffi.
;; Usage: bb ffi-qrcode.clj [text]
;;
;; QRcode_encodeString returns a QRcode* - struct {int version; int width;
;; unsigned char *data;} read by offset: width at 4, data pointer at 8.
;; Each data byte's lowest bit is the module (1 = black).

(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[clojure.string :as str])

(ffi/load-system-library "qrencode")

;; QRcode *QRcode_encodeString(const char *string, int version,
;;                             QRecLevel level, QRencodeMode hint, int casesensitive)
(defcfn qr-encode "QRcode_encodeString" [:string :int :int :int :int] :pointer)
(defcfn qr-free "QRcode_free" [:pointer] :void)

(def QR-ECLEVEL-M 1)
(def QR-MODE-8 2)

(defn qr-modules
  "Encodes s, returns {:width w :modules bit-vector-of-booleans}."
  [s]
  (let [qr (qr-encode s 0 QR-ECLEVEL-M QR-MODE-8 1)]
    (when (ffi/null?* qr)
      (throw (ex-info "QR encoding failed" {:input s})))
    (try
      (let [width (ffi/read qr :int 4)
            data (ffi/read qr :pointer 8)]
        {:width width
         :modules (mapv #(odd? (ffi/read data :uint8 %))
                        (range (* width width)))})
      (finally (qr-free qr)))))

(defn print-qr
  "Prints the code with half-height blocks, two rows per text line."
  [{:keys [width modules]}]
  (let [at (fn [x y] (if (or (neg? y) (>= y width)) false
                         (nth modules (+ (* y width) x))))
        quiet 2
        line (apply str (repeat (+ width (* 2 quiet)) " "))]
    (println line)
    (doseq [y (range (- quiet) (+ width quiet) 2)]
      (println
       (str (str/join (repeat quiet " "))
            (apply str
                   (for [x (range width)]
                     (case [(boolean (at x y)) (boolean (at x (inc y)))]
                       [true true] "█"
                       [true false] "▀"
                       [false true] "▄"
                       [false false] " ")))
            (str/join (repeat quiet " ")))))))

(let [text (or (first *command-line-args*) "https://babashka.org")]
  (print-qr (qr-modules text))
  (println text))
