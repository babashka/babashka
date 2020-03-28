#!/usr/bin/env bb

(import (java.net ServerSocket))
(require '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def debug? true)

(def notes-file (io/file (System/getProperty "user.home") ".notes" "notes.txt"))
(io/make-parents notes-file)

;; ensure notes file exists
(spit notes-file "" :append true)

;; we wait for the server to accept connections and then open a browser
(def accepting (promise))
(future
  @accepting
  (sh "open" "http://localhost:8080"))

;; hiccup-like
(defn html [v]
  (cond (vector? v)
        (let [tag (first v)
              attrs (second v)
              attrs (when (map? attrs) attrs)
              elts (if attrs (nnext v) (next v))
              tag-name (name tag)]
          (format "<%s%s>%s</%s>\n" tag-name (html attrs) (html elts) tag-name))
        (map? v)
        (str/join ""
                  (map (fn [[k v]]
                         (format " %s=\"%s\"" (name k) v)) v))
        (seq? v)
        (str/join " " (map html v))
        :else (str v)))

(defn write-response [out session-id status headers content]
  (let [cookie-header (str "Set-Cookie: notes-id=" session-id)
        headers (str/join "\r\n" (conj headers cookie-header))
        response (str "HTTP/1.1 " status "\r\n"
                      (str headers "\r\n")
                      "Content-Length: " (if content (count content)
                                             0)
                      "\r\n\r\n"
                      (when content
                        (str content)))]
    (when debug? (prn response))
    (binding [*out* out]
      (print response)
      (flush))))

;; the home page
(defn home [out session-id]
  (let [body (str
              "<!DOCTYPE html>\n"
              (html
               [:html
                [:head
                 [:title "Notes"]]
                [:body
                 [:h1 "Notes"]
                 [:pre (slurp notes-file)]
                 [:form {:action "/" :method "post"}
                  [:input {:type "text" :name "note"}]
                  [:input {:type "submit" :value "Submit"}]]]]))]
    (write-response out session-id "200 OK" nil body)))

(defn basic-auth-response [out session-id]
  (write-response out session-id
                  "401 Unauthorized"
                  ["WWW-Authenticate: Basic realm=\"notes\""]
                  nil))

(defn get-session-id [headers]
  (if-let [cookie-header (first (filter #(str/starts-with? % "Cookie: ") headers))]
    (let [parts (str/split cookie-header #"; ")]
      (if-let [notes-id (first (filter #(str/starts-with? % "notes-id") parts))]
        (str/replace notes-id "notes-id=" "")
        (java.util.UUID/randomUUID)))
    (java.util.UUID/randomUUID)))

(defn basic-auth-header [headers]
  (some #(str/starts-with? % "Basic-Auth: ") headers))

(def authenticated-sessions
  (atom #{}))

(defn authenticate [session-id headers]
  (or (contains? @authenticated-sessions session-id)
      (when (some #(= % "Authorization: Basic YWRtaW46YWRtaW4=") headers)
        (swap! authenticated-sessions conj session-id)
        true)))

;; run the server
(with-open [server-socket (let [s (new ServerSocket 8080)]
                            (deliver accepting true)
                            s)
            client-socket (.accept server-socket)]
  (loop []
    (let [out (io/writer (.getOutputStream client-socket))
          is (.getInputStream client-socket)
          in (io/reader is)
          [req & headers :as response]
          (loop [headers []]
            (let [line (.readLine in)]
              (if (str/blank? line)
                headers
                (recur (conj headers line)))))
          session-id (get-session-id headers)
          form-data (let [sb (StringBuilder.)]
                      (loop []
                        (when (.ready in)
                          (.append sb (char (.read in)))
                          (recur)))
                      (-> (str sb)
                          (java.net.URLDecoder/decode)))
          _ (when debug? (println (str/join "\n" response)))
          _ (when-not (str/blank? form-data)
              (when debug? (println form-data))
              (let [note (str/replace form-data "note=" "")]
                (spit notes-file (str note "\n") :append true)))
          _ (when debug? (println))]
      (cond (not (authenticate session-id headers))
            (basic-auth-response out session-id)
            :else (home out session-id)))
    (recur)))
