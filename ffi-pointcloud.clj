;; Point cloud over babashka.ffi, ported from b12n-raylib-jlt/point_cloud.clj:
;; N points, each a tiny rlgl cube (44 FFI calls per cube), slowly rotating
;; via the rlgl matrix stack. The FFI call-overhead stress test: 1500 points
;; is ~66k foreign calls per frame. Prints measured fps every 60 frames.
;;
;; Usage: bb ffi-pointcloud.clj [n-points] [auto-quit-seconds]
;;
;; Camera3D (44 bytes) is passed by pointer: on AArch64 a >16-byte composite
;; passes indirectly, so a [:pointer] binding works. Not portable to x86-64,
;; where it goes on the stack (the jolt original documents the same caveat).

(require '[babashka.ffi :as ffi :refer [defcfn]])

(def lib-candidates
  ["/opt/homebrew/lib/libraylib.dylib"
   "/usr/local/lib/libraylib.dylib"
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
(defcfn get-random-value "GetRandomValue" [:int :int] :int)
(defcfn get-frame-time "GetFrameTime" [] :float)
(defcfn begin-mode-3d "BeginMode3D" [:pointer] :void)
(defcfn end-mode-3d "EndMode3D" [] :void)
(defcfn rl-begin "rlBegin" [:int] :void)
(defcfn rl-end "rlEnd" [] :void)
(defcfn rl-vertex-3f "rlVertex3f" [:float :float :float] :void)
(defcfn rl-color-4ub "rlColor4ub" [:int :int :int :int] :void)
(defcfn rl-push-matrix "rlPushMatrix" [] :void)
(defcfn rl-pop-matrix "rlPopMatrix" [] :void)
(defcfn rl-rotatef "rlRotatef" [:float :float :float :float] :void)

(def RL-TRIANGLES 4)

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def BLACK (rgba 0 0 0 255))
(def RAYWHITE (rgba 245 245 245 255))

(def n-points
  (or (some-> (first *command-line-args*) parse-long) 1500))

(def deadline
  (when-let [secs (some-> (second *command-line-args*) parse-long)]
    (+ (System/currentTimeMillis) (* 1000 secs))))

(defn make-points []
  (vec (repeatedly n-points
                   (fn []
                     (let [x (/ (get-random-value -50 50) 10.0)
                           y (/ (get-random-value -50 50) 10.0)
                           z (/ (get-random-value -50 50) 10.0)]
                       [x y z
                        (int (+ 128 (* 25 x)))
                        (int (+ 128 (* 25 y)))
                        (int (+ 128 (* 25 z)))])))))

(defn shade ^long [^long ch f] (long (* f ch)))

(defn quad! [r g b f [[ax ay az] [bx by bz] [cx cy cz] [dx dy dz]]]
  (rl-color-4ub (shade r f) (shade g f) (shade b f) 255)
  (rl-vertex-3f ax ay az) (rl-vertex-3f bx by bz) (rl-vertex-3f cx cy cz)
  (rl-vertex-3f ax ay az) (rl-vertex-3f cx cy cz) (rl-vertex-3f dx dy dz))

(defn cube! [x y z half r g b]
  (let [x0 (- x half) x1 (+ x half)
        y0 (- y half) y1 (+ y half)
        z0 (- z half) z1 (+ z half)
        a000 [x0 y0 z0] a100 [x1 y0 z0] a010 [x0 y1 z0] a110 [x1 y1 z0]
        a001 [x0 y0 z1] a101 [x1 y0 z1] a011 [x0 y1 z1] a111 [x1 y1 z1]]
    (rl-begin RL-TRIANGLES)
    (quad! r g b 1.0  [a001 a101 a111 a011])
    (quad! r g b 0.5  [a100 a000 a010 a110])
    (quad! r g b 0.7  [a000 a001 a011 a010])
    (quad! r g b 0.85 [a101 a100 a110 a111])
    (quad! r g b 1.0  [a011 a111 a110 a010])
    (quad! r g b 0.4  [a000 a100 a101 a001])
    (rl-end)))

;; Camera3D: pos, target, up (Vector3 each), fovy float, projection int
(def camera
  (let [p (ffi/alloc 44)]
    (ffi/write p :float 8 12.0)   ; pos-z
    (ffi/write p :float 28 1.0)   ; up-y
    (ffi/write p :float 36 45.0)  ; fovy
    p))

(init-window 800 450 "babashka.ffi - point cloud")
(set-target-fps 120)

(def points (make-points))

(loop [frame 0 t-frames 0.0]
  (when (and (zero? (window-should-close))
             (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (begin-drawing)
    (clear-background BLACK)
    (begin-mode-3d camera)
    (rl-push-matrix)
    (rl-rotatef (* frame 0.3) 0.0 1.0 0.0)
    (doseq [[x y z r g b] points]
      (cube! x y z 0.03 r g b))
    (rl-pop-matrix)
    (end-mode-3d)
    (draw-text (str n-points " points, each a tiny rlgl cube") 10 10 20 RAYWHITE)
    (end-drawing)
    (let [t-frames (+ t-frames (get-frame-time))]
      (if (= 59 (mod frame 60))
        (do (println (format "fps: %.1f (%d points, ~%d ffi calls/frame)"
                             (/ 60.0 t-frames) n-points (* n-points 44)))
            (recur (inc frame) 0.0))
        (recur (inc frame) t-frames)))))

(close-window)
(ffi/free camera)
(println "point cloud done")
