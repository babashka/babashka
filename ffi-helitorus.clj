;; Helitorus in babashka via raylib: a helix with n windings around a torus,
;; swept into a tube. Ported from the Scittle demo
;; (scittle/resources/public/helitorus.html), which draws to a 2D canvas;
;; here the same computed vertices go to rlgl immediate mode, which takes
;; scalars only, so every call fits babashka.ffi.
;;
;;   bb ffi-helitorus.clj [resolution] [seconds]
;;
;; drag to turn, wheel to zoom, LEFT/RIGHT windings, UP/DOWN resolution.

(require '[babashka.ffi :as ffi :refer [defcfn]])

(ffi/load-system-library "raylib")

(defcfn init-window "InitWindow" [:int :int :string] :void)
(defcfn close-window "CloseWindow" [] :void)
(defcfn window-should-close "WindowShouldClose" [] :uint8)
(defcfn set-target-fps "SetTargetFPS" [:int] :void)
(defcfn begin-drawing "BeginDrawing" [] :void)
(defcfn end-drawing "EndDrawing" [] :void)
(defcfn clear-background "ClearBackground" [:uint] :void)
(defcfn draw-text "DrawText" [:string :int :int :int :uint] :void)
(defcfn get-frame-time "GetFrameTime" [] :float)
(defcfn screenshot "TakeScreenshot" [:string] :void)
(defcfn get-mouse-x "GetMouseX" [] :int)
(defcfn get-mouse-y "GetMouseY" [] :int)
(defcfn mouse-down? "IsMouseButtonDown" [:int] :uint8)
(defcfn get-wheel "GetMouseWheelMove" [] :float)
(defcfn key-down? "IsKeyDown" [:int] :uint8)
(defcfn key-pressed? "IsKeyPressed" [:int] :uint8)
(defcfn rl-begin "rlBegin" [:int] :void)
(defcfn rl-end "rlEnd" [] :void)
(defcfn rl-vertex-2f "rlVertex2f" [:float :float] :void)
(defcfn rl-color-4ub "rlColor4ub" [:int :int :int :int] :void)
;; the painter's ordering plus the 2D cross-product test below do the culling,
;; as in the canvas original; OpenGL's own culling would drop the same faces
(defcfn rl-disable-backface-culling "rlDisableBackfaceCulling" [] :void)

(def RL-TRIANGLES 4)
(def KEY-RIGHT 262) (def KEY-LEFT 263) (def KEY-DOWN 264) (def KEY-UP 265)

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def W 1000)
(def H 560)
(def CX (/ W 2.0))
(def CY (/ H 2.0))

(def NV 12)          ; points around the tube cross-section
(def MAX-NU 900)     ; max points along the spine
(def R 1.0)          ; major radius
(def r2 0.30)        ; minor radius of the torus
(def dist 4.6)
(def focal 3.2)
(def TAU 6.283185307179586)

(def NU (atom (or (some-> (first *command-line-args*) parse-long) 260)))
(def twists (atom 14))
(def r3 (atom 0.11))
(def rot-x (atom 0.55))
(def rot-y (atom 0.0))
(def vel-x (atom 0.0))
(def vel-y (atom 0.45))
(def zoom (atom 250.0))
(def dragging (atom false))

(def deadline
  (when-let [secs (some-> (second *command-line-args*) parse-long)]
    (+ (System/currentTimeMillis) (* 1000 secs))))

;; aset-double/aset-int rather than aset: plain aset on a primitive array
;; goes through java.lang.reflect.Array in bb and costs ~6.7us per write
;; (vs ~37ns typed), which dominated this loop
;; the phi grid is fixed, only its phase moves
(def cos-phi (double-array NV))
(def sin-phi (double-array NV))
(dotimes [j NV]
  (let [a (/ (* TAU j) NV)]
    (aset-double cos-phi j (Math/cos a))
    (aset-double sin-phi j (Math/sin a))))

(def sx (double-array (* MAX-NU NV)))
(def sy (double-array (* MAX-NU NV)))
(def shade (int-array (* MAX-NU NV)))
(def ring-z (double-array MAX-NU))
;; kept between frames: stays almost sorted, so the insertion sort is cheap
(def order (int-array MAX-NU))
(def order-nu (atom 0))

(defn reset-order! [nu]
  (dotimes [i nu] (aset-int order i i))
  (reset! order-nu nu))

;; hsl(337..349, 100..78%, 17..72%) as packed rgba, the Scittle palette
(defn hsl->rgba [h s l]
  (let [c (* (- 1.0 (Math/abs (- (* 2.0 l) 1.0))) s)
        h' (/ h 60.0)
        x (* c (- 1.0 (Math/abs (- (mod h' 2.0) 1.0))))
        m (- l (/ c 2.0))
        [r g b] (cond
                  (< h' 1) [c x 0.0] (< h' 2) [x c 0.0] (< h' 3) [0.0 c x]
                  (< h' 4) [0.0 x c] (< h' 5) [x 0.0 c] :else [c 0.0 x])]
    [(int (* 255 (+ r m))) (int (* 255 (+ g m))) (int (* 255 (+ b m)))]))

(def palette-r (int-array 64))
(def palette-g (int-array 64))
(def palette-b (int-array 64))
(dotimes [i 64]
  (let [t (/ i 63.0)
        [r g b] (hsl->rgba (+ 337.0 (* 12.0 t)) (/ (- 100.0 (* 22.0 t)) 100.0)
                           (/ (+ 17.0 (* 55.0 t)) 100.0))]
    (aset-int palette-r i r) (aset-int palette-g i g) (aset-int palette-b i b)))

(defn compute! [t]
  (let [nu (long @NU)
        n (long @twists)
        rr3 (double @r3)
        r (+ r2 rr3)
        zm (double @zoom)
        ay (double @rot-y)
        ax (double @rot-x)
        cay (Math/cos ay) say (Math/sin ay)
        cax (Math/cos ax) sax (Math/sin ax)
        po (* t 1.1)
        cpo (Math/cos po)
        spo (Math/sin po)
        dtheta (/ TAU nu)]
    (when (not= nu @order-nu) (reset-order! nu))
    (loop [i 0]
      (when (< i nu)
        (let [th (* i dtheta)
              nth (* n th)
              cn (Math/cos nth) sn (Math/sin nth)
              ct (Math/cos th) st (Math/sin th)
              xr (+ R (* r cn))
              ;; spine
              px (* xr ct) py (* xr st) pz (* r sn)
              ;; spine tangent: the theta derivative, written out
              tx (- (* (- xr) st) (* n r ct sn))
              ty (- (* xr ct) (* n r st sn))
              tz (* n r cn)
              ;; tangent of the flat circle under the spine
              bx (- st) by ct
              ;; tube normal: t cross b, normalised
              nx (- (* ty 0.0) (* tz by))
              ny (- (* tz bx) (* tx 0.0))
              nz (- (* tx by) (* ty bx))
              nl (Math/sqrt (+ (* nx nx) (* ny ny) (* nz nz)))
              nx (/ nx nl) ny (/ ny nl) nz (/ nz nl)
              ;; third axis: n cross t, normalised
              ux (- (* ny tz) (* nz ty))
              uy (- (* nz tx) (* nx tz))
              uz (- (* nx ty) (* ny tx))
              ul (Math/sqrt (+ (* ux ux) (* uy uy) (* uz uz)))
              ux (/ ux ul) uy (/ uy ul) uz (/ uz ul)
              base (* i NV)]
          ;; ring depth, for the painter's ordering below
          (let [rz (+ (* px say) (* pz cay))]
            (aset-double ring-z i (- (* rz cax) (* py sax))))
          (loop [j 0]
            (when (< j NV)
              (let [c0 (aget cos-phi j)
                    s0 (aget sin-phi j)
                    cp (- (* c0 cpo) (* s0 spo))
                    sp (+ (* s0 cpo) (* c0 spo))
                    ;; surface normal, then the point
                    vx (+ (* ux cp) (* nx sp))
                    vy (+ (* uy cp) (* ny sp))
                    vz (+ (* uz cp) (* nz sp))
                    wx (+ px (* rr3 vx))
                    wy (+ py (* rr3 vy))
                    wz (+ pz (* rr3 vz))
                    ;; rotate about Y, then about X
                    x1 (- (* wx cay) (* wz say))
                    z1 (+ (* wx say) (* wz cay))
                    y2 (+ (* wy cax) (* z1 sax))
                    z2 (- (* z1 cax) (* wy sax))
                    ;; same rotation on the normal
                    m1 (- (* vx cay) (* vz say))
                    q1 (+ (* vx say) (* vz cay))
                    m2 (+ (* vy cax) (* q1 sax))
                    q2 (- (* q1 cax) (* vy sax))
                    k (/ (* zm focal) (+ focal dist z2))
                    ;; diffuse plus a rim term
                    lum (+ (* 0.60 (max 0.0 (+ (* m1 -0.40) (* m2 -0.62) (* q2 -0.68))))
                           (* 0.28 (max 0.0 (- q2)))
                           0.12)
                    idx (min 63 (max 0 (int (* 63.0 lum))))
                    o (+ base j)]
                (aset-double sx o (+ CX (* x1 k)))
                (aset-double sy o (- CY (* y2 k)))
                (aset-int shade o idx))
              (recur (inc j))))
          (recur (inc i)))))
    ;; far rings first
    (loop [i 1]
      (when (< i nu)
        (let [v (aget order i)
              vz (aget ring-z v)]
          (loop [k (dec i)]
            (if (and (>= k 0) (< (aget ring-z (aget order k)) vz))
              (do (aset-int order (inc k) (aget order k))
                  (recur (dec k)))
              (aset-int order (inc k) v))))
        (recur (inc i))))))

;; one flat-shaded quad per cell as two rlgl triangles, back faces dropped by
;; the sign of the 2D cross product
(defn draw! []
  (let [nu (long @NU)]
    (loop [oi 0]
      (when (< oi nu)
        ;; one batch per ring: rlgl cannot flush inside an open rlBegin/rlEnd,
        ;; and the whole surface overflows its vertex buffer
        (rl-begin RL-TRIANGLES)
        (let [i (aget order oi)
              i2 (let [x (inc i)] (if (= x nu) 0 x))
              b1 (* i NV)
              b2 (* i2 NV)]
          (loop [j 0]
            (when (< j NV)
              (let [j2 (let [x (inc j)] (if (= x NV) 0 x))
                    a (+ b1 j) b (+ b1 j2)
                    c (+ b2 j2) d (+ b2 j)
                    xa (aget sx a) ya (aget sy a)
                    xb (aget sx b) yb (aget sy b)
                    xc (aget sx c) yc (aget sy c)]
                (when (pos? (- (* (- xb xa) (- yc ya))
                               (* (- yb ya) (- xc xa))))
                  (let [s (aget shade a)]
                    (rl-color-4ub (aget palette-r s) (aget palette-g s) (aget palette-b s) 255))
                  (rl-vertex-2f xa ya) (rl-vertex-2f xb yb) (rl-vertex-2f xc yc)
                  (rl-vertex-2f xa ya) (rl-vertex-2f xc yc)
                  (rl-vertex-2f (aget sx d) (aget sy d))))
              (recur (inc j)))))
        (rl-end)
        (recur (inc oi))))))

(def last-px (atom 0))
(def last-py (atom 0))

(defn advance-camera! [dt]
  (if (pos? (mouse-down? 0))
    (let [x (get-mouse-x) y (get-mouse-y)]
      (when @dragging
        (let [dx (- x @last-px) dy (- y @last-py)]
          (swap! rot-y + (* dx 0.008))
          (swap! rot-x (fn [v] (max -1.45 (min 1.45 (+ v (* dy 0.008))))))
          (reset! vel-y (* dx 0.35))
          (reset! vel-x (* dy 0.35))))
      (reset! dragging true)
      (reset! last-px x)
      (reset! last-py y))
    (do
      (reset! dragging false)
      ;; releasing keeps the spin and decays it to an idle turn
      (let [decay (Math/pow 0.94 (/ dt 0.016))
            vy (+ (* @vel-y decay) (* 0.16 (- 1.0 decay)))]
        (reset! vel-y vy)
        (reset! vel-x (* @vel-x decay)))))
  (swap! rot-y + (* @vel-y dt))
  (swap! rot-x (fn [v] (max -1.45 (min 1.45 (+ v (* @vel-x dt)))))))

(defn handle-keys! []
  (let [w (get-wheel)]
    (when-not (zero? w)
      (swap! zoom (fn [z] (max 110.0 (min 520.0 (* z (Math/exp (* 0.09 w)))))))))
  (when (pos? (key-pressed? KEY-RIGHT)) (swap! twists #(min 24 (inc %))))
  (when (pos? (key-pressed? KEY-LEFT)) (swap! twists #(max 3 (dec %))))
  (when (pos? (key-down? KEY-UP)) (swap! NU #(min MAX-NU (+ % 4))))
  (when (pos? (key-down? KEY-DOWN)) (swap! NU #(max 60 (- % 4)))))

(init-window W H "babashka.ffi - helitorus")
(set-target-fps 120)
(rl-disable-backface-culling)

(def frames (atom 0))
(def acc (atom 0.0))
(def c-acc (atom 0.0))
(def d-acc (atom 0.0))
(def hud (atom "computing..."))
(def frames-total (atom 0))

(loop [t 0.0]
  (when (and (zero? (window-should-close))
             (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (let [dt (get-frame-time)
          t (+ t dt)]
      (handle-keys!)
      (advance-camera! dt)
      (let [c0 (System/nanoTime)]
        (compute! t)
        (swap! c-acc + (/ (- (System/nanoTime) c0) 1e6)))
      (begin-drawing)
      (clear-background (rgba 255 255 255 255))
      (let [d0 (System/nanoTime)]
        (draw!)
        (swap! d-acc + (/ (- (System/nanoTime) d0) 1e6)))
      (swap! frames inc)
      (swap! acc + dt)
      (when (>= @acc 0.4)
        (let [f @frames]
          (reset! hud (format "fps %.0f | compute %.1f ms | draw %.1f ms | %dk verts/s | windings %d | res %d"
                              (/ f @acc) (/ @c-acc f) (/ @d-acc f)
                              (int (/ (* (/ f @acc) @NU NV) 1000))
                              @twists @NU))
          (println @hud))
        (reset! frames 0) (reset! acc 0.0) (reset! c-acc 0.0) (reset! d-acc 0.0))
      (draw-text @hud 12 12 20 (rgba 55 65 81 255))
      (draw-text "drag to turn, wheel to zoom, LEFT/RIGHT windings, UP/DOWN resolution"
                 12 (- H 28) 16 (rgba 156 163 175 255))
      (end-drawing)
      (when (and (= 20 @frames-total) (System/getenv "SHOT"))
        (screenshot "helitorus.png"))
      (swap! frames-total inc)
      (recur t))))

(close-window)
(println "helitorus done")
