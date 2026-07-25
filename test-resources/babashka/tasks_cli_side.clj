(ns babashka.tasks-cli-side)

;; Loading this namespace is observable, so a test can tell whether a
;; dependency's :requires were processed.
(println "SIDE EFFECT")
