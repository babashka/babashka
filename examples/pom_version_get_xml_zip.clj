(require '[babashka.deps :as deps])

(deps/add-deps '{:deps {org.clojure/data.zip {:mvn/version "RELEASE"}}})

(require '[clojure.data.xml :as xml]
         '[clojure.data.zip.xml :as xmlz]
         '[clojure.zip :as zip])

(def xml "<pom><version>1.0.0</version></pom>")

(-> xml
    xml/parse-str
    zip/xml-zip
    (xmlz/xml1-> :pom :version zip/down zip/node))
;; => 1.0.0
