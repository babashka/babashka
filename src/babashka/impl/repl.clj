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
   [sci.impl.utils :as utils]))

(set! *warn-on-reflection* true)

(defn repl-caught
  "Default :caught hook for repl"
  [^Throwable e]
  (sci/with-bindings {sci/out @sci/err}
    (let [{:keys [:file :line :column] :as d} (ex-data e)
          sci-error? (identical? :sci/error (:type d))
          ex-name (when sci-error?
                    (some-> ^Throwable (ex-cause e)
                            .getClass .getName))
          ex-message (when-let [m (.getMessage e)]
                       (when-not (str/blank? m)
                         m))]
      (sio/println (str ex-name
                        (when ex-message
                          (str (when ex-name ": ")
                               ex-message))
                        (when file
                          (str
                           (when (or ex-name ex-message)
                             " ")
                           "[at " file
                           (when line
                             (str ":" line ":" column))"]"))))
      (sio/flush))))

(defn skip-if-eol
  "Inspired by skip-if-eol from clojure.main."
  [s]
  (let [c (r/read-char s)]
    (when-not (= c \newline)
      (r/unread s c))))

(defn repl
  "REPL with predefined hooks for attachable socket server."
  ([sci-ctx] (repl sci-ctx nil))
  ([sci-ctx {:keys [:init :read :eval :need-prompt :prompt :flush :print :caught]}]
   (let [in @sci/in]
     (m/repl
      :init (or init
                (fn []
                  (sci/with-bindings {sci/out @sci/err}
                    (sio/println "Babashka"
                                 (str "v" (str/trim (slurp (io/resource "BABASHKA_VERSION"))))
                                 "REPL.")
                    (sio/println "Use :repl/quit or :repl/exit to quit the REPL.")
                    (sio/println "Clojure rocks, Bash reaches.")
                    (sio/println))
                  (eval-form sci-ctx `(apply require (quote ~m/repl-requires)))))
      :read (or read
                (fn [_request-prompt request-exit]
                  (if (nil? (r/peek-char in))
                    request-exit
                    (let [v (parser/parse-next sci-ctx in)]
                      (skip-if-eol in)
                      (if (or (identical? :repl/quit v)
                              (identical? :repl/exit v))
                        request-exit
                        v)))))
      :eval (or eval
                (fn [expr]
                  (sci/with-bindings {sci/file "<repl>"
                                      sci/*1 *1
                                      sci/*2 *2
                                      sci/*3 *3
                                      sci/*e *e}
                    (let [ret (eval-form sci-ctx expr)]
                      ret))))
      :need-prompt (or need-prompt (fn [] true))
      :prompt (or prompt #(sio/printf "%s=> " (utils/current-ns-name)))
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
