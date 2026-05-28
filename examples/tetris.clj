#!/usr/bin/env bb

;; Tetris in the terminal. Run with: bb tetris.clj
;; Uses JLine (bundled with babashka) for raw keyboard input and ANSI
;; truecolor escapes for rendering. No external dependencies.
;;
;; Board is a vector of rows (top to bottom), each row a vector of cells.
;; A cell is either nil (empty) or a keyword for the piece color.

(ns tetris
  (:import [org.jline.terminal TerminalBuilder]))

(def COLS 10)
(def ROWS 20)
(def TICK-MS 500)

;; [r g b] per piece kind, matching the browser version's hex colors.
(def COLORS
  {:I [0x22 0xd3 0xee]
   :O [0xfa 0xcc 0x15]
   :T [0xa8 0x55 0xf7]
   :S [0x22 0xc5 0x5e]
   :Z [0xef 0x44 0x44]
   :L [0xf9 0x73 0x16]
   :J [0x3b 0x82 0xf6]})

;; Pieces as sets of [x y] offsets from the piece origin. Rotation is computed
;; by rotating around (0,0): (x,y) -> (-y, x). O never rotates (handled below).
(def PIECES
  {:I [[-1 0] [0 0] [1 0] [2 0]]
   :O [[0 0] [1 0] [0 1] [1 1]]
   :T [[-1 0] [0 0] [1 0] [0 1]]
   :S [[0 0] [1 0] [-1 1] [0 1]]
   :Z [[-1 0] [0 0] [0 1] [1 1]]
   :L [[-1 0] [0 0] [1 0] [-1 1]]
   :J [[-1 0] [0 0] [1 0] [1 1]]})

(defn empty-board []
  (vec (repeat ROWS (vec (repeat COLS nil)))))

(defn rand-piece []
  (let [k (rand-nth (keys PIECES))]
    {:kind k
     :cells (get PIECES k)
     :pos [(quot COLS 2) 0]}))

(defn new-state []
  {:board (empty-board)
   :piece (rand-piece)
   :score 0
   :lines 0
   :over? false
   :paused? false})

(defn piece-cells
  "Absolute [x y] board coords occupied by `piece`."
  [piece]
  (let [[px py] (:pos piece)]
    (map (fn [[dx dy]] [(+ px dx) (+ py dy)]) (:cells piece))))

(defn in-bounds? [[x y]]
  (and (<= 0 x) (< x COLS) (< y ROWS)))

(defn collides?
  "True if any cell of `piece` is out of bounds (sides/bottom) or hits an
  occupied cell on `board`. y < 0 is allowed (spawn above the top)."
  [board piece]
  (some (fn [[x y]]
          (or (< x 0) (>= x COLS) (>= y ROWS)
              (and (>= y 0) (get-in board [y x]))))
        (piece-cells piece)))

(defn rotate-cells [cells]
  (mapv (fn [[x y]] [(- y) x]) cells))

(defn lock-piece
  "Burn `piece` into `board`, returning the new board."
  [board piece]
  (reduce (fn [b [x y]]
            (if (and (>= y 0) (in-bounds? [x y]))
              (assoc-in b [y x] (:kind piece))
              b))
          board
          (piece-cells piece)))

(defn clear-lines
  "Drop full rows; return [new-board cleared-count]."
  [board]
  (let [kept (vec (remove (fn [row] (every? some? row)) board))
        cleared (- ROWS (count kept))
        pad (vec (repeat cleared (vec (repeat COLS nil))))]
    [(into pad kept) cleared]))

(def SCORES {0 0 1 100 2 300 3 500 4 800})

(defn try-move [s dx dy]
  (let [p' (update (:piece s) :pos (fn [[x y]] [(+ x dx) (+ y dy)]))]
    (if (collides? (:board s) p') s (assoc s :piece p'))))

(defn try-rotate [s]
  (let [p (:piece s)
        p' (if (= :O (:kind p)) p (update p :cells rotate-cells))]
    (if (collides? (:board s) p') s (assoc s :piece p'))))

(defn step-down
  "Gravity tick: move piece down, or lock + clear + spawn next. Sets :over?
  when the freshly spawned piece can't fit (game over)."
  [s]
  (let [p (:piece s)
        p' (update p :pos (fn [[x y]] [x (inc y)]))]
    (if (collides? (:board s) p')
      (let [locked (lock-piece (:board s) p)
            [board' n] (clear-lines locked)
            next-piece (rand-piece)
            over? (boolean (collides? board' next-piece))]
        (-> s
            (assoc :board board'
                   :piece next-piece
                   :over? over?)
            (update :score + (get SCORES n 0))
            (update :lines + n)))
      (assoc s :piece p'))))

(defn hard-drop [s]
  (loop [s s]
    (let [p (:piece s)
          p' (update p :pos (fn [[x y]] [x (inc y)]))]
      (if (collides? (:board s) p')
        (step-down s)
        (recur (assoc s :piece p'))))))

;; -------------------------------------------------------------- rendering ----

(def ESC (str (char 27)))
(def CSI (str ESC "["))

(defn bg [[r g b]] (str CSI "48;2;" r ";" g ";" b "m"))
(def RESET (str CSI "0m"))
(def EMPTY-BG (bg [0x1f 0x29 0x37]))     ; empty cell
(def FRAME-BG (bg [0x11 0x11 0x11]))     ; board background / border

(defn render
  "Return the full screen as one string: cursor home + board + side panel."
  [s]
  (let [b (:board s)
        piece (:piece s)
        active (into {} (map (fn [c] [c (:kind piece)]) (piece-cells piece)))
        cell-color (fn [x y] (or (get active [x y]) (get-in b [y x])))
        ;; Each cell is two chars wide so blocks look square in the terminal.
        top (str FRAME-BG "+" (apply str (repeat (* 2 COLS) "-")) "+" RESET)
        panel ["" "  TETRIS"
               (str "  Score: " (:score s))
               (str "  Lines: " (:lines s))
               ""
               "  <-/->  move"
               "  up     rotate"
               "  down   soft drop"
               "  space  hard drop"
               "  p      pause"
               "  r      reset"
               "  q      quit"
               ""
               (cond (:over? s)   "  *** GAME OVER ***  (r)"
                     (:paused? s) "  *** PAUSED ***"
                     :else "")]
        rows (for [y (range ROWS)]
               (str FRAME-BG "|"
                    (apply str
                           (for [x (range COLS)]
                             (if-let [k (cell-color x y)]
                               (str (bg (COLORS k)) "  " FRAME-BG)
                               (str EMPTY-BG "  " FRAME-BG))))
                    "|" RESET
                    (when-let [line (nth panel (inc y) nil)] (str "  " line))))
        bottom (str FRAME-BG "+" (apply str (repeat (* 2 COLS) "-")) "+" RESET
                    "  " (nth panel (inc ROWS) ""))]
    (str CSI "H"                          ; cursor home
         CSI "?25l"                       ; hide cursor
         top (nth panel 0 "") "\r\n"
         (apply str (interpose "\r\n" rows)) "\r\n"
         bottom
         CSI "J")))                       ; clear to end of screen

;; --------------------------------------------------------------- main loop ----

(defn read-key
  "Read one logical key from the JLine reader with a timeout (ms). Returns a
  keyword for arrows, the char for printables, or :none on timeout. Arrow keys
  arrive as the escape sequence ESC [ A/B/C/D."
  [reader timeout]
  (let [c (.read reader (int timeout))]
    (cond
      (= c -2) :none                      ; timeout
      (= c -1) :eof
      (= c 27) (if (= 91 (.read reader (int 5)))   ; '['
                 (case (.read reader (int 5))
                   65 :up 66 :down 67 :right 68 :left :none)
                 :esc)
      :else (char c))))

(defn -main []
  (let [term (-> (TerminalBuilder/builder)
                 (.system true)
                 (.build))]
    (.enterRawMode term)
    (let [reader (.reader term)
          out (.writer term)
          draw! (fn [s] (.write out (render s)) (.flush out))]
      (try
        (.write out (str CSI "2J"))       ; clear screen once
        (loop [s (new-state)
               last (System/currentTimeMillis)]
          (draw! s)
          (let [k (read-key reader 50)
                now (System/currentTimeMillis)
                tick? (>= (- now last) TICK-MS)
                ;; apply key
                s (if (:over? s)
                    (if (= \r k) (new-state) s)
                    (case k
                      :left  (try-move s -1 0)
                      :right (try-move s 1 0)
                      :down  (try-move s 0 1)
                      :up    (try-rotate s)
                      \space (hard-drop s)
                      \p     (update s :paused? not)
                      \r     (new-state)
                      s))]
            (cond
              (or (= k \q) (= k :eof)) nil   ; quit
              ;; gravity tick (skip when over/paused)
              (and tick? (not (:over? s)) (not (:paused? s)))
              (recur (step-down s) now)
              tick? (recur s now)
              :else (recur s last))))
        (finally
          (.write out (str CSI "?25h" RESET CSI "2J" CSI "H"))  ; restore cursor
          (.flush out)
          (.close term))))))

(-main)
