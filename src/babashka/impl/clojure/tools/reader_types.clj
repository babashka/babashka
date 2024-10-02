(ns babashka.impl.clojure.tools.reader-types
  (:require [clojure.tools.reader.edn :as edn]
            [clojure.tools.reader.reader-types :as rt]
            [sci.core :as sci]))

(def tr-edn-ns (sci/create-ns 'clojure.tools.reader.edn))

(def edn-namespace {'read-string (sci/copy-var edn/read-string tr-edn-ns)
                    'read (sci/copy-var edn/read tr-edn-ns)})

(def rtns (sci/create-ns 'clojure.tools.reader.reader-types))

(def reader-types-namespace {'indexing-reader? (sci/copy-var rt/indexing-reader? rtns)
                             'get-line-number (sci/copy-var rt/get-line-number rtns)
                             'get-column-number (sci/copy-var rt/get-column-number rtns)
                             'peek-char (sci/copy-var rt/peek-char rtns)
                             'read-char (sci/copy-var rt/read-char rtns)
                             'unread (sci/copy-var rt/unread rtns)
                             'source-logging-push-back-reader (sci/copy-var rt/source-logging-push-back-reader rtns)
                             'source-logging-reader? (sci/copy-var rt/source-logging-reader? rtns)
                             'string-push-back-reader (sci/copy-var rt/string-push-back-reader rtns)})
