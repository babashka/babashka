(ns babashka.impl.repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.main :as m]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.reader-types :as r]
   [sci.core :as sci]
   [sci.impl.interpreter :refer [eval-form]]
   [sci.impl.opts :refer [init]]
   [sci.impl.parser :as parser]))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  [sci-ctx]
  (let [in (r/indexing-push-back-reader (r/push-back-reader *in*))]
    (m/repl
     :init #(do (println "Babashka"
                         (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
                         "REPL.")
                (println "Use :repl/quit or :repl/exit to quit the REPL.")
                (println "Clojure rocks, Bash reaches.")
                (println))
     :read (fn [_request-prompt request-exit]
             (if (r/peek-char in) ;; if this is nil, we reached EOF
               (let [v (parser/parse-next sci-ctx in)]
                 (if (or (identical? :repl/quit v)
                         (identical? :repl/exit v)
                         (identical? :edamame.impl.parser/eof v))
                   request-exit
                   v))
               request-exit))
     :eval (fn [expr]
             (let [ret (sci/with-bindings {sci/in *in*
                                           sci/out *out*
                                           sci/err *err*}
                         (eval-form (update sci-ctx
                                            :env
                                            (fn [env]
                                              (swap! env update-in [:namespaces 'clojure.core]
                                                     assoc
                                                     '*1 *1
                                                     '*2 *2
                                                     '*3 *3
                                                     '*e *e)
                                              env))
                                    expr))]
               ret))
     :need-prompt (fn [] true)
     :prompt #(printf "%s=> " (-> sci-ctx :env deref :current-ns)))))

(defn start-repl! [ctx]
  (let [sci-ctx (init ctx)]
    (repl sci-ctx)))
