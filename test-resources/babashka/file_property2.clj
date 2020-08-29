(ns babashka.file-property2)

(prn (= *file* (System/getProperty "babashka.file")))
