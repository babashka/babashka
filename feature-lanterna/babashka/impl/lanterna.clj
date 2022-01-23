(ns babashka.impl.lanterna
  {:no-doc true}
  (:require
   [lanterna.constants]
   [lanterna.screen]
   [lanterna.terminal]
   [sci.core :as sci :refer [copy-var]]))

(def tns (sci/create-ns 'lanterna.terminal nil))
(def sns (sci/create-ns 'lanterna.screen nil))
(def cns (sci/create-ns 'lanterna.constants nil))

(def lanterna-terminal-namespace
  {'add-resize-listener (copy-var lanterna.terminal/add-resize-listener tns)
   'remove-resize-listener (copy-var lanterna.terminal/remove-resize-listener tns)
   'get-terminal (copy-var lanterna.terminal/get-terminal tns)
   'start (copy-var lanterna.terminal/start tns)
   'stop (copy-var lanterna.terminal/stop tns)
   'get-size (copy-var lanterna.terminal/get-size tns)
   'move-cursor (copy-var lanterna.terminal/move-cursor tns)
   'put-character (copy-var lanterna.terminal/put-character tns)
   'put-string (copy-var lanterna.terminal/put-string tns)
   'clear (copy-var lanterna.terminal/clear tns)
   'flush (copy-var lanterna.terminal/flush tns)
   'set-fg-color (copy-var lanterna.terminal/set-fg-color tns)
   'set-bg-color (copy-var lanterna.terminal/set-bg-color tns)
   'set-style (copy-var lanterna.terminal/set-style tns)
   'get-key (copy-var lanterna.terminal/get-key tns)
   'get-key-blocking (copy-var lanterna.terminal/get-key-blocking tns)})

(def lanterna-screen-namespace
  {'terminal-screen (copy-var lanterna.screen/terminal-screen sns)
   'add-resize-listener (copy-var lanterna.screen/add-resize-listener sns)
   'remove-resize-listener (copy-var lanterna.screen/remove-resize-listener sns)
   'start (copy-var lanterna.screen/start sns)
   'stop (copy-var lanterna.screen/stop sns)
   'get-size (copy-var lanterna.screen/get-size sns)
   'redraw (copy-var lanterna.screen/redraw sns)
   'move-cursor (copy-var lanterna.screen/move-cursor sns)
   'get-cursor (copy-var lanterna.screen/get-cursor sns)
   'put-string (copy-var lanterna.screen/put-string sns)
   'put-sheet (copy-var lanterna.screen/put-sheet sns)
   'clear (copy-var lanterna.screen/clear sns)
   'get-key (copy-var lanterna.screen/get-key sns)
   'get-key-blocking (copy-var lanterna.screen/get-key-blocking sns)})

(def lanterna-constants-namespace
  {'charsets (copy-var lanterna.constants/charsets cns)
   'colors (copy-var lanterna.constants/colors cns)
   'styles (copy-var lanterna.constants/styles cns)
   'key-codes (copy-var lanterna.constants/key-codes cns)
   'sgr (copy-var lanterna.constants/sgr cns)})
