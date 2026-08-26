(ns babashka.impl.libffi
  "Calls into the libffi that BABASHKA_LIBFFI linked into the binary. On the
  classpath only for such a build, so that babashka.ffi finds it, and only
  then, by requiring this namespace."
  {:no-doc true}
  (:import [babashka.impl Libffi]))

(set! *warn-on-reflection* true)

(defn version
  "ffi_get_version: the version of the linked libffi, such as \"3.5.1\"."
  []
  (Libffi/version))

(defn prep-cif
  "ffi_prep_cif. Returns its status code."
  [cif abi nargs rtype atypes]
  (Libffi/prepCif (long cif) (int abi) (int nargs) (long rtype) (long atypes)))

(defn call
  "ffi_call. Writes the return value to rvalue."
  [cif fnp rvalue avalues]
  (Libffi/call (long cif) (long fnp) (long rvalue) (long avalues)))
