(ns me.raynes.core-test
  (:refer-clojure :exclude [name parents])
  (:require [me.raynes.fs :refer :all]
            ;; BB-TEST-PATCH: remove compression ns (requires unavailable classes from apache commons)
            #_[me.raynes.fs.compression :refer :all]
            ;; BB-TEST-PATCH: remove midje (needs currently unavailable classes) and add mock midje ns
            #_[midje.sweet :refer :all]
            [me.raynes.mock-midje :refer [fact]]
            [clojure.java.io :as io]
            [clojure.string :as string])
  (:import java.io.File))

(def system-tempdir (System/getProperty "java.io.tmpdir"))

(def fs-supports-symlinks? (not (.startsWith (System/getProperty "os.name") "Windows")))

(defn create-walk-dir []
  (let [root (temp-dir "fs-")]
    (mkdir (file root "a"))
    (mkdir (file root "b"))
    (spit (file root "1") "1")
    (spit (file root "a" "2") "1")
    (spit (file root "b" "3") "1")
    root))

(fact "Makes paths absolute."
  (file ".") => *cwd*
  (file "foo") => (io/file *cwd* "foo"))


(fact "Expands path to current user."
  (let [user (System/getProperty "user.home")]
    (expand-home "~") => (file user)
    (expand-home (str "~" File/separator "foo")) => (file user "foo")))

(fact "Expands to given user."
  (let [user (System/getProperty "user.home")
        name (System/getProperty "user.name")]
    (expand-home (str "~" name)) => (file user)
    (expand-home (format "~%s/foo" name)) => (file user "foo")))

(fact "Expand a path w/o tilde just returns path"
      (let [user (System/getProperty "user.home")]
        (expand-home (str user File/separator "foo")) => (io/file user "foo")))

;; BB-TEST-PATCH: commented tests use midje functionality that isn't currently converted and/or
;;                the compression ns

;; BB-TEST-PATCH: made binding to adapt paths to bb folder structure
(def libtest-files-path "test-resources/lib_tests/me/raynes/testfiles")

;(fact (list-dir ".") => (has every? #(instance? File %)))
;
;;; Want to change these files to be tempfiles at some point.
;(when unix-root (against-background
; [(around :contents (let [f (io/file "test/me/raynes/testfiles/bar")]
;                      (.setExecutable f false)
;                      (.setReadable f false)
;                      (.setWritable f false)
;                      ?form
;                      (.setExecutable f true)
;                      (.setReadable f true)
;                      (.setWritable f true)))]
; (fact
;   (executable? "test/me/raynes/testfiles/foo") => true
;   (executable? "test/me/raynes/testfiles/bar") => false)
;
; (fact
;   (readable? "test/me/raynes/testfiles/foo") => true
;   (readable? "test/me/raynes/testfiles/bar") => false)
;
; (fact
;   (writeable? "test/me/raynes/testfiles/foo") => true
;   (writeable? "test/me/raynes/testfiles/bar") => false)))

;; BB-TEST-PATCH: update these paths to use bb folder structure

(fact
  (file? (str libtest-files-path "/foo")) => true
  (file? ".") => false)

(fact
  (exists? (str libtest-files-path "/foo")) => true
  (exists? "ewjgnr4ig43j") => false)

(fact
  (let [f (io/file (str libtest-files-path "/baz"))]
    (.createNewFile f)
    (delete f)
    (exists? f) => false))

(fact
  (directory? ".") => true
  (directory? (str libtest-files-path "/foo")) => false)

(fact
  (file? ".") => false
  (file? (str libtest-files-path "/foo")) => true)

(fact
  (let [tmp (temp-file "fs-")]
    (exists? tmp) => true
    (file? tmp) => true
    (delete tmp)))

(fact
  (let [tmp (temp-dir "fs-")]
    (exists? tmp) => true
    (directory? tmp) => true
    (delete tmp)))

(fact
 (let [tmp (ephemeral-file "fs-")]
   (exists? tmp) => true
   (file? tmp) => true)) ;; is deleted on JVM exit

(fact
 (let [tmp (ephemeral-dir "fs-")]
   (exists? tmp) => true
   (directory? tmp) => true)) ;; is deleted on JVM exit

(fact
  (absolute "foo") => (io/file *cwd* "foo"))

(fact
  (normalized ".") => *cwd*)

(fact
  (base-name "foo/bar") => "bar"
  (base-name "foo/bar.txt" true) => "bar"
  (base-name "bar.txt" ".txt") => "bar"
  (base-name "foo/bar.txt" ".png") => "bar.txt")

(fact
  (let [tmp (temp-file "fs-")]
    (> (mod-time tmp) 0) => true
    (delete tmp)))

(fact
  (let [f (temp-file "fs-")]
    (spit f "abc")
    (size f) => 3
    (delete f)))

(fact
  (let [root (create-walk-dir)
        result (delete-dir root)]
    (exists? root) => false))

(fact
  (let [f (temp-file "fs-")]
    (delete f)
    (mkdir f)
    (directory? f) => true
    (delete-dir f)))

(fact
  (let [f (temp-file "fs-")
        sub (file f "a" "b")]
    (delete f)
    (mkdirs sub)
    (directory? sub) => true
    (delete-dir f)))

;(fact
;  (split (file "test/fs")) => (has-suffix ["test" "fs"]))

(when unix-root
  (fact
   (split (file "/tmp/foo/bar.txt")) => '("/" "tmp" "foo" "bar.txt")
   (split (file "/")) => '("/")
   (split "/") => '("/")
   (split "") => '("")))

(fact
  (let [f (temp-file "fs-")
        new-f (str f "-new")]
    (rename f new-f)
    (exists? f) => false
    (exists? new-f) => true
    (delete new-f)))

;(fact
;  (let [root (create-walk-dir)]
;    (walk vector root) => (contains [[root #{"b" "a"} #{"1"}]
;                                     [(file root "a") #{} #{"2"}]
;                                     [(file root "b") #{} #{"3"}]]
;                                    :in-any-order)
;    (delete-dir root)))

(fact
  (let [from (temp-file "fs-")
        to (temp-file "fs-")
        data "What's up Doc?"]
    (delete to)
    (spit from data)
    (copy from to)
    (slurp from) => (slurp to)
    (delete from)
    (delete to)))

(fact
  (let [f (temp-file "fs-")
        t (mod-time f)]
    (Thread/sleep 1000)
    (touch f)
    (> (mod-time f) t) => true
    (let [t2 3000]
      (touch f t2)
      (mod-time f) => t2)
    (delete f)))

(fact
  (let [f (temp-file "fs-")]
    (chmod "+x" f)
    (executable? f) => true
    (when-not (re-find #"Windows" (System/getProperty "os.name"))
      (chmod "-x" f)
      (executable? f) => false)
    (delete f)))

(fact
  (let [f (temp-file "fs-")]
    (chmod "777" f)
    (executable? f) => true
    (readable? f) => true
    (writeable? f) => true
    (chmod "000" f)
    (when-not (re-find #"Windows" (System/getProperty "os.name"))
      (chmod "-x" f)
      (executable? f) => false
      (readable? f) => false
      (writeable? f) => false)
    (delete f)))

;(fact
;  (let [from (create-walk-dir)
;        to (temp-dir "fs-")
;        path (copy-dir from to)
;        dest (file to (base-name from))]
;    path => dest
;    (walk vector to) => (contains [[to #{(base-name from)} #{}]
;                                   [dest #{"b" "a"} #{"1"}]
;                                   [(file dest "a") #{} #{"2"}]
;                                   [(file dest "b") #{} #{"3"}]]
;                                  :in-any-order)
;    (delete-dir from)
;    (delete-dir to)))
;
;(fact "copy-dir-into works as expected."
;  (let [from (create-walk-dir)
;        to (temp-dir "fs-")]
;    (copy-dir-into from to)
;    (walk vector to) => (contains [[(file to) #{"a" "b"} #{"1"}]
;                                   [(file to "a") #{} #{"2"}]
;                                   [(file to "b") #{} #{"3"}]]
;                                  :in-any-order)
;    (delete-dir from)
;    (delete-dir to)))

(when (System/getenv "HOME")
  (fact
   (let [env-home (io/file (System/getenv "HOME"))]
     (home) => env-home
     (home "") => env-home
     (home (System/getProperty "user.name")) => env-home)))

;(tabular
;  (fact (split-ext ?file) => ?ext)
;
;    ?file            ?ext
;    "fs.clj"        ["fs" ".clj"]
;    "fs."           ["fs" "."]
;    "fs.clj.bak"    ["fs.clj" ".bak"]
;    "/path/to/fs"   ["fs" nil]
;    ""              [(base-name (System/getProperty "user.dir")) nil]
;    "~user/.bashrc" [".bashrc" nil])
;
;(tabular
;  (fact (extension ?file) => ?ext)
;
;    ?file            ?ext
;    "fs.clj"        ".clj"
;    "fs."           "."
;    "fs.clj.bak"    ".bak"
;    "/path/to/fs"   nil
;    ""              nil
;    ".bashrc"       nil)
;
;(tabular
;  (fact (name ?file) => ?ext)
;
;    ?file            ?ext
;    "fs.clj"        "fs"
;    "fs."           "fs"
;    "fs.clj.bak"    "fs.clj"
;    "/path/to/fs"   "fs"
;    ""              (base-name (System/getProperty "user.dir"))
;    ".bashrc"       ".bashrc")

(fact "Can change cwd with with-cwd."
  (let [old *cwd*]
    (with-cwd "foo"
      *cwd* => (io/file old "foo"))))

(fact "Can change cwd mutably with with-mutable-cwd"
  (let [old *cwd*]
    (with-mutable-cwd
      (chdir "foo")
      *cwd* => (io/file old "foo"))))

;(with-cwd "test/me/raynes/testfiles"
;  (fact
;    (unzip "ggg.zip" "zggg")
;    (exists? "zggg/ggg") => true
;    (exists? "zggg/hhh/jjj") => true
;    (delete-dir "zggg"))
;
;  (fact (zip "fro.zip" ["bbb.txt" "bbb"])
;    (exists? "fro.zip") => true
;    (unzip "fro.zip" "fro")
;    (exists? "fro/bbb.txt") => true
;    (rename "fro.zip" "fro2.zip") => true
;    (delete "fro2.zip")
;    (delete-dir "fro"))
;
;  (fact "about zip round trip"
;    (zip "round.zip" ["some.txt" "some text"])
;    (unzip "round.zip" "round")
;    (slurp (file "round/some.txt")) => "some text")
;
;  (fact "zip-files"
;    (zip-files "foobar.zip" ["foo" "bar"])
;    (exists? "foobar.zip")
;    (unzip "foobar.zip" "foobar")
;    (exists? "foobar/foo") => true
;    (exists? "foobar/bar") => true
;    (delete "foobar.zip")
;    (delete-dir "foobar"))
;
;  (fact
;    (untar "ggg.tar" "zggg")
;    (exists? "zggg/ggg") => true
;    (exists? "zggg/hhh/jjj") => true
;    (delete-dir "zggg"))
;
;  (fact
;    (gunzip "ggg.gz" "ggg")
;    (exists? "ggg") => true
;    (delete "ggg"))
;
;  (fact
;    (bunzip2 "bbb.bz2" "bbb")
;    (exists? "bbb") => true
;    (delete "bbb"))
;
;  (fact
;    (unxz "xxx.xz" "xxx")
;    (exists? "xxx") => true
;    (delete "xxx"))
;
;  (fact "zip-slip vulnerability"
;    (unzip "zip-slip.zip" "zip-slip") => (throws Exception "Expanding entry would be created outside target dir")
;    (untar "zip-slip.tar" "zip-slip") => (throws Exception "Expanding entry would be created outside target dir")
;    (exists? "/tmp/evil.txt") => false
;    (delete-dir "zip-slip")))

;(let [win-root (when-not unix-root "c:")]
;  (fact
;    (parents (str win-root "/foo/bar/baz")) => (just [(file (str win-root "/foo"))
;                                        (file (str win-root "/foo/bar"))
;                                        (file (str win-root "/"))]
;                                       :in-any-order)
;    (parents (str win-root "/")) => nil))
;
;(fact
;  (child-of? "/foo/bar" "/foo/bar/baz") => truthy
;  (child-of? "/foo/bar/baz" "/foo/bar") => falsey)

(fact
  (path-ns "foo/bar/baz_quux.clj") => 'foo.bar.baz-quux)

;(fact
;  (str (ns-path 'foo.bar.baz-quux)) => (has-suffix (string/join File/separator ["foo" "bar" "baz_quux.clj"])))

(fact
  (let [win-root (when-not unix-root "c:")]
    (absolute? (str win-root "/foo/bar")) => true
    (absolute? (str win-root "/foo/")) => true
    (absolute? "foo/bar") => false
    (absolute? "foo/") => false))

(defmacro run-java-7-tests []
  (when (try (import '[java.nio.file Files Path LinkOption StandardCopyOption FileAlreadyExistsException]
                     '[java.nio.file.attribute FileAttribute])
             (catch Exception _ nil))
    '(do
       ;; BB-TEST-PATCH: change path to match bb folders
       (def test-files-path "test-resources/lib_tests/me/raynes/testfiles")

       (fact
        (let [files (find-files test-files-path #"ggg\.*")
              gggs (map #(file (str test-files-path "/ggg." %)) '(gz tar zip))]
          (every? (set gggs) files) => true))

       (fact
        (let [fs1 (find-files test-files-path #"ggg\.*")
              fs2 (find-files* test-files-path #(re-matches #"ggg\.*" (.getName %)))]
          (= fs1 fs2) => true))

       (fact
        (let [f (touch (io/file test-files-path ".hidden"))]
          (hidden? f)
          (delete f)))

       (fact
        (let [target (io/file test-files-path "ggg.tar")
              hard (link (io/file test-files-path "hard.link") target)]
          (file? hard) => true
          (delete hard)))

       (when fs-supports-symlinks?
         (fact
          (let [target (io/file test-files-path "ggg.tar")
                soft (sym-link (io/file test-files-path "soft.link") target)]
            (file? soft) => true
            (link? soft) => true
            (= (read-sym-link soft) target)
            (delete soft)))

         (fact
          (let [soft (sym-link (io/file test-files-path "soft.link") test-files-path)]
            (link? soft) => true
            (file? soft) => false
            (directory? soft) => true
            (directory? soft LinkOption/NOFOLLOW_LINKS) => false
            (delete soft)))

         (fact
          (let [root (create-walk-dir)
                soft-a (sym-link (io/file root "soft-a.link") (io/file root "a"))
                soft-b (sym-link (io/file root "soft-b.link") (io/file root "b"))]
            (delete-dir soft-a LinkOption/NOFOLLOW_LINKS)
            (exists? (io/file root "a" "2")) => true
            (delete-dir soft-b)
            (exists? (io/file root "b" "3")) => false
            (delete-dir root)
            (exists? root) => false)))

         (fact "`move` moves files"
                 (let [source (io/file test-files-path "foo")
                     target (io/file test-files-path "foo.moved")
                     existing-target (io/file test-files-path "bar")]
                 (move source target)
                 (exists? target) => true
                 (exists? source) => false
                 (move target source)
                 (exists? target) => false
                 (exists? source) => true
;                (move source existing-target) => (throws FileAlreadyExistsException)
                 (copy source target)
                 (move source target StandardCopyOption/REPLACE_EXISTING)
                 (exists? target) => true
                 (exists? source) => false
                 (move target source))))))

(run-java-7-tests)
