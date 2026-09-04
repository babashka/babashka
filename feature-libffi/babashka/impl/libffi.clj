(ns babashka.impl.libffi
  "Calls the libffi that BABASHKA_LIBFFI links into the binary.
  Builds that link libffi require this namespace."
  {:no-doc true}
  (:import [babashka.impl Libffi]))

(set! *warn-on-reflection* true)

(defn version
  "Returns the version of the linked libffi."
  []
  (Libffi/version))

(defn prep-cif
  "Calls ffi_prep_cif and returns its status code."
  [cif abi nargs rtype atypes]
  (Libffi/prepCif (long cif) (int abi) (int nargs) (long rtype) (long atypes)))

(defn prep-cif-var
  "Calls ffi_prep_cif_var, for a variadic call, and returns its status code."
  [cif abi nfixed ntotal rtype atypes]
  (Libffi/prepCifVar (long cif) (int abi) (int nfixed) (int ntotal) (long rtype) (long atypes)))

(defn call
  "Calls ffi_call and writes the return value to rvalue."
  [cif fnp rvalue avalues]
  (Libffi/call (long cif) (long fnp) (long rvalue) (long avalues)))
