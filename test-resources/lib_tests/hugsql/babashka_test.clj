(ns hugsql.babashka-test
  (:require [babashka.fs :as fs]
            [clojure.test :as t :refer [deftest is]]
            [hugsql.core :as hugsql]))

(def sql-file (fs/file (fs/parent *file*) "characters.sql"))
(hugsql/def-db-fns sql-file)
(hugsql/def-sqlvec-fns sql-file)

(declare characters-by-ids-specify-cols-sqlvec)

(deftest sqlvec-test
  (is (= ["select name, specialty from characters\nwhere id in (?,?)" 1 2]
         (characters-by-ids-specify-cols-sqlvec {:ids [1 2], :cols ["name" "specialty"]}))))
