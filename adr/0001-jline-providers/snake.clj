#!/usr/bin/env bb

(ns snake
  "Snake game TUI using JLine terminal library"
  (:import [org.jline.terminal TerminalBuilder]
           [org.jline.utils AttributedString AttributedStringBuilder AttributedStyle Display InfoCmp$Capability]))

(defn grid-size [terminal]
  (let [term-size (.getSize terminal)
        cols (.getColumns term-size)
        rows (.getRows term-size)]
    ;; Leave room for borders and status lines
    [(max 10 (quot (- cols 2) 2))   ; width (each cell is 2 chars)
     (max 10 (- rows 6))]))          ; height

(defonce state
  (atom {:snake [[5 5] [4 5] [3 5]] ; initial snake body
         :dir [1 0] ; moving right
         :food [10 5] ; initial food
         :alive? true
         :score 0}))

(defn random-food [snake [width height]]
  (let [food [(rand-int width) (rand-int height)]]
    (if (some #(= food %) snake)
      (recur snake [width height])
      food)))

(defn move-snake [terminal]
  (let [[width height] (grid-size terminal)]
    (swap! state
      (fn [{:keys [snake dir food alive?] :as st}]
        (if (not alive?)
          st
          (let [head [(+ (first (first snake)) (first dir))
                      (+ (second (first snake)) (second dir))]
                ate? (= head food)
                new-snake (vec (cons head (if ate? snake (butlast snake))))
                [x y] head
                hit-wall? (or (< x 0) (< y 0) (>= x width) (>= y height))
                hit-self? (some #(= head %) (rest new-snake))]
            (cond
              hit-wall? (assoc st :alive? false)
              hit-self? (assoc st :alive? false)
              ate? (-> st
                       (assoc :snake new-snake)
                       (assoc :food (random-food new-snake [width height]))
                       (update :score inc))
              :else (assoc st :snake new-snake))))))))

(defn handle-key [key]
  (swap! state update :dir
    (fn [dir]
      (case key
        (:up \k) (if (= dir [0 1]) dir [0 -1])
        (:down \j) (if (= dir [0 -1]) dir [0 1])
        (:left \h) (if (= dir [1 0]) dir [-1 0])
        (:right \l) (if (= dir [-1 0]) dir [1 0])
        dir))))

(defn clear-screen [terminal]
  (.puts terminal InfoCmp$Capability/clear_screen (object-array 0))
  (.flush terminal))

(def ^:private style-green-bright (-> AttributedStyle/DEFAULT (.foreground AttributedStyle/GREEN) (.bold)))
(def ^:private style-green (.foreground AttributedStyle/DEFAULT AttributedStyle/GREEN))
(def ^:private style-red (.foreground AttributedStyle/DEFAULT AttributedStyle/RED))
(def ^:private style-default AttributedStyle/DEFAULT)

(defn- build-row-line [snake food width y]
  "Build an AttributedString for a single game row"
  (let [asb (AttributedStringBuilder.)]
    (.style asb style-default)
    (.append asb "|")
    (doseq [x (range width)]
      (let [pos [x y]
            is-head (= pos (first snake))
            is-body (and (not is-head) (some #(= pos %) snake))
            is-food (= pos food)]
        (cond
          is-head (do (.style asb style-green-bright) (.append asb "██"))
          is-body (do (.style asb style-green) (.append asb "██"))
          is-food (do (.style asb style-red) (.append asb "██"))
          :else (do (.style asb style-default) (.append asb "  ")))))
    (.style asb style-default)
    (.append asb "|")
    (.toAttributedString asb)))

(defn- build-display-lines [terminal]
  "Build all lines for the display as AttributedStrings"
  (let [{:keys [snake food alive? score]} @state
        term-size (.getSize terminal)
        cols (.getColumns term-size)
        rows (.getRows term-size)
        [width height] (grid-size terminal)
        border-line (str "+" (apply str (repeat (* width 2) "-")) "+")
        lines (java.util.ArrayList.)]
    ;; Top border
    (.add lines (AttributedString. border-line))
    ;; Grid rows
    (doseq [y (range height)]
      (.add lines (build-row-line snake food width y)))
    ;; Bottom border
    (.add lines (AttributedString. border-line))
    ;; Empty line
    (.add lines (AttributedString. ""))
    ;; Score line
    (.add lines (AttributedString. (str "Score: " score "  |  Terminal: " cols "x" rows "  |  Grid: " width "x" height)))
    ;; Controls line
    (.add lines (AttributedString. "Controls: Arrow keys or hjkl | q to quit"))
    ;; Game over message
    (when (not alive?)
      (.add lines (AttributedString. ""))
      (.add lines (AttributedString. "*** GAME OVER! Press 'r' to restart or 'q' to quit ***")))
    lines))

(defn render [display terminal]
  (let [term-size (.getSize terminal)
        cols (.getColumns term-size)
        rows (.getRows term-size)]
    (.resize display rows cols)
    (.update display (build-display-lines terminal) 0)))

(defn restart-game [terminal]
  (let [[width height] (grid-size terminal)
        start-y (quot height 2)]
    (reset! state {:snake [[5 start-y] [4 start-y] [3 start-y]]
                   :dir [1 0]
                   :food [(quot width 2) start-y]
                   :alive? true
                   :score 0})))

(defn read-key [reader]
  (let [c (.read reader 1)]
    (when (pos? c)
      (cond
        ;; ESC sequences
        (= c 27)
        (let [c2 (.read reader 50)]
          (when (pos? c2)
            (case c2
              ;; ESC [ A/B/C/D (Unix/ANSI)
              91 (let [c3 (.read reader 50)]
                   (case c3
                     65 :up
                     66 :down
                     67 :right
                     68 :left
                     nil))
              ;; ESC O A/B/C/D (Application mode / Windows)
              79 (let [c3 (.read reader 50)]
                   (case c3
                     65 :up
                     66 :down
                     67 :right
                     68 :left
                     nil))
              nil)))
        ;; Regular character
        :else (char c)))))

(defn -main [& _args]
  (let [terminal (-> (TerminalBuilder/builder)
                     (.system true)
                     (.build))
        reader (.reader terminal)
        display (Display. terminal false)
        term-size (.getSize terminal)]
    (println "Terminal size:" (.getColumns term-size) "x" (.getRows term-size))
    (println "Terminal type:" (.getType terminal))
    (println "Press any key to start...")
    (.read reader)
    (try
      ;; Enter raw mode for unbuffered input
      (.enterRawMode terminal)
      (clear-screen terminal)
      (render display terminal)

      ;; Game loop
      (loop [last-move (System/currentTimeMillis)
             running true]
        (when running
          (let [now (System/currentTimeMillis)
                elapsed (- now last-move)
                {:keys [alive?]} @state
                key (read-key reader)]

            ;; Handle input
            (cond
              ;; Quit
              (= key \q)
              (clear-screen terminal)

              ;; Restart when dead
              (and (not alive?) (= key \r))
              (do (restart-game terminal)
                  (clear-screen terminal)
                  (render display terminal)
                  (recur now true))

              ;; Direction change
              key
              (do (handle-key key)
                  (recur last-move true))

              ;; Move snake on timer
              (and alive? (>= elapsed 150))
              (do (move-snake terminal)
                  (render display terminal)
                  (recur now true))

              ;; Keep looping
              :else
              (do (Thread/sleep 10)
                  (recur last-move true))))))

      (finally
        (.close terminal)))))

#_(when (= *file* (System/getProperty "babashka.file"))
  (-main))

(-main)
