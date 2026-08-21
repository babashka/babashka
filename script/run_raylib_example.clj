;; Runs one b12n-raylib-clj example against the ported babashka.ffi bindings.
;;
;;   bb -cp port:<their-src> /tmp/run-example.clj <example-ns> [seconds] [shot.png]
;;
;; Their examples loop until window-should-close?, so a deadline is grafted
;; onto that var; everything stays on the main thread, which GLFW requires.
(require '[babashka.ffi :as ffi :refer [defcfn]]
         '[raylib.core.window]
         '[raylib.core.drawing])

(defcfn screenshot "TakeScreenshot" [:string] :void)

(let [[ns-name secs shot] *command-line-args*
      secs (parse-long (or secs "5"))
      sym (symbol ns-name)
      stop (+ (System/currentTimeMillis) (* 1000 secs))
      frames (atom 0)]
  (require sym)
  (let [close-var (ns-resolve 'raylib.core.window 'window-should-close?)
        orig-close @close-var
        end-var (ns-resolve 'raylib.core.drawing 'end-drawing!)
        orig-end @end-var]
    (alter-var-root close-var
                    (constantly (fn [] (or (orig-close)
                                           (> (System/currentTimeMillis) stop)))))
    (when shot
      (alter-var-root end-var
                      (constantly (fn []
                                    (orig-end)
                                    (when (= 60 (swap! frames inc)) (screenshot shot))))))
    (println "running" ns-name "for" secs "s")
    ((ns-resolve (find-ns sym) '-main))
    (println "finished" ns-name)))
