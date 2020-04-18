#!/usr/bin/env bb

(import (java.net ServerSocket))
(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def debug? true)
(def user "admin")
(def password "admin")
(def base64 (-> (.getEncoder java.util.Base64)
                (.encodeToString (.getBytes (str user ":" password)))))

(def notes-file (io/file (System/getProperty "user.home") ".notes" "notes.txt"))
(def file-lock (Object.))

(defn write-note! [note]
  (locking file-lock
    (io/make-parents notes-file)
    (spit notes-file (str note "\n") :append true)))

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
    (when debug? (println response))
    (binding [*out* out]
      (print response)
      (flush))))

;; the home page
(defn home-response [out session-id]
  (let [body (str
              "<!DOCTYPE html>\n"
              (html
               [:html
                [:head
                 [:title "Notes"]]
                [:body
                 [:h1 "Notes"]
                 [:pre (when (.exists notes-file)
                         (slurp notes-file))]
                 [:form {:action "/" :method "post"}
                  [:input {:type "text" :name "note"}]
                  [:input {:type "submit" :value "Submit"}]]]]))]
    (write-response out session-id "200 OK" nil body)))

(defn basic-auth-response [out session-id]
  (write-response out session-id
                  "401 Unauthorized"
                  ["WWW-Authenticate: Basic realm=\"notes\""]
                  nil))

(def known-sessions
  (atom #{}))

(defn new-session! []
  (let [uuid (str (java.util.UUID/randomUUID))]
    (swap! known-sessions conj uuid)
    uuid))

(defn get-session-id [headers]
  (if-let [cookie-header (first (filter #(str/starts-with? % "Cookie: ") headers))]
    (let [parts (str/split cookie-header #"; ")]
      (if-let [notes-id (first (filter #(str/starts-with? % "notes-id") parts))]
        (str/replace notes-id "notes-id=" "")
        (new-session!)))
    (new-session!)))

(defn basic-auth-header [headers]
  (some #(str/starts-with? % "Basic-Auth: ") headers))

(def authenticated-sessions
  (atom #{}))

(defn authenticate! [session-id headers]
  (or (contains? @authenticated-sessions session-id)
      (when (some #(= % (str "Authorization: Basic " base64)) headers)
        (swap! authenticated-sessions conj session-id)
        true)))

;; run the server
(with-open [server-socket (let [s (new ServerSocket 8080)]
                            (println "Server started on port 8080.")
                            s)]
  (loop []
    (let [client-socket (.accept server-socket)]
      (future
        (with-open [conn client-socket]
          (try
            (let [out (io/writer (.getOutputStream conn))
                  is (.getInputStream conn)
                  in (io/reader is)
                  [_req & headers :as response]
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
                        (write-note! note)))
                  _ (when debug? (println))]
              (cond
                ;; if we didn't see this session before, we want the user to re-authenticate
                (not (contains? @known-sessions session-id))
                (let [uuid (new-session!)]
                  (basic-auth-response out uuid))
                (not (authenticate! session-id headers))
                (basic-auth-response out session-id)
                :else (home-response out session-id)))
            (catch Throwable t
              (binding [*err* *out*]
                (println t)))))))
    (recur)))
