(ns babashka.impl.repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.main :as m]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.reader-types :as r]
   [sci.impl.interpreter :refer [eval-form]]
   [sci.impl.parser :as parser]
   [sci.core :as sci]))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  ([sci-ctx] (repl sci-ctx nil))
  ([sci-ctx {:keys [:init :read :eval :need-prompt :prompt]}]
   (m/repl
    :init (or init
              #(do (println "Babashka"
                            (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
                            "REPL.")
                   (println "Use :repl/quit or :repl/exit to quit the REPL.")
                   (println "Clojure rocks, Bash reaches.")
                   (println)))
    :read (or read
              (fn [_request-prompt request-exit]
                (if (r/peek-char @sci/in) ;; if this is nil, we reached EOF
                  (let [v (parser/parse-next sci-ctx @sci/in)]
                    (if (or (identical? :repl/quit v)
                            (identical? :repl/exit v)
                            (identical? :edamame.impl.parser/eof v))
                      request-exit
                      v))
                  request-exit)))
    :eval (or eval
              (fn [expr]
                (let [ret (eval-form (update sci-ctx
                                             :env
                                             (fn [env]
                                               (swap! env update-in [:namespaces 'clojure.core]
                                                      assoc
                                                      '*1 *1
                                                      '*2 *2
                                                      '*3 *3
                                                      '*e *e)
                                               env))
                                     expr)]
                  ret)))
    :need-prompt (or need-prompt (fn [] true))
    :prompt (or prompt #(printf "%s=> " (-> sci-ctx :env deref :current-ns))))))

(defn start-repl!
  ([sci-ctx] (start-repl! sci-ctx nil))
  ([sci-ctx opts]
   (repl sci-ctx opts)))
