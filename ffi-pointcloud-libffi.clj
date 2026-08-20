;; Point cloud with EVERY raylib call routed through libffi (ffi_call)
;; instead of the trampolines - the worst-case libffi benchmark. Same scene
;; as ffi-pointcloud.clj; prints measured fps every 60 frames.
;;
;; Usage: bb ffi-pointcloud-libffi.clj [n-points] [auto-quit-seconds]
;;
;; Bonus over the trampoline version: BeginMode3D receives Camera3D properly
;; BY VALUE via a nested libffi struct type - portable, unlike the
;; arm64-only pointer trick.

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "ffi")
(ffi/load-system-library "raylib")

(defcfn prep-cif "ffi_prep_cif" [:pointer :int :uint :pointer :pointer] :int)
(defcfn ffi-call "ffi_call" [:pointer :pointer :pointer :pointer] :void)
(defcfn c-dlsym "dlsym" [:pointer :string] :pointer)

(def RTLD-DEFAULT (if (= "Mac OS X" (System/getProperty "os.name")) -2 0))
(def FFI-DEFAULT-ABI (if (= "aarch64" (System/getProperty "os.arch")) 1 2))

(defn sym-addr [name]
  (let [p (c-dlsym RTLD-DEFAULT name)]
    (when (ffi/null?* p) (throw (ex-info (str "symbol not found: " name) {})))
    p))

(defn ffi-type [size align code elements]
  (let [t (ffi/alloc 24)]
    (ffi/write t :size_t 0 size)
    (ffi/write t :uint16 8 align)
    (ffi/write t :uint16 10 code)
    (ffi/write t :pointer 16 elements)
    t))

(def t-void (ffi-type 1 1 0 0))
(def t-float (ffi-type 4 4 2 0))
(def t-sint32 (ffi-type 4 4 10 0))
(def t-uint32 (ffi-type 4 4 9 0))
(def t-uint8 (ffi-type 1 1 5 0))
(def t-pointer (ffi-type 8 8 14 0))

(defn struct-type [element-types]
  (let [elems (ffi/alloc (* 8 (inc (count element-types))))]
    (doseq [[i t] (map-indexed vector element-types)]
      (ffi/write elems :pointer (* 8 i) t))
    (ffi/write elems :pointer (* 8 (count element-types)) 0)
    (ffi-type 0 0 13 elems)))

(def t-vector3 (struct-type [t-float t-float t-float]))
(def t-camera3d (struct-type [t-vector3 t-vector3 t-vector3 t-float t-sint32]))

(def ^:private write-kw
  {t-float :float t-sint32 :int t-uint32 :uint t-uint8 :uint8 t-pointer :pointer})

(defn libffi-fn
  "Binds sym via ffi_call with preallocated per-binding buffers. arg-types
  and ret-type are ffi_type pointers; struct args are written by the caller
  into the slot directly (pass :slot to get it)."
  [sym arg-types ret-type]
  (let [n (count arg-types)
        cif (ffi/alloc 128)
        atypes (ffi/alloc (max 8 (* 8 n)))
        slots (mapv (fn [_] (ffi/alloc 48)) (range n))
        avalues (ffi/alloc (max 8 (* 8 n)))
        rvalue (ffi/alloc 8)
        fnp (sym-addr sym)]
    (doseq [[i t] (map-indexed vector arg-types)]
      (ffi/write atypes :pointer (* 8 i) t))
    (doseq [[i s] (map-indexed vector slots)]
      (ffi/write avalues :pointer (* 8 i) s))
    (when-not (zero? (prep-cif cif FFI-DEFAULT-ABI n ret-type atypes))
      (throw (ex-info (str "ffi_prep_cif failed for " sym) {})))
    (let [wk (mapv write-kw arg-types)
          ;; struct args have no scalar writer: the caller fills the slot
          w (fn [i v] (when-let [k (wk i)] (ffi/write (slots i) k 0 v)))]
      {:slots slots
       :rvalue rvalue
       :call (case n
               0 (fn [] (ffi-call cif fnp rvalue avalues))
               1 (fn [a] (w 0 a) (ffi-call cif fnp rvalue avalues))
               2 (fn [a b] (w 0 a) (w 1 b) (ffi-call cif fnp rvalue avalues))
               3 (fn [a b c] (w 0 a) (w 1 b) (w 2 c) (ffi-call cif fnp rvalue avalues))
               4 (fn [a b c d] (w 0 a) (w 1 b) (w 2 c) (w 3 d)
                   (ffi-call cif fnp rvalue avalues))
               5 (fn [a b c d e] (w 0 a) (w 1 b) (w 2 c) (w 3 d) (w 4 e)
                   (ffi-call cif fnp rvalue avalues)))})))

(defn fret [b kw] (fn [& args] (apply (:call b) args) (ffi/read (:rvalue b) kw 0)))
(defn fvoid [b] (:call b))

(def init-window (fvoid (libffi-fn "InitWindow" [t-sint32 t-sint32 t-pointer] t-void)))
(def close-window (fvoid (libffi-fn "CloseWindow" [] t-void)))
(def window-should-close (fret (libffi-fn "WindowShouldClose" [] t-uint8) :uint8))
(def set-target-fps (fvoid (libffi-fn "SetTargetFPS" [t-sint32] t-void)))
(def begin-drawing (fvoid (libffi-fn "BeginDrawing" [] t-void)))
(def end-drawing (fvoid (libffi-fn "EndDrawing" [] t-void)))
(def clear-background (fvoid (libffi-fn "ClearBackground" [t-uint32] t-void)))
(def draw-text (fvoid (libffi-fn "DrawText" [t-pointer t-sint32 t-sint32 t-sint32 t-uint32] t-void)))
(def get-random-value (fret (libffi-fn "GetRandomValue" [t-sint32 t-sint32] t-sint32) :int))
(def get-frame-time (fret (libffi-fn "GetFrameTime" [] t-float) :float))
(def end-mode-3d (fvoid (libffi-fn "EndMode3D" [] t-void)))
(def rl-push-matrix (fvoid (libffi-fn "rlPushMatrix" [] t-void)))
(def rl-pop-matrix (fvoid (libffi-fn "rlPopMatrix" [] t-void)))
(def rl-rotatef (fvoid (libffi-fn "rlRotatef" [t-float t-float t-float t-float] t-void)))
(def rl-begin (fvoid (libffi-fn "rlBegin" [t-sint32] t-void)))
(def rl-end (fvoid (libffi-fn "rlEnd" [] t-void)))
(def rl-vertex-3f (fvoid (libffi-fn "rlVertex3f" [t-float t-float t-float] t-void)))
(def rl-color-4ub (fvoid (libffi-fn "rlColor4ub" [t-uint8 t-uint8 t-uint8 t-uint8] t-void)))

;; BeginMode3D(Camera3D) - true by-value struct arg via libffi
(def begin-mode-3d
  (let [b (libffi-fn "BeginMode3D" [t-camera3d] t-void)
        slot (first (:slots b))]
    ;; camera: pos-z 12, up-y 1, fovy 45, projection 0
    (doseq [[off v] [[8 12.0] [28 1.0] [36 45.0]]]
      (ffi/write slot :float off v))
    (ffi/write slot :int 40 0)
    (fn [] ((:call b) nil))))

(def RL-TRIANGLES 4)

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def BLACK (rgba 0 0 0 255))
(def RAYWHITE (rgba 245 245 245 255))

(def n-points (or (some-> (first *command-line-args*) parse-long) 1500))
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

(def title (ffi/string->ptr "babashka.ffi - point cloud (libffi)"))
(def label (ffi/string->ptr (str n-points " points, all calls via libffi")))

(init-window 800 450 title)
(set-target-fps 120)

(def points (make-points))

(loop [frame 0 t-frames 0.0]
  (when (and (zero? (window-should-close))
             (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (begin-drawing)
    (clear-background BLACK)
    (begin-mode-3d)
    (rl-push-matrix)
    (rl-rotatef (* frame 0.3) 0.0 1.0 0.0)
    (doseq [[x y z r g b] points]
      (cube! x y z 0.03 r g b))
    (rl-pop-matrix)
    (end-mode-3d)
    (draw-text label 10 10 20 RAYWHITE)
    (end-drawing)
    (let [t-frames (+ t-frames (get-frame-time))]
      (if (= 59 (mod frame 60))
        (do (println (format "fps: %.1f (%d points, all libffi)"
                             (/ 60.0 t-frames) n-points))
            (recur (inc frame) 0.0))
        (recur (inc frame) t-frames)))))

(close-window)
(println "libffi point cloud done")
