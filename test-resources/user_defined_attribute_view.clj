(ns user-defined-attribute-view
  (:import [java.nio.file Files Paths]
           [java.nio.file.attribute UserDefinedFileAttributeView FileAttribute]
           [java.nio.charset StandardCharsets])
  (:require [babashka.fs :as fs]))

(defn set-user-defined-attribute
  "Sets a user-defined attribute on the specified file."
  [file-path attribute-name attribute-value]
  (let [path file-path
        view (.getFileAttributeView (Files/getFileStore path)
                                    UserDefinedFileAttributeView)]
    (when view
      (let [bytes (.getBytes attribute-value StandardCharsets/UTF_8)]
        (.write view attribute-name bytes 0 (count bytes)))
      (println "Attribute set successfully."))))

(defn get-user-defined-attribute
  "Gets a user-defined attribute from the specified file."
  [file-path attribute-name]
  (let [path file-path
        view (.getFileAttributeView (Files/getFileStore path)
                                    UserDefinedFileAttributeView)]
    (when view
      (let [size (.size view attribute-name)
            buffer (byte-array size)]
        (.read view attribute-name buffer 0 size)
        (String. buffer StandardCharsets/UTF_8)))))

;; Example usage
(defn -main []
  (let [tmp-dir (fs/temp-dir)
        file-path (fs/path tmp-dir "example.txt")
        _ (fs/delete-on-exit file-path)
        attribute-name "user.comment"
        attribute-value "This is a test comment."]
    ;; Create an example file
    (Files/createFile file-path (into-array FileAttribute []))
    ;; Set and get the user-defined attribute
    (set-user-defined-attribute file-path attribute-name attribute-value)
    (println "Retrieved attribute:"
             (get-user-defined-attribute file-path attribute-name))))

(-main)
