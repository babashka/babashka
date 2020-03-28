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

(defn write-response [out status headers content]
  (let [headers (not-empty (str/join "\r\n" headers))
        response (str "HTTP/1.1 " status "\r\n"
                      (when headers (str headers "\r\n"))
                      "Content-Length: " (if content (count content)
                                             0)
                      (when content
                        (str "\r\n\r\n" content)))]
    (when debug? (prn response))
    (binding [*out* out]
      (print response)
      (flush))))

;; the home page
(defn home [out]
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
    (write-response out "200 OK" nil body)))

(defn basic-auth [out]
  (write-response out "401 Unauthorized"
                  ["WWW-Authenticate: Basic realm \"notes\""]
                  nil))

#_(defn authorized? [headers]
  true)

(def sessions
  (atom {}))

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
      (basic-auth out)
      #_(home out) #_(cond false #_(not (authorized? headers))
            (basic-auth out)
            :else (home out)))
    (recur)))
