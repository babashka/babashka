(ns babashka.impl.clojure.tools.reader-types
  (:require [clojure.tools.reader.edn :as edn]
            [clojure.tools.reader.reader-types :as rt]
            [sci.core :as sci]))

(def edn-namespace {'read-string (sci/copy-var edn/read-string (sci/create-ns 'clojure.tools.reader.edn))})

(def rtns (sci/create-ns 'clojure.tools.reader.reader-types))

(def reader-types-namespace {'indexing-reader? (sci/copy-var rt/indexing-reader? rtns)
                             'get-line-number (sci/copy-var rt/get-line-number rtns)
                             'get-column-number (sci/copy-var rt/get-column-number rtns)
                             'peek-char (sci/copy-var rt/peek-char rtns)
                             'read-char (sci/copy-var rt/read-char rtns)
                             'unread (sci/copy-var rt/unread rtns)
                             'source-logging-push-back-reader (sci/copy-var rt/source-logging-push-back-reader rtns)})
