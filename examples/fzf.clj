(require '[babashka.process :as p])

(defn fzf [s]
  (let [proc (p/process ["fzf" "-m"]
                        {:in s :err :inherit
                         :out :string})]
    (:out @proc)))

(fzf (slurp *in*))
