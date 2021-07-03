; let's build up a little data structure to play with

(def pet-store-sexp
  [:pet-store
   [:family
    [:owners
     [:name "Terry Smith"]
     [:name "Sam Smith"]
     [:phone "555-1212"]]
    [:animals
     [:animal {:type "dog"} "Sparky"]]]
   [:family
    [:owners
     [:name "Pat Jones"]
     [:phone "555-2121"]]
    [:animals
     [:animal {:type "hamster"} "Oliver"]
     [:animal {:type "cat"} "Kat"]]]])

; we can build XML from this

(def xml-str (xml/indent-str (xml/sexp-as-element pet-store-sexp)))

(println "Our XML as a string is:")
(println xml-str)

(comment xml-str is
         "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
          <pet-store>
           <family>
            <owners>
             <name>Terry Smith</name>...")

; and then we can parse that XML back into a data structure

(def xml-tree (xml/parse-str xml-str))

#_"xml-tree is a nested associative structure:
    {:tag :pet-store,
     :attrs {},
     :content
       ({:tag :family,
         :attrs {},
         :content ...})}"


; with a couple of quick helper functions...

(defn get-by-tag 
  "takes a seq of XML elements (or a 'root-ish' element) and a tag, filters by tag name, and gets the content of each"
  [elems tag-name]
  ; if we get (presumably) a root element, wrap it in a vector so we can still
  ; filter by its tag
  (if (xml/element? elems)
    (recur [elems] tag-name)
    (->> (filter #(= (:tag %) tag-name) elems)
         (mapcat :content))))

(defn get-in-by-tag
 "takes a seq of XML elements and a vector of tags, and drills into each 
  element by the tags, sort of like a mash-up of core/get-in and an XPath
  lookup" 
  [elems tag-vec]
  (reduce get-by-tag elems tag-vec))

; we can do things like...

(println "all the owner names:" (get-in-by-tag 
                                 xml-tree 
                                 [:pet-store :family :owners :name]))

(println "all the animal names:" (get-in-by-tag
                                  xml-tree
                                  [:pet-store :family :animals :animal]))

(println "all the phone numbers:" (get-in-by-tag
                                   xml-tree
                                   [:pet-store :family :owners :phone]))
