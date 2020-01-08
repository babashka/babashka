#!/usr/bin/env bb

(import (java.net ServerSocket))
(require '[clojure.string :as string]
         '[clojure.java.io :as io])

(with-open [server-socket (new ServerSocket 8080)
            client-socket (.accept server-socket)]
  (loop []
    (let [out (io/writer (.getOutputStream client-socket))
          in (io/reader (.getInputStream client-socket))
          [req-line & headers] (loop [headers []]
                                 (let [line (.readLine in)]
                                   (if (string/blank? line)
                                     headers
                                     (recur (conj headers line)))))
          [_ _ path _] (re-find #"([^\s]+)\s([^\s]+)\s([^\s]+)" req-line)
          f (io/file (format "./%s" path))
          status (if (.exists f)
                   200
                   404)
          html (fn html-fn [tag & body]
                 (let [attrs? (map? (first body))
                       attrs-str (str (when attrs?
                                        (format " %s" (string/join " " (for [[k v] (first body)]
                                                                         (format "%s=%s" (name k) (name v)))))))]
                   (format "<%s%s>%s</%s>"
                           (name tag)
                           attrs-str
                           (string/join (if attrs? (rest body) body))
                           (name tag))))
          body (cond
                 (not (.exists f)) ""
                 (.isFile f) (slurp f)
                 (.isDirectory f) (format "<!DOCTYPE html>\n%s"
                                          (html :html
                                                (html :head
                                                      (html :title path))
                                                (html :body
                                                      (html :h1 path)
                                                      (html :tt
                                                            (apply html :pre
                                                                   (for [i (.list f)]
                                                                     (html :div (html :a {:href (format "\"%s\"" i)} i)))))))))]
      (prn path)
      (.write out (format "HTTP/1.1 %s OK\r\nContent-Length: %s\r\n\r\n%s"
                          status
                          (count body)
                          body))
      (.flush out))
    (recur)))
