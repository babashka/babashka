#!/usr/bin/env bb

(import (java.net ServerSocket))
(require '[clojure.java.io :as io]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

(def debug? false)

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

;; the home page
(defn home []
  (str
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
       [:input {:type "submit" :value "Submit"}]]]])))

;; run the server
(with-open [server-socket (let [s (new ServerSocket 8080)]
                            (deliver accepting true)
                            s)
            client-socket (.accept server-socket)]
  (loop []
    (let [out (io/writer (.getOutputStream client-socket))
          is (.getInputStream client-socket)
          in (io/reader is)
          response (loop [headers []]
                     (let [line (.readLine in)]
                       (if (str/blank? line)
                         headers
                         (recur (conj headers line)))))
          data (let [sb (StringBuilder.)]
                 (loop []
                   (when (.ready in)
                     (.append sb (char (.read in)))
                     (recur)))
                 (-> (str sb)
                     (java.net.URLDecoder/decode)))
          _ (when debug? (println (str/join "\n" response)))
          _ (when-not (str/blank? data)
              (when debug? (println data))
              (let [note (str/replace data "note=" "")]
                (spit notes-file (str note "\n") :append true)))
          _ (when debug? (println))
          body (home)]
      (.write out (format "HTTP/1.1 %s OK\r\nContent-Length: %s\r\n\r\n%s"
                          200
                          (count body)
                          body))
      (.flush out))
    (recur)))
