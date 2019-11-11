(with-open [reader (io/reader (io/file "test-resources" "test.csv"))]
  (doall
   (csv/read-csv reader)))
