(ns babashka.impl.reify
  {:no-doc true})

(def reify-opts
  {'java.nio.file.FileVisitor
   (fn [{:keys [:methods]}]
     {:obj (reify java.nio.file.FileVisitor
             (preVisitDirectory [this p attrs]
               ((get methods 'preVisitDirectory) this p attrs))
             (postVisitDirectory [this p attrs]
               ((get methods 'postVisitDirectory) this p attrs))
             (visitFile [this p attrs]
               ((get methods 'visitFile) this p attrs)))})
   'java.io.FileFilter
   (fn [{:keys [:methods]}]
     {:obj (reify java.io.FileFilter
             (accept [this f]
               ((get methods 'accept) this f)))})
   'java.io.FilenameFilter
   (fn [{:keys [:methods]}]
     {:obj (reify java.io.FilenameFilter
             (accept [this f s]
               ((get methods 'accept) this f s)))})})
