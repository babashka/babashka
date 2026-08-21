# babashka.ffi examples

Run each with `bb <file>`. See doc/ffi.md for the API.

- `sqlite.clj` - queries an in-memory sqlite database. Needs the sqlite3
  shared library, present by default on macOS and most Linux systems.
- `helitorus.clj` - a helix around a torus, drawn with raylib
  (`brew install raylib` or the raylib package of your distro).
- `doom.clj` - a raycaster with textures and sprites, drawn with raylib.
- `python.clj` - embeds CPython: evaluates Python expressions and registers
  a bb function as a Python callable. Needs libpython3.
- `libffi.clj` - binds libffi through babashka.ffi itself and calls a
  function that returns a struct by value, which the bundled shapes cannot.
