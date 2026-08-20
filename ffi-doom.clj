;; A Doom-like raycaster in babashka, drawn with raylib through babashka.ffi.
;;
;;   bb ffi-doom.clj [columns] [seconds]
;;
;; WASD moves, mouse turns, left click shoots, ESC quits.
;;
;; Walls are textured vertical strips: one rlgl quad per screen column, with
;; the texture atlas generated pixel by pixel into foreign memory and handed
;; to rlLoadTexture. Every raylib call here takes scalars only - Texture2D
;; and Rectangle cross the ABI by value, so the rlgl entry points are used
;; instead of LoadTexture/DrawTexturePro.

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "raylib")

(defcfn init-window "InitWindow" [:int :int :string] :void)
(defcfn close-window "CloseWindow" [] :void)
(defcfn window-should-close "WindowShouldClose" [] :uint8)
(defcfn set-target-fps "SetTargetFPS" [:int] :void)
(defcfn begin-drawing "BeginDrawing" [] :void)
(defcfn end-drawing "EndDrawing" [] :void)
(defcfn clear-background "ClearBackground" [:uint] :void)
(defcfn draw-rectangle "DrawRectangle" [:int :int :int :int :uint] :void)
(defcfn draw-circle "DrawCircle" [:int :int :float :uint] :void)
(defcfn draw-line "DrawLine" [:int :int :int :int :uint] :void)
(defcfn draw-text "DrawText" [:string :int :int :int :uint] :void)
(defcfn screenshot "TakeScreenshot" [:string] :void)
(defcfn get-frame-time "GetFrameTime" [] :float)
(defcfn key-down? "IsKeyDown" [:int] :uint8)
(defcfn key-pressed? "IsKeyPressed" [:int] :uint8)
(defcfn mouse-pressed? "IsMouseButtonPressed" [:int] :uint8)
(defcfn get-mouse-x "GetMouseX" [] :int)
(defcfn set-mouse-position "SetMousePosition" [:int :int] :void)
(defcfn hide-cursor "HideCursor" [] :void)
(defcfn rl-load-texture "rlLoadTexture" [:pointer :int :int :int :int] :uint)
(defcfn rl-set-texture "rlSetTexture" [:uint] :void)
(defcfn rl-begin "rlBegin" [:int] :void)
(defcfn rl-end "rlEnd" [] :void)
(defcfn rl-vertex-2f "rlVertex2f" [:float :float] :void)
(defcfn rl-tex-coord-2f "rlTexCoord2f" [:float :float] :void)
(defcfn rl-color-4ub "rlColor4ub" [:int :int :int :int] :void)
(defcfn rl-disable-backface-culling "rlDisableBackfaceCulling" [] :void)

(def RL-QUADS 7)
(def RGBA8 7)                           ; RL_PIXELFORMAT_UNCOMPRESSED_R8G8B8A8
(def KEY-W 87) (def KEY-A 65) (def KEY-S 83) (def KEY-D 68)
(def KEY-LEFT 263) (def KEY-RIGHT 262)

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def W 900)
(def H 560)
(def COLS (or (some-> (first *command-line-args*) parse-long) 450))
(def COLW (/ (double W) COLS))
(def deadline
  (when-let [secs (some-> (second *command-line-args*) parse-long)]
    (+ (System/currentTimeMillis) (* 1000 secs))))

;; --- the level ---------------------------------------------------------------

(def level
  ["1111111111111111"
   "1..............1"
   "1..2222...44...1"
   "1..2......4....1"
   "1..2..33..4....1"
   "1.....3........1"
   "1.....3...222..1"
   "1..............1"
   "1...44444......1"
   "1.......4...33.1"
   "1.......4......1"
   "1..333..4..22..1"
   "1....3.....2...1"
   "1....3.........1"
   "1..............1"
   "1111111111111111"])

(def MAP-W (count (first level)))
(def MAP-H (count level))
(def grid (int-array (* MAP-W MAP-H)))
(dotimes [y MAP-H]
  (dotimes [x MAP-W]
    (let [c (.charAt ^String (nth level y) x)]
      (aset-int grid (+ (* y MAP-W) x)
                (if (= \. c) 0 (- (int c) (int \0)))))))

(defn wall-at ^long [x y]
  (if (or (< x 0) (< y 0) (>= x MAP-W) (>= y MAP-H))
    1
    (aget grid (+ (* y MAP-W) x))))

;; --- texture atlas: four wall styles plus one sprite, 64x64 each -------------

(def TILE 64)
(def TILES 5)

(defn tile-pixel
  "[r g b a] of pixel x,y in tile t."
  [t x y]
  (case (long t)
    ;; brick
    0 (let [row (quot y 16)
            off (if (even? row) 0 32)
            bx (mod (+ x off) 32)
            mortar? (or (< (mod y 16) 2) (< bx 2))
            n (mod (* (+ x (* y 7)) 31) 24)]
        (if mortar? [126 122 118 255] [(+ 120 n) (+ 46 (quot n 2)) (+ 38 (quot n 3)) 255]))
    ;; stone
    1 (let [n (mod (* (+ (* x 13) (* y 29)) 17) 40)
            crack? (< (mod (+ (* x 3) y) 61) 2)
            v (+ 96 n)]
        (if crack? [58 58 62 255] [v v (+ v 6) 255]))
    ;; metal panel with rivets
    2 (let [px (mod x 32) py (mod y 32)
            edge? (or (< px 3) (< py 3) (> px 28) (> py 28))
            rivet? (and (< 5 px 11) (< 5 py 11))
            n (mod (* (+ x y) 11) 14)]
        (cond rivet? [176 182 196 255]
              edge? [70 78 96 255]
              :else [(+ 92 n) (+ 100 n) (+ 124 n) 255]))
    ;; tech green
    3 (let [line? (or (< (mod y 12) 2) (and (< (mod x 24) 2) (> (mod y 24) 10)))
            n (mod (* (+ (* x 7) y) 13) 18)]
        (if line? [92 232 140 255] [(+ 20 n) (+ 54 n) (+ 40 n) 255]))
    ;; sprite: a squat imp, transparent around it
    4 (let [cx (- x 32) cy (- y 40)
            body (+ (* cx cx) (* (/ cy 1.3) (/ cy 1.3)))
            head (+ (* cx cx) (* (- y 18) (- y 18)))
            eye-l (+ (* (- x 24) (- x 24)) (* (- y 16) (- y 16)))
            eye-r (+ (* (- x 40) (- x 40)) (* (- y 16) (- y 16)))]
        (cond
          (or (< eye-l 9) (< eye-r 9)) [255 226 92 255]
          (< head 150) [150 74 52 255]
          (< body 420) [112 52 40 255]
          :else [0 0 0 0]))))

(def atlas-buf (ffi/alloc (* TILE TILE TILES 4)))
(dotimes [t TILES]
  (dotimes [y TILE]
    (dotimes [x TILE]
      (let [[r g b a] (tile-pixel t x y)
            off (* 4 (+ (* (+ (* t TILE) y) TILE) x))]
        (ffi/write atlas-buf :uint32 off (rgba r g b a))))))

;; --- player ------------------------------------------------------------------

(def pos-x (atom 2.5)) (def pos-y (atom 7.5))
(def dir-x (atom 1.0)) (def dir-y (atom 0.0))
(def plane-x (atom 0.0)) (def plane-y (atom 0.66))
(def health (atom 100))
(def kills (atom 0))
(def shots (atom 0))
(def flash (atom 0.0))

(def zbuf (double-array COLS))

;; --- enemies -----------------------------------------------------------------

(defn spawn-imps []
  (atom (mapv (fn [[x y]] {:x x :y y :alive true})
              [[8.5 2.5] [12.5 6.5] [5.5 12.5] [11.5 11.5] [13.5 3.5] [4.5 8.5]])))
(def imps (spawn-imps))

;; --- raycasting --------------------------------------------------------------

(defn cast-column
  "DDA for screen column i. Returns [dist tile side wall-x]."
  [i px py dx dy plx ply]
  (let [camera (- (/ (* 2.0 i) COLS) 1.0)
        rdx (+ dx (* plx camera))
        rdy (+ dy (* ply camera))
        ddx (if (zero? rdx) 1e30 (Math/abs (/ 1.0 rdx)))
        ddy (if (zero? rdy) 1e30 (Math/abs (/ 1.0 rdy)))
        stepx (if (neg? rdx) -1 1)
        stepy (if (neg? rdy) -1 1)]
    (loop [mx (int px)
           my (int py)
           sdx (if (neg? rdx) (* (- px (int px)) ddx) (* (- (+ (int px) 1.0) px) ddx))
           sdy (if (neg? rdy) (* (- py (int py)) ddy) (* (- (+ (int py) 1.0) py) ddy))
           side 0
           n 0]
      (let [tile (wall-at mx my)]
        (if (or (pos? tile) (> n 64))
          (let [dist (if (zero? side) (- sdx ddx) (- sdy ddy))
                dist (max 0.0001 dist)
                wx (if (zero? side) (+ py (* dist rdy)) (+ px (* dist rdx)))
                wx (- wx (Math/floor wx))]
            [dist (max 1 tile) side wx])
          (if (< sdx sdy)
            (recur (+ mx stepx) my (+ sdx ddx) sdy 0 (inc n))
            (recur mx (+ my stepy) sdx (+ sdy ddy) 1 (inc n))))))))

(defn shade
  "Distance and side shading, as a 0-255 factor."
  ^long [dist side]
  (let [f (/ 1.0 (+ 1.0 (* 0.11 dist dist)))
        f (if (zero? side) f (* f 0.72))]
    (max 30 (min 255 (long (* 255 (+ 0.12 (* 0.95 f))))))))

;; the atlas is uploaded after InitWindow, which is where the GL context starts
(declare atlas-id)

(defn draw-walls! []
  (let [px @pos-x py @pos-y dx @dir-x dy @dir-y plx @plane-x ply @plane-y
        half (/ H 2.0)]
    (rl-set-texture atlas-id)
    (loop [i 0]
      (when (< i COLS)
        ;; rlgl cannot flush inside an open rlBegin/rlEnd, so batch in chunks
        (rl-begin RL-QUADS)
        (let [chunk-end (min COLS (+ i 128))]
          (loop [i i]
            (when (< i chunk-end)
              (let [[dist tile side wx] (cast-column i px py dx dy plx ply)
                    line (/ H dist)
                    y0 (- half (/ line 2.0))
                    y1 (+ half (/ line 2.0))
                    x0 (* i COLW)
                    x1 (+ x0 COLW)
                    s (shade dist side)
                    ;; atlas row for this wall type, u from the hit fraction
                    tile-i (min (dec TILES) (dec tile))
                    v0 (/ (double tile-i) TILES)
                    v1 (/ (+ tile-i 1.0) TILES)
                    u (if (zero? side) wx (- 1.0 wx))]
                (aset-double zbuf i dist)
                (rl-color-4ub s s s 255)
                (rl-tex-coord-2f u v0) (rl-vertex-2f x0 y0)
                (rl-tex-coord-2f u v1) (rl-vertex-2f x0 y1)
                (rl-tex-coord-2f u v1) (rl-vertex-2f x1 y1)
                (rl-tex-coord-2f u v0) (rl-vertex-2f x1 y0))
              (recur (inc i)))))
        (rl-end)
        (recur (+ i 128))))
    (rl-set-texture 0)))

(defn draw-imps! []
  (let [px @pos-x py @pos-y dx @dir-x dy @dir-y plx @plane-x ply @plane-y
        inv-det (/ 1.0 (- (* plx dy) (* dx ply)))
        half (/ H 2.0)
        visible (->> @imps
                     (filter :alive)
                     (map (fn [{:keys [x y]}]
                            (let [sx (- x px) sy (- y py)
                                  tx (* inv-det (- (* dy sx) (* dx sy)))
                                  ty (* inv-det (+ (* (- ply) sx) (* plx sy)))]
                              {:tx tx :ty ty})))
                     (filter #(> (:ty %) 0.25))
                     (sort-by :ty >))]
    (when (seq visible)
      (rl-set-texture atlas-id)
      (doseq [{:keys [tx ty]} visible]
        (let [screen-x (* (/ COLS 2.0) (+ 1.0 (/ tx ty)))
              size (/ COLS ty)
              w2 (/ size 2.0)
              h-px (/ H ty)
              y0 (- half (/ h-px 2.0))
              y1 (+ half (/ h-px 2.0))
              s (shade ty 0)
              strips 12
              c0 (max 0 (long (- screen-x w2)))
              c1 (min COLS (long (+ screen-x w2)))]
          (when (< c0 c1)
            (rl-begin RL-QUADS)
            (dotimes [k strips]
              (let [a (+ (- screen-x w2) (* size (/ (double k) strips)))
                    b (+ (- screen-x w2) (* size (/ (+ k 1.0) strips)))
                    mid (long (/ (+ a b) 2.0))]
                ;; depth test this strip against the wall column behind it
                (when (and (>= mid 0) (< mid COLS) (< ty (aget zbuf mid)))
                  (let [u0 (/ (double k) strips)
                        u1 (/ (+ k 1.0) strips)
                        v0 (/ 4.0 TILES)
                        v1 1.0]
                    (rl-color-4ub s s s 255)
                    (rl-tex-coord-2f u0 v0) (rl-vertex-2f (* a COLW) y0)
                    (rl-tex-coord-2f u0 v1) (rl-vertex-2f (* a COLW) y1)
                    (rl-tex-coord-2f u1 v1) (rl-vertex-2f (* b COLW) y1)
                    (rl-tex-coord-2f u1 v0) (rl-vertex-2f (* b COLW) y0)))))
            (rl-end))))
      (rl-set-texture 0))))

;; --- input and game logic ----------------------------------------------------

(def last-mx (atom (quot W 2)))

(defn rotate! [a]
  (let [c (Math/cos a) s (Math/sin a)
        dx @dir-x dy @dir-y plx @plane-x ply @plane-y]
    (reset! dir-x (- (* dx c) (* dy s)))
    (reset! dir-y (+ (* dx s) (* dy c)))
    (reset! plane-x (- (* plx c) (* ply s)))
    (reset! plane-y (+ (* plx s) (* ply c)))))

(defn move! [fwd strafe dt]
  (let [sp (* 3.4 dt)
        nx (+ @pos-x (* sp (+ (* fwd @dir-x) (* strafe @plane-x))))
        ny (+ @pos-y (* sp (+ (* fwd @dir-y) (* strafe @plane-y))))]
    ;; slide along walls: test each axis on its own
    (when (zero? (wall-at (int nx) (int @pos-y))) (reset! pos-x nx))
    (when (zero? (wall-at (int @pos-x) (int ny))) (reset! pos-y ny))))

(defn shoot! []
  (swap! shots inc)
  (reset! flash 0.06)
  (let [px @pos-x py @pos-y dx @dir-x dy @dir-y
        hit (->> @imps
                 (keep-indexed (fn [i imp]
                                 (when (:alive imp)
                                   (let [ex (- (:x imp) px) ey (- (:y imp) py)
                                         dist (Math/sqrt (+ (* ex ex) (* ey ey)))
                                         dot (/ (+ (* ex dx) (* ey dy)) (max 0.001 dist))]
                                     (when (> dot 0.985) [i dist])))))
                 (sort-by second)
                 first)]
    (when hit
      (swap! imps assoc-in [(first hit) :alive] false)
      (swap! kills inc))))

(defn advance-imps! [dt]
  (let [px @pos-x py @pos-y]
    (swap! imps
           (fn [is]
             (mapv (fn [imp]
                     (if-not (:alive imp)
                       imp
                       (let [ex (- px (:x imp)) ey (- py (:y imp))
                             d (Math/sqrt (+ (* ex ex) (* ey ey)))]
                         (if (< d 0.9)
                           (do (swap! health #(max 0 (- % 1))) imp)
                           (let [sp (* 0.9 dt)
                                 nx (+ (:x imp) (* sp (/ ex d)))
                                 ny (+ (:y imp) (* sp (/ ey d)))]
                             (cond-> imp
                               (zero? (wall-at (int nx) (int (:y imp)))) (assoc :x nx)
                               (zero? (wall-at (int (:x imp)) (int ny))) (assoc :y ny)))))))
                   is)))))

;; --- hud ---------------------------------------------------------------------

(defn draw-minimap! []
  (let [s 7 ox 12 oy 12]
    (draw-rectangle (- ox 4) (- oy 4) (+ (* MAP-W s) 8) (+ (* MAP-H s) 8) (rgba 12 12 16 210))
    (dotimes [y MAP-H]
      (dotimes [x MAP-W]
        (let [t (wall-at x y)]
          (when (pos? t)
            (draw-rectangle (+ ox (* x s)) (+ oy (* y s)) (dec s) (dec s)
                            (case t
                              1 (rgba 150 70 55 255) 2 (rgba 120 120 130 255)
                              3 (rgba 90 110 150 255) (rgba 70 180 110 255)))))))
    (doseq [imp @imps :when (:alive imp)]
      (draw-circle (+ ox (long (* (:x imp) s))) (+ oy (long (* (:y imp) s))) 2.0
                   (rgba 230 80 60 255)))
    (draw-circle (+ ox (long (* @pos-x s))) (+ oy (long (* @pos-y s))) 2.5 (rgba 245 235 120 255))
    (draw-line (+ ox (long (* @pos-x s))) (+ oy (long (* @pos-y s)))
               (+ ox (long (* (+ @pos-x (* 2 @dir-x)) s)))
               (+ oy (long (* (+ @pos-y (* 2 @dir-y)) s)))
               (rgba 245 235 120 255))))

(defn draw-hud! [fps]
  (let [alive (count (filter :alive @imps))]
    (draw-rectangle 0 (- H 42) W 42 (rgba 16 14 18 235))
    (draw-text (str "HEALTH " @health) 16 (- H 32) 22
               (if (< @health 40) (rgba 235 70 60 255) (rgba 220 220 210 255)))
    (draw-text (str "KILLS " @kills) 190 (- H 32) 22 (rgba 220 220 210 255))
    (draw-text (str "IMPS " alive) 330 (- H 32) 22 (rgba 220 180 120 255))
    (draw-text (str "SHOTS " @shots) 460 (- H 32) 22 (rgba 150 150 160 255))
    (draw-text (format "%d fps  %d cols" (long fps) COLS) 620 (- H 32) 22 (rgba 120 130 140 255))
    ;; crosshair
    (let [cx (quot W 2) cy (quot H 2)]
      (draw-line (- cx 9) cy (- cx 3) cy (rgba 240 240 240 200))
      (draw-line (+ cx 3) cy (+ cx 9) cy (rgba 240 240 240 200))
      (draw-line cx (- cy 9) cx (- cy 3) (rgba 240 240 240 200))
      (draw-line cx (+ cy 3) cx (+ cy 9) (rgba 240 240 240 200)))
    (when (zero? @health)
      (draw-text "YOU DIED" (- (quot W 2) 120) (- (quot H 2) 40) 54 (rgba 220 40 40 255)))))

;; --- main --------------------------------------------------------------------

(init-window W H "babashka.ffi - doom-like")
(set-target-fps 60)
(rl-disable-backface-culling)
(hide-cursor)
(def atlas-id (rl-load-texture atlas-buf TILE (* TILE TILES) RGBA8 1))
(set-mouse-position (quot W 2) (quot H 2))

(def frame-count (atom 0))
(def fps-acc (atom 0.0))
(def fps-n (atom 0))
(def fps-shown (atom 0.0))

(loop []
  (when (and (zero? (window-should-close))
             (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (let [dt (min 0.05 (get-frame-time))]
      ;; look
      (let [mx (get-mouse-x)
            dxm (- mx (quot W 2))]
        (when-not (zero? dxm) (rotate! (* dxm 0.0022)))
        (set-mouse-position (quot W 2) (quot H 2)))
      (when (pos? (key-down? KEY-LEFT)) (rotate! (* -1.8 dt)))
      (when (pos? (key-down? KEY-RIGHT)) (rotate! (* 1.8 dt)))
      ;; move
      (let [fwd (+ (if (pos? (key-down? KEY-W)) 1 0) (if (pos? (key-down? KEY-S)) -1 0))
            str8 (+ (if (pos? (key-down? KEY-D)) 1 0) (if (pos? (key-down? KEY-A)) -1 0))]
        (when (or (not= 0 fwd) (not= 0 str8)) (move! fwd str8 dt)))
      (when (pos? (mouse-pressed? 0)) (shoot!))
      (when (pos? @health) (advance-imps! dt))
      (swap! flash #(max 0.0 (- % dt)))

      (begin-drawing)
      ;; ceiling and floor
      (clear-background (rgba 28 26 32 255))
      (draw-rectangle 0 (quot H 2) W (quot H 2) (rgba 48 42 38 255))
      (draw-walls!)
      (draw-imps!)
      (when (pos? @flash)
        (draw-rectangle 0 0 W H (rgba 255 220 140 40)))
      (draw-minimap!)
      (draw-hud! @fps-shown)
      (end-drawing)

      (swap! fps-acc + dt)
      (swap! fps-n inc)
      (when (> @fps-acc 0.4)
        (reset! fps-shown (/ @fps-n @fps-acc))
        (println (format "fps %.0f | cols %d | health %d | kills %d"
                         @fps-shown COLS @health @kills))
        (reset! fps-acc 0.0) (reset! fps-n 0))
      (swap! frame-count inc)
      (when (and (= (or (some-> (System/getenv "SHOT") parse-long) 30) @frame-count) (System/getenv "SHOT"))
        (screenshot "doom.png"))
      (recur))))

(close-window)
(ffi/free atlas-buf)
(println "doom done")
