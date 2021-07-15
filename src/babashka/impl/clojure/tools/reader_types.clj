(ns babashka.impl.clojure.tools.reader-types
  (:require [clojure.tools.reader.edn :as edn]
            [clojure.tools.reader.reader-types :as rt]))

(def edn-namespace {'read-string read-string})

(def reader-types-namespace {'indexing-reader? rt/indexing-reader?
                             'get-line-number rt/get-line-number
                             'get-column-number rt/get-column-number
                             'peek-char rt/peek-char
                             'read-char rt/read-char
                             'unread rt/unread
                             'source-logging-push-back-reader rt/source-logging-push-back-reader})
