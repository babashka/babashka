;; Pac-Man in babashka, drawn with raylib through babashka.ffi.
;;
;;   bb ffi-pacman.clj [seconds]
;;
;; Arrows or WASD steer, ENTER restarts after a game over.
;;
;; Ghosts keep their classic personalities: Blinky chases, Pinky aims four
;; tiles ahead, Inky reflects Blinky through that point, Clyde backs off when
;; he gets close. They alternate scatter and chase, and turn blue when
;; Pac-Man eats a power pellet.

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
(defcfn draw-text "DrawText" [:string :int :int :int :uint] :void)
(defcfn measure-text "MeasureText" [:string :int] :int)
(defcfn screenshot "TakeScreenshot" [:string] :void)
(defcfn get-frame-time "GetFrameTime" [] :float)
(defcfn key-down? "IsKeyDown" [:int] :uint8)
(defcfn key-pressed? "IsKeyPressed" [:int] :uint8)
(defcfn rl-begin "rlBegin" [:int] :void)
(defcfn rl-end "rlEnd" [] :void)
(defcfn rl-vertex-2f "rlVertex2f" [:float :float] :void)
(defcfn rl-color-4ub "rlColor4ub" [:int :int :int :int] :void)
(defcfn rl-disable-backface-culling "rlDisableBackfaceCulling" [] :void)

(def RL-TRIANGLES 4)
(def KEY-RIGHT 262) (def KEY-LEFT 263) (def KEY-DOWN 264) (def KEY-UP 265)
(def KEY-D 68) (def KEY-A 65) (def KEY-S 83) (def KEY-W 87) (def KEY-ENTER 257)

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def BLACK (rgba 6 6 14 255))
(def BLUE (rgba 33 33 222 255))
(def BLUE-DARK (rgba 20 20 130 255))
(def YELLOW (rgba 255 224 40 255))
(def WHITE (rgba 240 240 245 255))
(def PINKISH (rgba 255 184 174 255))

;; --- maze --------------------------------------------------------------------
;; # wall, . dot, o power pellet, space empty, - ghost door, P pac-man start,
;; G ghost start

(def maze
  ["###################"
   "#........#........#"
   "#o##.###.#.###.##o#"
   "#.................#"
   "#.##.#.#####.#.##.#"
   "#....#...#...#....#"
   "####.###.#.###.####"
   "#....#.......#....#"
   "#.##.#.##-##.#.##.#"
   "#.##...#GGG#...##.#"
   ".....#.#GGG#.#....."
   "#.##...#####...##.#"
   "#.##.#...#...#.##.#"
   "#....#.#####.#....#"
   "####.#...#...#.####"
   "#o.......P.......o#"
   "#.###.#######.###.#"
   "#...#....#....#...#"
   "###.#.##.#.##.#.###"
   "#.................#"
   "###################"])

(def MW (count (first maze)))
(def MH (count maze))
(doseq [row maze]
  (assert (= MW (count row)) (str "maze row not " MW " wide: " row)))

(defn tile-at [x y]
  (if (or (< y 0) (>= y MH))
    \#
    (let [x (mod x MW)]
      (.charAt ^String (nth maze y) x))))

(defn wall? [x y]
  (let [c (tile-at x y)]
    (or (= \# c) (= \- c))))

(defn ghost-wall? [x y]
  (= \# (tile-at x y)))

;; --- geometry ----------------------------------------------------------------

(def CELL 30)
(def OX 20)
(def OY 70)
(def W (+ (* MW CELL) (* 2 OX)))
(def H (+ (* MH CELL) OY 30))

(defn px [gx] (+ OX (* gx CELL)))
(defn py [gy] (+ OY (* gy CELL)))

(def deadline
  (when-let [secs (some-> (first *command-line-args*) parse-long)]
    (+ (System/currentTimeMillis) (* 1000 secs))))

;; --- state -------------------------------------------------------------------

(defn find-tile [ch]
  (first (for [y (range MH) x (range MW)
               :when (= ch (tile-at x y))]
           [x y])))

(def pac-start (or (find-tile \P) [9 15]))
(def ghost-start (or (find-tile \G) [9 9]))

(defn initial-dots []
  (into #{} (for [y (range MH) x (range MW)
                  :when (#{\. \o} (tile-at x y))]
              [x y])))

(defn initial-ghosts []
  (mapv (fn [[nm color scatter i]]
          {:name nm :color color :scatter scatter
           :x (+ (first ghost-start) (- i 1.0)) :y (double (second ghost-start))
           :dx 0 :dy -1 :mode :scatter :frightened 0.0 :home-timer (* i 1.4)})
        [["blinky" (rgba 255 60 50 255) [(dec MW) 0] 0]
         ["pinky" (rgba 255 160 200 255) [0 0] 1]
         ["inky" (rgba 90 220 240 255) [(dec MW) (dec MH)] 2]
         ["clyde" (rgba 255 170 60 255) [0 (dec MH)] 3]]))

(def state (atom nil))

(defn reset-game! [& {:keys [keep-score] :or {keep-score false}}]
  (reset! state
          {:pac {:x (double (first pac-start)) :y (double (second pac-start))
                 :dx -1 :dy 0 :ndx -1 :ndy 0 :mouth 0.0}
           :ghosts (initial-ghosts)
           :dots (initial-dots)
           :score (if keep-score (:score @state) 0)
           :lives (if keep-score (:lives @state) 3)
           :level (if keep-score (inc (:level @state)) 1)
           :mode-timer 7.0
           :chase? false
           :combo 0
           :message nil
           :message-timer 0.0
           :over? false}))

;; --- maze validation: every dot must be reachable from the start -------------

(defn reachable-from [[sx sy]]
  (loop [seen #{[sx sy]} q [[sx sy]]]
    (if-let [[x y] (first q)]
      (let [nbrs (for [[dx dy] [[1 0] [-1 0] [0 1] [0 -1]]
                       :let [nx (mod (+ x dx) MW) ny (+ y dy)]
                       :when (and (>= ny 0) (< ny MH)
                                  (not (wall? nx ny))
                                  (not (seen [nx ny])))]
                   [nx ny])]
        (recur (into seen nbrs) (into (vec (rest q)) nbrs)))
      seen)))

(let [reach (reachable-from pac-start)
      orphans (remove reach (initial-dots))]
  (when (seq orphans)
    (println "WARNING: unreachable dots:" (count orphans) (vec (take 10 orphans)))))

;; --- movement ----------------------------------------------------------------

(defn centered?
  "True when v is close enough to a tile centre to turn there."
  [v]
  (< (Math/abs (- v (Math/floor v) 0.5)) 0.18))

(defn snap [v] (+ (Math/floor v) 0.5))

(defn can-go?
  "Can something at tile (round x,y) move in direction dx,dy?"
  [x y dx dy walls?]
  (let [tx (long (Math/floor x)) ty (long (Math/floor y))]
    (not (walls? (+ tx dx) (+ ty dy)))))

(defn advance
  "Move one step along dx,dy with tunnel wrap."
  [x y dx dy speed dt]
  [(mod (+ x (* dx speed dt)) MW) (+ y (* dy speed dt))])

(defn move-pac [pac dt]
  (let [{:keys [x y dx dy ndx ndy]} pac
        ;; take the buffered turn as soon as we are on a tile centre
        turn? (and (or (not= ndx dx) (not= ndy dy))
                   (centered? x) (centered? y)
                   (can-go? x y ndx ndy wall?))
        [dx dy x y] (if turn?
                      [ndx ndy (snap x) (snap y)]
                      [dx dy x y])
        blocked? (and (centered? x) (centered? y)
                      (not (can-go? x y dx dy wall?)))
        [nx ny] (if blocked?
                  [(snap x) (snap y)]
                  (advance x y dx dy 5.6 dt))]
    (assoc pac :x nx :y ny :dx dx :dy dy
           :mouth (+ (:mouth pac) (* dt (if blocked? 0 9.0))))))

(defn ghost-target
  "Classic personalities, in tile coordinates."
  [g pac ghosts]
  (let [{:keys [x y dx dy]} pac
        px (long x) py (long y)]
    (case (:name g)
      "blinky" [px py]
      "pinky" [(+ px (* 4 dx)) (+ py (* 4 dy))]
      "inky" (let [b (first (filter #(= "blinky" (:name %)) ghosts))
                   ax (+ px (* 2 dx)) ay (+ py (* 2 dy))]
               [(- (* 2 ax) (long (:x b))) (- (* 2 ay) (long (:y b)))])
      "clyde" (let [d (+ (Math/abs (- (:x g) x)) (Math/abs (- (:y g) y)))]
                (if (> d 8) [px py] (:scatter g))))))

(defn ghost-choose-dir
  "At a tile centre, pick the direction that gets closest to the target,
  without reversing."
  [g target]
  (let [{:keys [x y dx dy frightened]} g
        tx (long (Math/floor x)) ty (long (Math/floor y))
        opts (for [[ndx ndy] [[0 -1] [-1 0] [0 1] [1 0]]
                   :when (and (not (and (= ndx (- dx)) (= ndy (- dy))))
                              (not (ghost-wall? (+ tx ndx) (+ ty ndy))))]
               [ndx ndy])
        opts (if (seq opts) opts [[(- dx) (- dy)]])]
    (if (pos? frightened)
      (rand-nth (vec opts))
      (let [[gx gy] target]
        (apply min-key
               (fn [[ndx ndy]]
                 (let [ax (+ tx ndx) ay (+ ty ndy)]
                   (+ (* (- ax gx) (- ax gx)) (* (- ay gy) (- ay gy)))))
               opts)))))

(defn move-ghost [g pac ghosts chase? dt]
  (if (pos? (:home-timer g))
    ;; bob inside the house until released
    (update g :home-timer - dt)
    (let [g (update g :frightened #(max 0.0 (- % dt)))
          speed (cond (pos? (:frightened g)) 3.1
                      :else (+ 4.4 (* 0.2 (:level @state 1))))
          {:keys [x y dx dy]} g
          at-centre? (and (centered? x) (centered? y))
          target (if chase? (ghost-target g pac ghosts) (:scatter g))
          [dx dy x y] (if at-centre?
                        (let [[ndx ndy] (ghost-choose-dir g target)]
                          [ndx ndy (snap x) (snap y)])
                        [dx dy x y])
          [nx ny] (advance x y dx dy speed dt)]
      (assoc g :x nx :y ny :dx dx :dy dy))))

;; --- drawing -----------------------------------------------------------------

(defn draw-maze! [dots blink?]
  (dotimes [y MH]
    (dotimes [x MW]
      (let [c (tile-at x y)
            sx (px x) sy (py y)]
        (cond
          (= \# c)
          (do (draw-rectangle sx sy CELL CELL BLUE-DARK)
              (draw-rectangle (+ sx 3) (+ sy 3) (- CELL 6) (- CELL 6) BLUE))
          (= \- c)
          (draw-rectangle sx (+ sy (quot CELL 2) -2) CELL 4 PINKISH)))))
  (doseq [[x y] dots]
    (let [pellet? (= \o (tile-at x y))
          cx (+ (px x) (quot CELL 2))
          cy (+ (py y) (quot CELL 2))]
      (when (or (not pellet?) blink?)
        (draw-circle cx cy (if pellet? 7.0 3.0) YELLOW)))))

(defn draw-pac! [pac]
  ;; a filled fan with a mouth wedge cut out, in rlgl so no Vector2 is needed
  (let [cx (+ (px (:x pac)) 0.0)
        cy (+ (py (:y pac)) 0.0)
        r (* 0.46 CELL)
        base (Math/atan2 (double (:dy pac)) (double (:dx pac)))
        open (* 0.42 (+ 1.0 (Math/sin (:mouth pac))))
        steps 28]
    (rl-begin RL-TRIANGLES)
    (rl-color-4ub 255 224 40 255)
    (dotimes [i steps]
      (let [a0 (+ base open (* (- (* 2 Math/PI) (* 2 open)) (/ (double i) steps)))
            a1 (+ base open (* (- (* 2 Math/PI) (* 2 open)) (/ (+ i 1.0) steps)))]
        (rl-vertex-2f cx cy)
        (rl-vertex-2f (+ cx (* r (Math/cos a0))) (+ cy (* r (Math/sin a0))))
        (rl-vertex-2f (+ cx (* r (Math/cos a1))) (+ cy (* r (Math/sin a1))))))
    (rl-end)))

(defn draw-ghost! [g frightened-blink?]
  (let [cx (long (px (:x g))) cy (long (py (:y g)))
        r (long (* 0.44 CELL))
        col (cond (and (pos? (:frightened g)) frightened-blink?) WHITE
                  (pos? (:frightened g)) (rgba 40 60 230 255)
                  :else (:color g))]
    (draw-circle cx (- cy 2) (double r) col)
    (draw-rectangle (- cx r) (- cy 2) (* 2 r) (+ r 2) col)
    ;; feet
    (dotimes [i 3]
      (draw-circle (+ (- cx r) (* i r) (quot r 2)) (+ cy r) (/ (double r) 2.6) col))
    ;; eyes look along the direction of travel
    (let [ex (long (* 4 (:dx g))) ey (long (* 4 (:dy g)))]
      (draw-circle (- cx 5) (- cy 5) 5.0 WHITE)
      (draw-circle (+ cx 5) (- cy 5) 5.0 WHITE)
      (when-not (pos? (:frightened g))
        (draw-circle (+ (- cx 5) ex) (+ (- cy 5) ey) 2.5 (rgba 20 20 60 255))
        (draw-circle (+ cx 5 ex) (+ (- cy 5) ey) 2.5 (rgba 20 20 60 255))))))

(defn draw-hud! [s]
  (draw-text (str "SCORE " (:score s)) 20 20 28 WHITE)
  (draw-text (str "LEVEL " (:level s)) (- W 340) 20 28 WHITE)
  (dotimes [i (:lives s)]
    (draw-circle (+ (- W 150) (* i 34)) 33 11.0 YELLOW))
  (when-let [m (:message s)]
    (let [size 46
          w (measure-text m size)]
      (draw-text m (quot (- W w) 2) (- (quot H 2) 24) size
                 (if (:over? s) (rgba 255 90 80 255) YELLOW)))))

;; --- game step ---------------------------------------------------------------

(defn eat! [s]
  (let [pac (:pac s)
        tx (long (Math/floor (:x pac))) ty (long (Math/floor (:y pac)))]
    (if-not (contains? (:dots s) [tx ty])
      s
      (let [pellet? (= \o (tile-at tx ty))]
        (cond-> (-> s
                    (update :dots disj [tx ty])
                    (update :score + (if pellet? 50 10)))
          pellet? (-> (assoc :combo 0)
                      (update :ghosts (fn [gs] (mapv #(assoc % :frightened 7.0) gs)))))))))

(defn collide! [s]
  (let [pac (:pac s)]
    (reduce
     (fn [s i]
       (let [g (get-in s [:ghosts i])
             d (+ (Math/abs (- (:x g) (:x pac))) (Math/abs (- (:y g) (:y pac))))]
         (cond
           (> d 0.75) s
           (pos? (:frightened g))
           (let [combo (inc (:combo s))]
             (-> s
                 (update :score + (* 200 (bit-shift-left 1 (dec combo))))
                 (assoc :combo combo)
                 (assoc-in [:ghosts i] (merge g {:x (double (first ghost-start))
                                                 :y (double (second ghost-start))
                                                 :frightened 0.0 :home-timer 1.5}))))
           :else
           (let [lives (dec (:lives s))]
             (if (pos? lives)
               (-> s
                   (assoc :lives lives
                          :message "CAUGHT!" :message-timer 1.2
                          :pac {:x (double (first pac-start)) :y (double (second pac-start))
                                :dx -1 :dy 0 :ndx -1 :ndy 0 :mouth 0.0}
                          :ghosts (initial-ghosts)))
               (assoc s :lives 0 :over? true :message "GAME OVER"))))))
     s (range (count (:ghosts s))))))

(defn step! [dt]
  (swap! state
         (fn [s]
           (if (:over? s)
             s
             (let [s (update s :message-timer #(max 0.0 (- % dt)))
                   s (if (zero? (:message-timer s)) (assoc s :message nil) s)
                   ;; scatter and chase alternate, as in the original
                   s (let [t (- (:mode-timer s) dt)]
                       (if (pos? t)
                         (assoc s :mode-timer t)
                         (assoc s :chase? (not (:chase? s))
                                :mode-timer (if (:chase? s) 7.0 20.0))))
                   s (update s :pac move-pac dt)
                   s (eat! s)
                   s (update s :ghosts
                             (fn [gs] (mapv #(move-ghost % (:pac s) gs (:chase? s) dt) gs)))
                   s (collide! s)]
               (if (empty? (:dots s))
                 (assoc s :message "LEVEL CLEARED" :message-timer 2.0 :cleared? true)
                 s))))))

(defn read-input! []
  (let [set-dir (fn [dx dy] (swap! state update :pac assoc :ndx dx :ndy dy))]
    (when (or (pos? (key-down? KEY-LEFT)) (pos? (key-down? KEY-A))) (set-dir -1 0))
    (when (or (pos? (key-down? KEY-RIGHT)) (pos? (key-down? KEY-D))) (set-dir 1 0))
    (when (or (pos? (key-down? KEY-UP)) (pos? (key-down? KEY-W))) (set-dir 0 -1))
    (when (or (pos? (key-down? KEY-DOWN)) (pos? (key-down? KEY-S))) (set-dir 0 1))
    (when (and (pos? (key-pressed? KEY-ENTER)) (:over? @state))
      (reset-game!))))

;; --- main --------------------------------------------------------------------

(reset-game!)
(init-window W H "babashka.ffi - pac-man")
(set-target-fps 60)
(rl-disable-backface-culling)

(def frame (atom 0))

(loop [t 0.0]
  (when (and (zero? (window-should-close))
             (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (let [dt (min 0.05 (get-frame-time))
          t (+ t dt)]
      (read-input!)
      (step! dt)
      (when (:cleared? @state)
        (when (> (:message-timer @state) 0.0)
          nil)
        (when (zero? (:message-timer @state))
          (reset-game! :keep-score true)))
      (let [s @state]
        (begin-drawing)
        (clear-background BLACK)
        (draw-maze! (:dots s) (< (mod t 0.4) 0.25))
        (draw-pac! (:pac s))
        (doseq [g (:ghosts s)] (draw-ghost! g (< (mod t 0.25) 0.12)))
        (draw-hud! s)
        (end-drawing))
      (swap! frame inc)
      (when (and (System/getenv "SHOT")
                 (= (parse-long (System/getenv "SHOT")) @frame))
        (screenshot "pacman.png"))
      (recur t))))

(close-window)
(println "final score:" (:score @state) "level:" (:level @state))
