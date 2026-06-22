;; demo_affordances.clj — the FREE affordances, each = the same fold op, re-pointed.
;;
;; Because state = fold(claims <= T) is a pure fn of (claim-set, T), moving T or restricting
;; the set gives time-travel / unwind-redo / fork for free. Tiny demos, not essays.
;;
;; usage: bb demo_affordances.clj
(require '[clojure.java.io :as io] '[clojure.string :as str])
(load-file (str (System/getProperty "user.dir") "/experiments/store-bakeoff/common.clj"))
(load-file (str (System/getProperty "user.dir") "/experiments/snapshot-boot/prototype.clj"))
(alias 'c 'common)

(def sdir "/dev/shm/snapboot-affordance")

;; Build a tiny, legible history: @e0 gets a title set 3 times (single-pred LWW), so its
;; value changes over HLC time — perfect for showing time-travel / unwind.
(defn build-history! []
  (c/fresh-dir! sdir)
  (let [w0 (c/mk-hlc-fn 0)
        edits [{:l "@e0" :p "title" :r "draft"}      ; T1
               {:l "@e0" :p "title" :r "reviewed"}   ; T2
               {:l "@e0" :p "title" :r "shipped"}    ; T3
               {:l "@e0" :p "owner" :r "tom"}]       ; T4
        claims (mapv (fn [e] (assoc e :id (w0) :op :assert :by "w0")) edits)]
    (with-open [out (java.io.FileOutputStream. (str sdir "/w0.log") true)]
      (doseq [cl claims] (.write out (.getBytes (str (pr-str cl) "\n") "UTF-8"))))
    claims))

(defn state-at
  "Boot the program AS OF HLC T: fold only claims with id <= T. The fold point IS the cursor."
  [claims t]
  (c/fold-state (filter #(<= (compare (:id %) t) 0) claims)))

(println "=== affordance demos — each is fold() re-pointed ===\n")
(def claims (build-history!))
(def ids (mapv :id claims))

;; ---------------------------------------------------------------------------
;; 1. TIME-TRAVEL — boot at an earlier HLC. Same fold, T restricted.
;; ---------------------------------------------------------------------------
(println "1. TIME-TRAVEL  (@e0 title over its 3 edits)")
(doseq [[label t] [["after edit 1" (ids 0)] ["after edit 2" (ids 1)] ["after edit 3" (ids 2)] ["HEAD" (last ids)]]]
  (println (format "   T=%-12s -> %s" label (get (state-at claims t) ["@e0" "title"]))))

;; ---------------------------------------------------------------------------
;; 2. UNWIND / REDO — move the fold cursor backward then forward. The log IS the undo stack.
;; ---------------------------------------------------------------------------
(println "\n2. UNWIND / REDO  (cursor over the log; no undo-stack needed)")
(let [cursor (atom (count ids))                              ; HEAD
      view #(state-at claims (get ids (dec @cursor) (last ids)))]
  (println "   start  @cursor" @cursor "-> title" (get (view) ["@e0" "title"]))
  (swap! cursor dec) (println "   unwind @cursor" @cursor "-> title" (get (view) ["@e0" "title"]))
  (swap! cursor dec) (println "   unwind @cursor" @cursor "-> title" (get (view) ["@e0" "title"]))
  (swap! cursor inc) (println "   redo   @cursor" @cursor "-> title" (get (view) ["@e0" "title"])))

;; ---------------------------------------------------------------------------
;; 3. FORK — snapshot at T, apply two divergent tails => two states sharing one image.
;; ---------------------------------------------------------------------------
(println "\n3. FORK  (snapshot @T2, diverge two ways — shared image, different tails)")
(let [t2 (ids 1)                                            ; fork point: after "reviewed"
      base (state-at claims t2)                            ; the shared materialized image
      w-a (c/mk-hlc-fn 0) w-b (c/mk-hlc-fn 1)
      tail-a [{:l "@e0" :p "title" :r "branch-A-final" :id (w-a) :op :assert :by "wa"}]
      tail-b [{:l "@e0" :p "title" :r "branch-B-final" :id (w-b) :op :assert :by "wb"}]
      state-a (fold-into (into {} base) tail-a)
      state-b (fold-into (into {} base) tail-b)]
  (println "   shared image title :" (get base ["@e0" "title"]))
  (println "   fork A title       :" (get state-a ["@e0" "title"]))
  (println "   fork B title       :" (get state-b ["@e0" "title"]))
  (println "   (both reuse the ONE snapshot image; only the tail differs)"))

(println "\nall affordances = the same pure fold, re-pointed. No bespoke undo/branch machinery.")
