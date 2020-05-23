(ns babashka.impl.repl
  {:no-doc true}
  (:require
   [babashka.impl.clojure.main :as m]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.reader.reader-types :as r]
   [sci.core :as sci]
   [sci.impl.interpreter :refer [eval-form]]
   [sci.impl.io :as sio]
   [sci.impl.parser :as parser]
   [sci.impl.vars :as vars]))

(set! *warn-on-reflection* true)

(defn repl-caught
  "Default :caught hook for repl"
  [^Throwable e]
  (sci/with-bindings {sci/out @sci/err}
    (let [d (ex-data e)
          sci-error? (identical? :sci/error (:type d))
          ex-name (when sci-error?
                    (some-> ^Throwable (ex-cause e)
                            .getClass .getName))]
      (sio/println (str ex-name
                        (when-let [m (.getMessage e)]
                          (str ": " m)) ))
      (sio/flush))))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  ([sci-ctx] (repl sci-ctx nil))
  ([sci-ctx {:keys [:init :read :eval :need-prompt :prompt :flush :print :caught]}]
   (let [in (r/indexing-push-back-reader (r/push-back-reader @sci/in))]
     (m/repl
      :init (or init
                #(do (sio/println "Babashka"
                                  (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
                                  "REPL.")
                     (sio/println "Use :repl/quit or :repl/exit to quit the REPL.")
                     (sio/println "Clojure rocks, Bash reaches.")
                     (sio/println)
                     (eval-form sci-ctx '(use 'clojure.repl))))
      :read (or read
                (fn [_request-prompt request-exit]
                  (let [v (parser/parse-next sci-ctx in)]
                    (if (or (identical? :repl/quit v)
                            (identical? :repl/exit v)
                            (identical? :edamame.impl.parser/eof v))
                      request-exit
                      v))))
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
      :prompt (or prompt #(sio/printf "%s=> " (vars/current-ns-name)))
      :flush (or flush sio/flush)
      :print (or print sio/prn)
      :caught (or caught repl-caught)))))

(defn start-repl!
  ([sci-ctx] (start-repl! sci-ctx nil))
  ([sci-ctx opts]
   (repl sci-ctx opts)))

;;;; Scratch

(comment
  (def in (-> (java.io.StringReader. "(+ 1 2 3)") clojure.lang.LineNumberingPushbackReader.))
  (r/peek-char in)
  (r/read-char in)
  )
