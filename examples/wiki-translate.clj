#!/usr/bin/env bb
;; by Janne Himanka shared on Clojurians Slack
(require '[babashka.curl :as curl])

(let [url (str "https://en.wikipedia.org/wiki/" (first *command-line-args*))
      page (:body (curl/get url))]
  (cond
    (re-find #"Disambiguation" page)
    (doseq [item (map last (re-seq #"<li><a href...wiki/([^\"]+)" page))]
      (println item))
    :else (last (re-find #"nl.wikipedia.org/.+?title..([^\"]+)" page))))
