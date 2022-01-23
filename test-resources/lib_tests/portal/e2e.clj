(ns portal.e2e
  (:require [portal.colors :as c]))

(defn step [code]
  (binding [*out* *err*]
    (println "\n==> Enter to execute:" code "\n"))
  (read-line)
  (prn code))

(def pane-titles '("Alice" "Mad Hatter" "The Cake is a Lie"))

(defn options []
  {:portal.colors/theme
   (rand-nth (keys (dissoc c/themes ::c/vs-code-embedded)))
   :portal.launcher/window-title
   (rand-nth pane-titles)})

(defn -main [& args]
  (if (= (first args) "web")
    (step '(require '[portal.web :as p]))
    (step '(require '[portal.api :as p])))
  (step `(do (add-tap #'p/submit)
             (p/open ~(options))))
  (step '(tap> :hello-world))
  (step '(p/clear))
  (step '(require '[examples.data :refer [data]]))
  (step '(tap> data))
  (step '(p/clear))
  (step '(remove-tap #'p/submit))
  (step '(tap> :hello-world))
  (step '(p/eval-str "(js/alert 1)"))
  (step '(p/close)))
