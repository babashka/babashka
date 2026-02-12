(ns babashka.impl.deftype
  {:no-doc true}
  (:import [babashka.impl SciMap]))

(set! *warn-on-reflection* true)

;; Interfaces that APersistentMap (+ IObj) already implements.
;; These are "free" on SciMap — declaring them changes nothing,
;; omitting them doesn't hide them. We strip these before matching
;; so that libraries are not penalized for explicitly listing them.
(def ^:private map-inherent-interfaces
  (into #{} (filter #(.isInterface ^Class %))
        (conj (supers clojure.lang.APersistentMap)
              clojure.lang.IMeta
              clojure.lang.IObj
              clojure.lang.IKVReduce
              clojure.lang.IMapIterable
              clojure.lang.Reversible)))

(defn ->scimap
  "Constructor function for SciMap. Receives a map with :methods, :fields, :protocols.
   Mapped in the SCI ctx so it can be called from generated deftype code."
  [{:keys [methods fields protocols]}]
  (SciMap. methods fields nil protocols nil))

(defn deftype-fn
  "Returns a map with :constructor-fn (symbol) or :error (string),
   or nil to fall through to the standard SciType path."
  [{:keys [interfaces]}]
  (if (interfaces clojure.lang.IPersistentMap)
    (let [novel (remove map-inherent-interfaces interfaces)]
      (if (empty? novel)
        {:constructor-fn 'babashka.impl.deftype/->scimap}
        {:error (str "Babashka supports deftype with map interfaces, but "
                     (pr-str (set (map #(.getName ^Class %) novel)))
                     " is not supported.")}))
    (when (some map-inherent-interfaces interfaces)
      {:error (str "Babashka's deftype supports full map types (add IPersistentMap to the interface list), "
                   "or use reify for individual interfaces like ILookup.")})))
