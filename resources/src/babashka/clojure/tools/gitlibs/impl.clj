;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.gitlibs.impl
  "Implementation, use at your own risk"
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.gitlibs.config :as config])
  (:import
    [java.lang ProcessBuilder$Redirect]
    [java.io File FilenameFilter InputStream IOException StringWriter]))

(set! *warn-on-reflection* true)

;; io util

(defn printerrln [& msgs]
  (binding [*out* *err*]
    (apply println msgs)))

(defn- capture
  "Reads from input-stream until EOF and returns a String (or nil if 0 length)."
  [^InputStream input-stream]
  (let [writer (StringWriter.)]
    (jio/copy input-stream writer)
    (let [s (str/trim (.toString writer))]
      (when-not (zero? (.length s))
        s))))

(defmacro background
  [& body]
  `(let [result# (promise)]
     (doto (Thread. (fn [] (deliver result# (do ~@body))))
       (.setDaemon true)
       (.start))
     result#))

(defn- run-git
  [& args]
  (let [{:gitlibs/keys [command debug terminal]} @config/CONFIG
        command-args (cons command args)]
    (when debug
      (apply printerrln command-args))
    (let [proc-builder (ProcessBuilder. ^java.util.List command-args)
          _ (when debug (.redirectError proc-builder ProcessBuilder$Redirect/INHERIT))
          _ (when-not terminal (.put (.environment proc-builder) "GIT_TERMINAL_PROMPT" "0"))
          proc (.start proc-builder)
          out (background (capture (.getInputStream proc)))
          err (background (capture (.getErrorStream proc))) ;; if debug is true, stderr will be redirected instead
          exit (.waitFor proc)]
      {:args command-args, :exit exit, :out @out, :err @err})))

;; dirs

(defn lib-dir
  ^File [lib]
  (jio/file (:gitlibs/dir @config/CONFIG) "libs" (namespace lib) (name lib)))

(def ^:private git-url-regex
  #"([a-z0-9+.-]+):\/\/(?:(?:(?:[^@]+?)@)?([^/]+?)(?::[0-9]*)?)?(/[^:]+)")

(def ^:private git-scp-regex
  #"(?:(?:[^@]+?)@)?(.+?):([^:]+)")

(defn- clean-url
  "Convert url into a safe relative path (this is not a reversible transformation)
  based on scheme, host, and path (drop user and port).

  Examples:
    ssh://git@gitlab.com:3333/org/repo.git     => ssh/gitlab.com/org/repo
    git@github.com:dotted.org/dotted.repo.git  => ssh/github.com/dotted.org/dotted.repo
    file://../foo                              => file/REL/_DOTDOT_/foo
    file:///Users/user/foo.git                 => file/Users/user/foo
    ../foo                                     => file/REL/_DOTDOT_/foo
    ~user/foo.git                              => file/REL/_TILDE_user/foo

  * https://git-scm.com/docs/git-clone#_git_urls
  * https://git-scm.com/book/en/v2/Git-on-the-Server-The-Protocols
  "
  [url]
  (let [[scheme host path] (cond
                             (str/starts-with? url "file://") ["file" nil (-> url (subs 7) (str/replace #"^([^/])" "REL/$1"))]
                             (str/includes? url "://") (let [[_ s h p] (re-matches git-url-regex url)] [s h p])
                             (str/includes? url ":") (let [[_ h p] (re-matches git-scp-regex url)] ["ssh" h p])
                             :local-repo ["file" nil (str/replace url #"^([^/])" "REL/$1")])
        clean-path (-> path
                     (str/replace #"\.git/?$" "") ;; remove trailing .git or .git/
                     (str/replace #"~" "_TILDE_")) ;; replace ~ with _TILDE_
        dir-parts (->> (concat [scheme host] (str/split clean-path #"/")) ;; split on /
                    (remove str/blank?) ;; remove any missing path segments
                    (map #(-> % ({"." "_DOT_", ".." "_DOTDOT_"} %))))] ;; replace . or .. segments
    (str/join "/" dir-parts)))

(defn git-dir
  ^File [url]
  (jio/file (:gitlibs/dir @config/CONFIG) "_repos" (clean-url url)))

(defn git-try-fetch
  "Try to fetch and return the error code (0=success)"
  [^File git-dir]
  (let [git-path (.getCanonicalPath git-dir)
        ;; NOTE: --prune-tags would be desirable here but was added in git 2.17.0
        {:keys [exit]} (run-git "--git-dir" git-path
                                "fetch" "--quiet" "--all" "--tags" "--prune")]
    exit))

(defn git-fetch
  [^File git-dir]
  (let [git-path (.getCanonicalPath git-dir)
        ;; NOTE: --prune-tags would be desirable here but was added in git 2.17.0
        {:keys [exit err] :as ret} (run-git "--git-dir" git-path
                                     "fetch" "--quiet" "--all" "--tags" "--prune")]
    (when-not (zero? exit)
      (throw (ex-info (format "Unable to fetch %s%n%s" git-path err) ret)))))

;; TODO: restrict clone to an optional refspec?
(defn git-clone-bare
  [url ^File git-dir]
  (printerrln "Cloning:" url)
  (let [git-path (.getCanonicalPath git-dir)
        {:keys [exit err] :as ret} (run-git "clone" "--quiet" "--mirror" url git-path)]
    (when-not (zero? exit)
      (throw (ex-info (format "Unable to clone %s to %s:%n%s" url git-path err) ret)))
    git-dir))

(defn ensure-git-dir
  "Ensure the bare git dir for the specified url, return the path to the git dir."
  [url]
  (let [git-dir-file (git-dir url)
        config-file (jio/file git-dir-file "config")]
    (when-not (.exists config-file)
      (git-clone-bare url git-dir-file))
    (.getCanonicalPath git-dir-file)))

(defn git-checkout
  [git-dir-path ^File lib-dir ^String rev]
  (let [rev-file (jio/file lib-dir rev)]
    (when-not (.exists rev-file)
      (let [{:keys [exit err] :as ret}
            (run-git "--git-dir" git-dir-path
              "worktree" "add" "--force" "--detach"
              (.getCanonicalPath rev-file) rev)]
        (when-not (zero? exit)
          (throw (ex-info (format "Unable to checkout %s%n%s" rev err) ret)))))))

(defn git-rev-parse
  [git-dir rev]
  (let [{:keys [exit out]} (run-git "--git-dir" git-dir "rev-parse" (str rev "^{commit}"))]
    (when (zero? exit)
      (str/trimr out))))

(defn git-type
  [git-dir rev]
  (let [{:keys [exit out]} (run-git "--git-dir" git-dir "cat-file" "-t" rev)]
    (when (zero? exit)
      (keyword (str/trimr out)))))

;; git merge-base --is-ancestor <maybe-ancestor-commit> <descendant-commit>
(defn- ancestor?
  [git-dir x y]
  (let [{:keys [exit err] :as ret} (run-git "--git-dir" git-dir "merge-base" "--is-ancestor" x y)]
    (condp = exit
      0 true
      1 false
      (throw (ex-info (format "Unable to compare commits %s%n%s" git-dir err) ret)))))

(defn commit-comparator
  [git-dir x y]
  (cond
    (= x y) 0
    (ancestor? git-dir x y) 1
    (ancestor? git-dir y x) -1
    :else (throw (ex-info "" {}))))

(defn match-exact
  "In dir, match file in dir with exact, nil if doesn't exist"
  [^File dir exact]
  (when (.exists (jio/file dir exact))
    exact))

(defn match-prefix
  "In dir, match file in dir with prefix, nil if not found, exception if more than one."
  [^File dir prefix]
  (when (.exists dir)
    (if (.exists (jio/file dir prefix))
      prefix
      (let [matches (.listFiles dir
                      (reify FilenameFilter
                        (accept [_this _dir name]
                          (str/starts-with? name prefix))))]
        (case (alength matches)
          0 nil
          1 (.getName ^File (aget matches 0))
          (throw (IOException. (str "Prefix not unique: " prefix))))))))

(defn tags
  "Fetch, then return all tags in the git dir."
  [git-dir]
  (git-try-fetch (jio/file git-dir))
  (let [{:keys [exit out err] :as ret} (run-git "--git-dir" git-dir "tag" "--sort=v:refname")]
    (when-not (zero? exit)
      (throw (ex-info (format "Unable to get tags %s%n%s" git-dir err) ret)))
    (when out
      (remove str/blank? (str/split-lines out)))))
