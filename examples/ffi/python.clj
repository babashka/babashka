;; Embedded CPython over babashka.ffi, libpython-clj style: evaluate Python
;; expressions from bb, and register a bb function as a Python callable
;; (a hand-built PyMethodDef struct + an upcall).

(require '[babashka.ffi :as ffi :refer [defcfn]])

(def lib-candidates
  ["/opt/homebrew/Frameworks/Python.framework/Versions/3.12/lib/libpython3.12.dylib"
   "/opt/homebrew/Frameworks/Python.framework/Versions/3.13/lib/libpython3.13.dylib"
   "/usr/local/Frameworks/Python.framework/Versions/3.12/lib/libpython3.12.dylib"
   "libpython3.12.so.1.0"
   "libpython3.12.so"
   "libpython3.so"])

(when-not (some #(try (ffi/load-library %) (catch Exception _ nil)) lib-candidates)
  (println "libpython not found - install python (brew install python@3.12)")
  (System/exit 1))

(defcfn py-initialize "Py_Initialize" [] :void)
(defcfn py-finalize "Py_FinalizeEx" [] :int)
(defcfn py-run-simple "PyRun_SimpleString" [:string] :int)
(defcfn py-run-string "PyRun_String" [:string :int :pointer :pointer] :pointer)
(defcfn py-import-add "PyImport_AddModule" [:string] :pointer)
(defcfn py-module-dict "PyModule_GetDict" [:pointer] :pointer)
(defcfn py-obj-str "PyObject_Str" [:pointer] :pointer)
(defcfn py-unicode-utf8 "PyUnicode_AsUTF8" [:pointer] :string)
(defcfn py-dec-ref "Py_DecRef" [:pointer] :void)
(defcfn py-err-print "PyErr_Print" [] :void)
(defcfn py-long-as-long "PyLong_AsLong" [:pointer] :long)
(defcfn py-long-from-long "PyLong_FromLong" [:long] :pointer)
(defcfn py-tuple-get-item "PyTuple_GetItem" [:pointer :size_t] :pointer)
(defcfn py-dict-set-item "PyDict_SetItemString" [:pointer :string :pointer] :int)
(defcfn py-cfunction-new "PyCFunction_NewEx" [:pointer :pointer :pointer] :pointer)

(def Py-eval-input 258)

(py-initialize)

(def globals (py-module-dict (py-import-add "__main__")))

(defn py-eval
  "Evaluates a Python expression, returns its str() as a Clojure string."
  [code]
  (let [obj (py-run-string code Py-eval-input globals globals)]
    (when (ffi/null? obj)
      (py-err-print)
      (throw (ex-info "python error" {:code code})))
    (let [s (py-obj-str obj)
          res (py-unicode-utf8 s)]
      (py-dec-ref s)
      (py-dec-ref obj)
      res)))

(println "python:" (py-eval "__import__('sys').version.split()[0]"))
(println "1+2 =" (py-eval "1+2"))
(py-run-simple "import math")
(println "math.pi =" (py-eval "math.pi"))
(println "sum(range(101)) =" (py-eval "sum(range(101))"))
(println "join =" (py-eval "'-'.join(['babashka','calls','python'])"))

;; -- a bb function callable FROM Python ---------------------------------------
;; PyCFunction: PyObject* f(PyObject* self, PyObject* args)
(def bb-fib
  (ffi/callback
   (fn [_self args]
     (let [n (py-long-as-long (py-tuple-get-item args 0))
           fib (fn fib [n] (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))]
       (py-long-from-long (fib n))))
   [:pointer :pointer] :pointer))

;; PyMethodDef {const char* ml_name; PyCFunction ml_meth; int ml_flags;
;;              const char* ml_doc} - 32 bytes on 64-bit
(def METH-VARARGS 1)
(def mdef (ffi/alloc 32))
(ffi/write mdef :pointer 0 (ffi/string->ptr "bb_fib"))
(ffi/write mdef :pointer 8 bb-fib)
(ffi/write mdef :int 16 METH-VARARGS)
(ffi/write mdef :pointer 24 ffi/null)

(def py-fn (py-cfunction-new mdef ffi/null ffi/null))
(py-dict-set-item globals "bb_fib" py-fn)

(println "python calls bb:" (py-eval "[bb_fib(i) for i in range(10)]"))
(println "python maps bb fn:" (py-eval "max(bb_fib(i) for i in range(20))"))

(py-finalize)
(println "PYTHON OK")
