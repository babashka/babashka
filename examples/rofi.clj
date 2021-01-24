(require '[babashka.process :as p])

(defn rofi [s]
  (let [proc (p/process
               ["rofi" "-i" "-dmenu" "-mesg" "Select" "-sync" "-p" "*"]
               {:in  s :err :inherit
                :out :string})]
    (:out @proc)))

(rofi (slurp *in*))

;; `echo "hi\nthere\nclj" | bb examples/rofi.clj`
