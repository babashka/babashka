(ns nrepl.util.classloader
  "Stand-in for nREPL's nrepl.util.classloader.
  Returns the context classloader in place of a DynamicClassLoader.")

(defn find-topmost-dcl [_classloader] nil)

(defn dynamic-classloader
  ([] (.getContextClassLoader (Thread/currentThread)))
  ([classloader] classloader))
