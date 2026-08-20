;; raylib bouncing-ball demo over babashka.ffi, ported from
;; b12n-raylib-jlt/bounce.clj (itself from raylib's shapes_bouncing_ball.c).
;; SPACE pauses. Optional CLI arg: seconds to auto-quit (for testing).
;;
;; Color is raylib's one by-value struct here: 4 bytes {u8 r,g,b,a}, passed
;; packed as :uint (r | g<<8 | b<<16 | a<<24).

(require '[babashka.ffi :as ffi :refer [defcfn]])

(def lib-candidates
  ["/opt/homebrew/lib/libraylib.dylib"     ; brew, Apple silicon
   "/usr/local/lib/libraylib.dylib"        ; brew, Intel mac
   "libraylib.dylib"
   "/usr/lib/libraylib.so"
   "/usr/local/lib/libraylib.so"
   "libraylib.so"])

(when-not (some #(try (ffi/load-library %) (catch Exception _ nil)) lib-candidates)
  (println "libraylib not found - install raylib first (brew install raylib)")
  (System/exit 1))

(defcfn init-window "InitWindow" [:int :int :string] :void)
(defcfn close-window "CloseWindow" [] :void)
(defcfn window-should-close "WindowShouldClose" [] :uint8)
(defcfn set-target-fps "SetTargetFPS" [:int] :void)
(defcfn begin-drawing "BeginDrawing" [] :void)
(defcfn end-drawing "EndDrawing" [] :void)
(defcfn clear-background "ClearBackground" [:uint] :void)
(defcfn draw-text "DrawText" [:string :int :int :int :uint] :void)
(defcfn draw-fps "DrawFPS" [:int :int] :void)
(defcfn draw-circle "DrawCircle" [:int :int :float :uint] :void)
(defcfn is-key-pressed "IsKeyPressed" [:int] :uint8)

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def RAYWHITE (rgba 245 245 245 255))
(def LIGHTGRAY (rgba 200 200 200 255))
(def MAROON (rgba 190 33 55 255))
(def KEY-SPACE 32)

(def W 800)
(def H 450)
(def R 20.0)

(def deadline
  (when-let [secs (first *command-line-args*)]
    (+ (System/currentTimeMillis) (* 1000 (parse-long secs)))))

(init-window W H "babashka.ffi - bouncing ball")
(set-target-fps 150)

(loop [x 400.0 y 225.0 vx 5.0 vy 4.0 paused? false]
  (when (and (zero? (window-should-close))
             (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (let [paused? (if (pos? (is-key-pressed KEY-SPACE)) (not paused?) paused?)
          [x y vx vy] (if paused?
                        [x y vx vy]
                        (let [x (+ x vx)
                              y (+ y vy)
                              vx (if (or (>= (+ x R) W) (<= (- x R) 0)) (- vx) vx)
                              vy (if (or (>= (+ y R) H) (<= (- y R) 0)) (- vy) vy)]
                          [x y vx vy]))]
      (begin-drawing)
      (clear-background RAYWHITE)
      (draw-circle (int x) (int y) R MAROON)
      (draw-text "PRESS SPACE to PAUSE BALL MOVEMENT" 10 (- H 25) 20 LIGHTGRAY)
      (draw-fps 10 10)
      (end-drawing)
      (recur x y vx vy paused?))))

(close-window)
(println "raylib demo done")
