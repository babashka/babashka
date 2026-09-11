(ns nrepl.util.classloader
  "Stand-in for nREPL's nrepl.util.classloader: a native image has no
  DynamicClassLoader, so sessions run without a context classloader.")

(defn find-topmost-dcl [_classloader] nil)

(defn dynamic-classloader
  ([] nil)
  ([_classloader] nil))
