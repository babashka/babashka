(ns babashka.impl.tasks-test
  (:require [babashka.impl.tasks :as sut]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [test-with-files.tools :refer [with-tmp-dir]]))

(t/deftest target-order-test
  (t/is (= '[quux bar foo]
           (sut/target-order
            {'foo {:depends ['bar 'quux]}
             'bar {:depends ['quux]}}
            'foo))))

(t/deftest key-order-test
  (let [edn "{:tasks
 {;; Development tasks
  repl        {:doc  \"Starts an nrepl session with a reveal window\"
               :task (clojure \"-M:reveal-nrepl\")}

  ;; Testing
  watch-tests {:doc  \"Watch tests and run on change\"
               :task (clojure \"-M:test -m kaocha.runner --watch\")}
  ;test
  #_{:doc  \"Runs tests\"
   :task (clojure \"-M:test -m kaocha.runner\")}
  }}"]
    (t/is (= '[repl watch-tests] (sut/key-order edn)))))

(t/deftest shell
  (t/testing "command"
    ;; The hostname tool is ubiquitous
    (let [process (sut/shell "hostname")]
      (t/is (= 0 (:exit process)))))

  (when sut/windows?
    ;; chcp.com is shipped with all MS-Windows versions.
    (t/testing "MS-Windows .com"
      (let [process (sut/shell "chcp")]
        (t/is (= 0 (:exit process)))))
    )
  )

(t/deftest windows-add-extension
  (when sut/windows?
    (with-tmp-dir tmp-dir
      ;; create test temp layout
      ;; tmp-dir
      ;;       |- a/x.bat
      ;;       |- b/x.cmd
      (let [t-a (.getAbsolutePath (io/file tmp-dir "a"))
            t-b (.getAbsolutePath (io/file tmp-dir "b"))
            t-a-x-bat (.getAbsolutePath (io/file t-a "x.bat"))
            t-b-x-cmd (.getAbsolutePath (io/file t-b "x.cmd"))
            t-a-x (.getAbsolutePath (io/file t-a "x"))
            p-ta-tb (str/join ";" [t-a t-b])
            p-tb-ta (str/join ";" [t-b t-a])]
        (doseq [p [t-a-x-bat t-b-x-cmd]]
          (io/make-parents p)
          (spit p nil))

        (t/testing "absolute"
          (t/is (= t-a-x-bat
                   (sut/ext-add-windows t-a-x-bat "" "")))

          ;; found via PATHEXT
          (t/is (= t-a-x-bat
                   (sut/ext-add-windows t-a-x "" ".bat")))
          ;; not found, in PATH but not in PATHEXT
          (t/is (= t-a-x
                   (sut/ext-add-windows t-a-x "" ".cmd"))))

        (t/testing "current working directory"
          ;; assuming the babashka repo starts with a lein
          ;; project. Any other file at the root will do.
          ;;
          ;; found via PATHEXT
          (t/is (= "project.CLJ"
                   (sut/ext-add-windows "project" "" ".CLJ"))))

        (t/testing "relative"
          ;; this file.
          ;;
          ;; found via PATHEXT
          (t/is (= "test/babashka/impl/tasks_test.CLJ"
                   (sut/ext-add-windows "test/babashka/impl/tasks_test" "" ".CLJ"))))

        (t/testing "solo filename"
          ;; found via PATH
          (t/is (= "x.bat"
                   (sut/ext-add-windows "x.bat" t-a "")))
          ;; not found, no PATHEXT
          (t/is (= "x"
                   (sut/ext-add-windows "x" t-a "")))
          ;; found via PATH
          (t/is (= "x.BAT"
                   (sut/ext-add-windows "x" t-a ".BAT")))
          ;; not found, PATH matches but no matching PATHEXT
          (t/is (= "x"
                   (sut/ext-add-windows "x" t-a ".CMD")))
          ;; found via PATH, PATHEXT
          (t/is (= "x.CMD"
                   (sut/ext-add-windows "x" t-b ".CMD")))
          ;; found via PATHEXT
          (t/is (= "x.CMD"
                   (sut/ext-add-windows "x" p-ta-tb ".CMD")))
          ;; found via first entry in PATH, PATHEXT
          (t/is (= "x.BAT"
                   (sut/ext-add-windows "x" p-ta-tb ".BAT;.CMD")))
          ;; found via first entry in PATH, PATHEXT
          (t/is (= "x.CMD"
                   (sut/ext-add-windows "x" p-tb-ta ".BAT;.CMD")))
          ;; found via first entry in PATH, PATHEXT
          (t/is (= "x.CMD"
                   (sut/ext-add-windows "x" p-tb-ta ".CMD;.BAT")))))
      )))
