(ns babashka.impl.lanterna
  {:no-doc true}
  (:require
   [lanterna.screen]
   [lanterna.terminal]
   [sci.impl.namespaces :refer [copy-var]]
   [sci.impl.vars :as vars]))

(def tns (vars/->SciNamespace 'lanterna.terminal nil))
(def sns (vars/->SciNamespace 'lanterna.screen nil))

(def lanterna-terminal-namespace
  {'text-terminal (copy-var lanterna.terminal/text-terminal tns)
   'put-string (copy-var lanterna.terminal/put-string tns)
   'flush (copy-var lanterna.terminal/flush tns)
   'clear (copy-var lanterna.terminal/clear tns)
   'start (copy-var lanterna.terminal/start tns)
   'stop (copy-var lanterna.terminal/stop tns)})

(def lanterna-screen-namespace
  ;; TODO
  {;; 'put-string (copy-var lanterna.screen/put-string sns)
   ;; 'redraw (copy-var lanterna.screen/redraw sns)
   ;; 'terminal-screen  (copy-var lanterna.screen/terminal-screen sns)
   ;; 'start (copy-var lanterna.screen/start sns)
   })
