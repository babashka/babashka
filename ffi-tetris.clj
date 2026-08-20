;; Tetris over babashka.ffi, ported from b12n-raylib-jlt/tetris.clj.
;; LEFT/RIGHT move, UP rotates, DOWN soft-drops, SPACE hard-drops, ENTER
;; restarts after GAME OVER. Optional CLI arg: seconds to auto-quit.

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
(defcfn draw-rectangle "DrawRectangle" [:int :int :int :int :uint] :void)
(defcfn draw-rectangle-lines "DrawRectangleLines" [:int :int :int :int :uint] :void)
(defcfn is-key-pressed "IsKeyPressed" [:int] :uint8)
(defcfn is-key-down "IsKeyDown" [:int] :uint8)
(defcfn get-random-value "GetRandomValue" [:int :int] :int)

(defn key-pressed? [k] (pos? (is-key-pressed k)))
(defn key-down? [k] (pos? (is-key-down k)))

(defn rgba [r g b a]
  (bit-or r (bit-shift-left g 8) (bit-shift-left b 16) (bit-shift-left a 24)))

(def RAYWHITE (rgba 245 245 245 255))
(def GRAY (rgba 130 130 130 255))
(def RED (rgba 230 41 55 255))
(def SKYBLUE (rgba 102 191 255 255))
(def GOLD (rgba 255 203 0 255))
(def PURPLE (rgba 200 122 255 255))
(def LIME (rgba 0 158 47 255))
(def BLUE (rgba 0 121 241 255))
(def ORANGE (rgba 255 161 0 255))

(def KEY-SPACE 32)
(def KEY-ENTER 257)
(def KEY-RIGHT 262)
(def KEY-LEFT 263)
(def KEY-DOWN 264)
(def KEY-UP 265)

(def W 800)
(def H 450)
(def COLS 10)
(def ROWS 20)
(def CELL 18)
(def WELL-X 200)
(def WELL-Y 40)

(def PIECES
  {:i {:color SKYBLUE
       :rots [[[0 1] [1 1] [2 1] [3 1]] [[2 0] [2 1] [2 2] [2 3]]]}
   :o {:color GOLD
       :rots [[[1 0] [2 0] [1 1] [2 1]]]}
   :t {:color PURPLE
       :rots [[[1 0] [0 1] [1 1] [2 1]] [[1 0] [1 1] [2 1] [1 2]]
              [[0 1] [1 1] [2 1] [1 2]] [[1 0] [0 1] [1 1] [1 2]]]}
   :s {:color LIME
       :rots [[[1 0] [2 0] [0 1] [1 1]] [[1 0] [1 1] [2 1] [2 2]]]}
   :z {:color RED
       :rots [[[0 0] [1 0] [1 1] [2 1]] [[2 0] [1 1] [2 1] [1 2]]]}
   :j {:color BLUE
       :rots [[[0 0] [0 1] [1 1] [2 1]] [[1 0] [2 0] [1 1] [1 2]]
              [[0 1] [1 1] [2 1] [2 2]] [[1 0] [1 1] [0 2] [1 2]]]}
   :l {:color ORANGE
       :rots [[[2 0] [0 1] [1 1] [2 1]] [[1 0] [1 1] [1 2] [2 2]]
              [[0 1] [1 1] [2 1] [0 2]] [[0 0] [1 0] [1 1] [1 2]]]}})

(def PIECE-TYPES [:i :o :t :s :z :j :l])

(defn rand-type []
  (nth PIECE-TYPES (get-random-value 0 6)))

(defn spawn [type]
  {:type type :rot 0 :x 3 :y 0})

(defn interval [level]
  (max 6 (- 48 (* 4 level))))

(defn piece-cells [{:keys [type rot x y]}]
  (let [rots (:rots (PIECES type))
        state (nth rots (mod rot (count rots)))]
    (map (fn [[cx cy]] [(+ x cx) (+ y cy)]) state)))

(defn valid? [board piece]
  (every? (fn [[c r]]
            (and (>= c 0) (< c COLS) (>= r 0) (< r ROWS) (nil? (get-in board [r c]))))
          (piece-cells piece)))

(defn lock [board piece]
  (let [color (:color (PIECES (:type piece)))]
    (reduce (fn [b [c r]] (assoc-in b [r c] color)) board (piece-cells piece))))

(defn clear-lines [board]
  (let [kept (filterv (fn [row] (some nil? row)) board)
        cleared (- ROWS (count kept))]
    [(into (vec (repeat cleared (vec (repeat COLS nil)))) kept) cleared]))

(defn hard-drop [board piece]
  (loop [p piece]
    (let [pd (update p :y inc)]
      (if (valid? board pd) (recur pd) p))))

(defn initial-state []
  {:board (vec (repeat ROWS (vec (repeat COLS nil))))
   :piece (spawn (rand-type))
   :next (rand-type)
   :score 0
   :lines 0
   :level 0
   :tick 0
   :over? false})

(defn lock-and-next [s board piece]
  (let [[board' cleared] (clear-lines (lock board piece))
        lines (+ (:lines s) cleared)
        next-p (spawn (:next s))]
    (assoc s :board board' :piece next-p :next (rand-type) :tick 0
           :lines lines :level (quot lines 10)
           :score (+ (:score s) (nth [0 40 100 300 1200] cleared))
           :over? (not (valid? board' next-p)))))

(defn step [s]
  (if (:over? s)
    (if (key-pressed? KEY-ENTER) (initial-state) s)
    (let [board (:board s)
          dx (cond (key-pressed? KEY-LEFT) -1 (key-pressed? KEY-RIGHT) 1 :else 0)
          p1 (let [p (update (:piece s) :x + dx)] (if (valid? board p) p (:piece s)))
          p2 (if (key-pressed? KEY-UP)
               (let [p (update p1 :rot inc)] (if (valid? board p) p p1))
               p1)
          soft? (key-down? KEY-DOWN)
          tick (inc (:tick s))
          drop? (or soft? (>= tick (interval (:level s))))]
      (cond
        (key-pressed? KEY-SPACE) (lock-and-next s board (hard-drop board p2))
        drop? (let [pd (update p2 :y inc)]
                (if (valid? board pd) (assoc s :piece pd :tick 0) (lock-and-next s board p2)))
        :else (assoc s :piece p2 :tick tick)))))

(defn cell! [c r color]
  (draw-rectangle (+ WELL-X (* c CELL)) (+ WELL-Y (* r CELL))
                  (dec CELL) (dec CELL) color))

(defn draw-state [s]
  (clear-background (rgba 18 18 28 255))
  (draw-rectangle-lines (- WELL-X 2) (- WELL-Y 2)
                        (+ (* COLS CELL) 4) (+ (* ROWS CELL) 4) GRAY)
  (doseq [r (range ROWS) c (range COLS)]
    (when-let [color (get-in (:board s) [r c])] (cell! c r color)))
  (let [color (:color (PIECES (:type (:piece s))))]
    (doseq [[c r] (piece-cells (:piece s))]
      (when (>= r 0) (cell! c r color))))
  (draw-text "TETRIS" 470 40 34 RAYWHITE)
  (draw-text (str "SCORE " (:score s)) 470 110 20 RAYWHITE)
  (draw-text (str "LINES " (:lines s)) 470 140 20 RAYWHITE)
  (draw-text (str "LEVEL " (:level s)) 470 170 20 RAYWHITE)
  (draw-text "NEXT" 470 220 20 GRAY)
  (let [color (:color (PIECES (:next s)))]
    (doseq [[cx cy] (first (:rots (PIECES (:next s))))]
      (draw-rectangle (+ 480 (* cx CELL)) (+ 250 (* cy CELL))
                      (dec CELL) (dec CELL) color)))
  (draw-text "<> move   ^ rotate   v soft   SPACE hard drop" 30 (- H 26) 16 GRAY)
  (when (:over? s)
    (draw-text "GAME OVER" 130 180 34 RED)
    (draw-text "ENTER to restart" 120 230 18 RAYWHITE)))

(def deadline
  (when-let [secs (first *command-line-args*)]
    (+ (System/currentTimeMillis) (* 1000 (parse-long secs)))))

(init-window W H "babashka.ffi - tetris")
(set-target-fps 60)

(loop [s (initial-state)]
  (when (and (zero? (window-should-close))
             (or (nil? deadline) (< (System/currentTimeMillis) deadline)))
    (let [s' (step s)]
      (begin-drawing)
      (draw-state s')
      (end-drawing)
      (recur s'))))

(close-window)
(println "tetris done")
