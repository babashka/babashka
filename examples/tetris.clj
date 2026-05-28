#!/usr/bin/env bb

;; Tetris in the terminal. Run with: bb tetris.clj
;; Uses JLine (bundled with babashka) for raw keyboard input and screen
;; rendering. No external dependencies.
;;
;; Rather than hand-coding ANSI escapes, this leans on JLine's higher-level
;; helpers:
;;   - AttributedString / AttributedStyle for colored cells
;;   - Display for diff-based, flicker-free screen updates
;;   - enter_ca_mode (alternate screen) so the game owns the whole screen and
;;     the shell is restored untouched on exit
;;
;; Board is a vector of rows (top to bottom), each row a vector of cells.
;; A cell is either nil (empty) or a keyword for the piece color.

(ns tetris
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.utils AttributedString AttributedStringBuilder AttributedStyle
                            Display InfoCmp$Capability]))

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

(defn rgb-style [[r g b]]
  (.background AttributedStyle/DEFAULT (int r) (int g) (int b)))

(def cell-styles (update-vals COLORS rgb-style))   ; piece kind -> style
(def empty-style (rgb-style [0x1f 0x29 0x37]))     ; empty cell

(defn panel-lines
  "The text shown to the right of the board, one entry per board row."
  [s]
  ["TETRIS"
   (str "Score: " (:score s))
   (str "Lines: " (:lines s))
   ""
   "<-/->  move"
   "up     rotate"
   "down   soft drop"
   "space  hard drop"
   "p      pause"
   "r      reset"
   "q      quit"
   ""
   (cond (:over? s)   "*** GAME OVER *** (r)"
         (:paused? s) "*** PAUSED ***"
         :else "")])

(defn render-lines
  "Build the screen as a list of AttributedStrings for Display to render: the
  active piece overlaid on the board, framed, with the side panel attached."
  [s]
  (let [b (:board s)
        piece (:piece s)
        active (into {} (map (fn [c] [c (:kind piece)]) (piece-cells piece)))
        cell-style (fn [x y]
                     (if-let [k (or (get active [x y]) (get-in b [y x]))]
                       (cell-styles k)
                       empty-style))
        panel (panel-lines s)
        border (AttributedString. (str "+" (apply str (repeat (* 2 COLS) "-")) "+"))
        lines (java.util.ArrayList.)]
    (.add lines border)
    (doseq [y (range ROWS)]
      ;; Each cell is two spaces wide so blocks look roughly square.
      (let [sb (AttributedStringBuilder.)]
        (.append sb "|")
        (doseq [x (range COLS)]
          (.append sb "  " (cell-style x y)))
        (.append sb "|")
        (when-let [text (get panel y)]
          (when (seq text) (.append sb (str "  " text))))
        (.add lines (.toAttributedString sb))))
    (.add lines border)
    lines))

;; --------------------------------------------------------------- input ----

(def ESC 27)

;; Final byte of a cursor escape sequence -> logical key. Both ESC [ A and
;; ESC O A end in the same letter, so this covers either cursor-key mode.
(def CURSOR-KEYS {\A :up \B :down \C :right \D :left})

(defn read-key
  "Read one logical keypress with a 50ms timeout: an arrow keyword, a typed
  character, :none on timeout, or :eof. Reads exactly one escape sequence (no
  more) so that a held-down arrow's queued repeats stay buffered for the next
  call instead of being swallowed into one."
  [reader]
  (let [c (.read reader (int 50))]
    (cond
      (= c -2) :none                       ; timeout
      (= c -1) :eof
      (= c ESC)                            ; ESC [ X  or  ESC O X
      (let [c1 (.read reader (int 5))]
        (if (#{(int \[) (int \O)} c1)
          (let [c2 (.read reader (int 5))] ; final byte may lag; -2 if it does
            (if (neg? c2) :none (get CURSOR-KEYS (char c2) :none)))
          :none))
      :else (char c))))

;; --------------------------------------------------------------- main loop ----

(defn puts!
  "Emit a terminal capability (puts takes varargs we don't use here)."
  [term cap]
  (.puts term cap (object-array 0)))

(defn -main []
  (let [term (-> (TerminalBuilder/builder) (.system true) (.build))]
    (.enterRawMode term)
    (let [reader (.reader term)
          display (Display. term true)
          size (atom nil)
          draw! (fn [s]
                  ;; Only resize when the terminal actually changed: resize drops
                  ;; the diff cache, so calling it every frame forces a full
                  ;; repaint (flicker) instead of a smooth incremental update.
                  (let [sz (.getSize term)
                        dims [(.getRows sz) (.getColumns sz)]]
                    (when (not= @size dims)
                      (reset! size dims)
                      (.resize display (first dims) (second dims))))
                  (.update display (render-lines s) 0)
                  (.flush term))]
      (puts! term InfoCmp$Capability/enter_ca_mode)   ; alternate screen buffer
      (puts! term InfoCmp$Capability/cursor_invisible)
      (.flush term)
      (try
        (loop [s (new-state)
               last (System/currentTimeMillis)]
          (draw! s)
          (let [k (read-key reader)
                now (System/currentTimeMillis)
                tick? (>= (- now last) TICK-MS)
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
              (or (= k \q) (= k :eof)) nil          ; quit
              (and tick? (not (:over? s)) (not (:paused? s)))
              (recur (step-down s) now)
              tick? (recur s now)
              :else (recur s last))))
        (finally
          (puts! term InfoCmp$Capability/cursor_visible)
          (puts! term InfoCmp$Capability/exit_ca_mode)   ; restore the shell screen
          (.flush term)
          (.close term))))))

(-main)
