(ns nrepl.middleware.load-file
  "Middleware for loading entire files.

  Delegates to `interruptible-eval` for reading and evaluation while
  ensuring proper source file metadata (path, name, line numbers) is
  set for the compiler."
  {:author "Chas Emerick"
   :added  "0.4"}
  (:require [nrepl.middleware :as middleware :refer [set-descriptor!]]
            [nrepl.middleware.caught :as caught]
            [nrepl.middleware.interruptible-eval :as eval]
            [nrepl.middleware.print :as print]
            [nrepl.misc :refer [resolve-in-session]])
  (:import #_(clojure.lang Compiler) ;; BB-PATCH the image has no Compiler

           #_(nrepl.transport Transport) ;; BB-PATCH a sci protocol is not a class
))

;; In this middleware, we implement loading whole the file by nREPL from start
;; to finish. This used to be implemented by calling out to `Compiler/load`.
;; However, `Compiler/load` doesn't allow to customize the evaluation function
;; it uses, so now the middleware is implemented completely by delegating to
;; `interruptible-eval` middleware to do the reading and evaling. All that's
;; left to do here is to make sure that the behavior is still consistent with
;; the old approach.

#_(defn- per-file-bindings [msg]
  {Compiler/METHOD nil
   Compiler/LOCAL_ENV nil
   Compiler/LOOP_LOCALS nil
   Compiler/NEXT_LOCAL_NUM 0
   ;; We don't set LINE_BEFORE, COLUMN_BEFORE, LINE_AFTER, COLUMN_AFTER because
   ;; it looks like it doesn't change much for our usecase. But this is still to
   ;; be confirmed.
   #'*read-eval* true
   ;; This function runs in "server context" (not yet in session context), so
   ;; make sure to resolve dynvar variables from session.
   #'*ns* (resolve-in-session msg *ns*)
   #'*unchecked-math* (resolve-in-session msg *unchecked-math*)
   #'*warn-on-reflection* (resolve-in-session msg *warn-on-reflection*)
   #'*data-readers* (resolve-in-session msg *data-readers*)}) ;; BB-PATCH sci has no compiler state to reset per file
(defn- per-file-bindings [msg]
  {#'*read-eval* true
   #'*ns* (resolve-in-session msg *ns*)
   #'*unchecked-math* (resolve-in-session msg *unchecked-math*)
   #'*warn-on-reflection* (resolve-in-session msg *warn-on-reflection*)
   #'*data-readers* (resolve-in-session msg *data-readers*)})

(defn- load-file-eval-msg
  [{:keys [file file-path transport] :as msg}]
  (let [last-result (volatile! nil)
        error-encountered (volatile! false)
        wrapped-t (reify nrepl.transport/Transport
                    (recv [_this] (nrepl.transport/recv transport))
                    (recv [_this timeout] (nrepl.transport/recv transport timeout))
                    (send [_this resp]
                      ;; Whenever the result is returned to the client, remember
                      ;; and don't send it, since `load-file` is supposed to
                      ;; only "return" the result of the last form in the file.
                      (if (contains? resp :value)
                        (vreset! last-result resp)
                        (do (when (:ex resp)
                              ;; If an error is thrown, don't send any
                              ;; successful result to the client.
                              (vreset! error-encountered true))
                            ;; This is our signal to send the last result back
                            ;; to the client before sending `done`. Dissoc `:ns`
                            ;; to avoid confusing tools which assume any
                            ;; :ns always means *ns*.
                            (when (and (contains? (:status resp) :done)
                                       (not @error-encountered)
                                       @last-result)
                              (nrepl.transport/send transport (dissoc @last-result :ns)))
                            (nrepl.transport/send transport resp)))))]
    (-> (dissoc msg :file-path)
        (assoc :op "eval", :code file, :transport wrapped-t, :file file-path
               ::eval/stop-on-error true
               ::eval/bindings (per-file-bindings msg)))))

(defn wrap-load-file
  "Middleware that evaluates a file's contents, as per load-file,
   but with all data supplied in the sent message (i.e. safe for use
   with remote REPL environments).

   This middleware depends on the availability of an :op \"eval\"
   middleware below it (such as interruptible-eval)."
  [h]
  (fn [{:keys [op] :as msg}]
    (if (not= op "load-file")
      (h msg)
      (h (load-file-eval-msg msg)))))

(set-descriptor! #'wrap-load-file
                 {:requires #{#'caught/wrap-caught #'print/wrap-print}
                  :expects #{"eval"}
                  :handles {"load-file"
                            {:doc "Loads a body of code, using supplied path and filename info to set source file and line number metadata. Delegates to underlying \"eval\" middleware/handler."
                             :requires {"file" "Full contents of a file of code."}
                             :optional (merge caught/wrap-caught-optional-arguments
                                              print/wrap-print-optional-arguments
                                              {"file-path" "Source-path-relative path of the source file, e.g. clojure/java/io.clj"
                                               "file-name" "Name of source file, e.g. io.clj"})
                             :returns (-> (meta #'eval/interruptible-eval)
                                          (get-in [::middleware/descriptor :handles "eval" :returns])
                                          (dissoc "ns"))}}})
