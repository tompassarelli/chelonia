(ns chelonia.main
  (:gen-class)
  (:require [chelonia.kernel :as k]
            [chelonia.fold :as fold]
            [chelonia.projections :as proj]
            [chelonia.import :as imp]
            [clojure.string :as str]
            [chelonia.rt :as rt]))

(defn- ^String title-of [idx ^String te]
  (let [t (k/one-i idx te "title")]
  (if (some? t) t "")))

(defn- ^String short-id [^String te]
  (if (str/starts-with? te "thread:") (subs te 7) te))

(defn- ^String trunc [^String s n]
  (if (> (count s) n) (str (subs s 0 (- n 1)) "…") s))

(defrecord LevItem [te score])

(defn levitem-te [r] (:te r))

(defn levitem-score [r] (:score r))

(defrecord NextItem [te score])

(defn nextitem-te [r] (:te r))

(defn nextitem-score [r] (:score r))

(defn cmd-import [^String threads-dir ^String log]
  (let [as (imp/load-corpus threads-dir)]
  (chelonia.rt/write-log log as)
  (println (str "imported -> " (count as) " claims -> " log))))

(defn cmd-ready [^String log]
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   rs (proj/ready idx)]
  (println (str "READY NOW — " (count rs)))
  (doseq [te rs]
  (println (str "  " (short-id te) "  " (trunc (title-of idx te) 56))))))

(defn cmd-blocked [^String log]
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   bs (proj/blocked idx)]
  (println (str "BLOCKED — " (count bs)))
  (doseq [te bs]
  (println (str "  " (short-id te) "  " (trunc (title-of idx te) 48) "  (waiting on " (count (proj/incomplete-deps idx te)) ")")))))

(defn cmd-leverage [^String log]
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   cands (filterv (fn [te] (not (k/terminal-i? idx te))) (k/thread-ids-i idx))
   items (filterv (fn [it] (> (:score it) 0)) (mapv (fn [te] (->LevItem te (proj/leverage-score idx te))) cands))
   ranked (vec (take 15 (sort-by (fn [it] (- 0 (:score it))) items)))]
  (println "TOP UNBLOCKERS — finishing this transitively frees the most stuck threads")
  (doseq [it ranked]
  (println (str "  unblocks " (:score it) "  " (short-id (:te it)) "  " (trunc (title-of idx (:te it)) 46))))))

(defn cmd-validate [^String log]
  (let [f (fold/fold (chelonia.rt/read-log log))
   claims (:claims f)
   problems (reduce (fn [acc te] (reduce (fn [a v] (conj a (str (short-id te) ": " v))) acc (k/violations claims te))) [] (k/thread-ids claims))]
  (if (empty? problems) (println (str "OK — " (count (k/thread-ids claims)) " threads, no violations.")) (do
  (doseq [p problems]
  (println (str "  " p)))
  (println (str "\n" (count problems) " violation(s)."))))))

(defn cmd-show [^String log ^String id]
  (let [f (fold/fold (chelonia.rt/read-log log))
   claims (:claims f)
   te (str "thread:" id)
   cs (k/q-by-l claims te)]
  (if (empty? cs) (println (str "no claims for " te)) (doseq [c cs]
  (println (str "  " (:p c) "  " (trunc (:r c) 80)))))))

(defn cmd-next [^String log]
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   today (chelonia.rt/today-iso)
   items (mapv (fn [te] (let [lev (proj/leverage-score idx te)
   doo (k/one-i idx te "do_on")
   urg (if (some? doo) (cond
  (chelonia.rt/str-lt? doo today) 5
  (= doo today) 3
  :else 0) 0)
   mom (if (= (k/one-i idx te "state") "active") 2 0)]
  (->NextItem te (+ (* 3 lev) (+ urg mom))))) (proj/ready idx))
   ranked (vec (take 12 (sort-by (fn [it] (- 0 (:score it))) items)))]
  (println (str "WHAT TO WORK ON — top picks (" today ")"))
  (doseq [it ranked]
  (println (str "  [" (:score it) "] " (short-id (:te it)) "  " (trunc (title-of idx (:te it)) 50))))))

(defn cmd-plate [^String log]
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   nonterm (filterv (fn [te] (not (k/terminal-i? idx te))) (k/thread-ids-i idx))]
  (println (str "ON YOUR PLATE — " (count nonterm) " open"))
  (doseq [st ["active" "ready" "draft"]]
  (let [grp (filterv (fn [te] (= (k/one-i idx te "state") st)) nonterm)]
  (if (not (empty? grp)) (do
  (println (str "\n" st " (" (count grp) ")"))
  (doseq [te grp]
  (println (str "  " (short-id te) "  " (trunc (title-of idx te) 52))))))))))

(defn cmd-set [^String log ^String id ^String pred ^String value]
  (let [f (fold/fold (chelonia.rt/read-log log))
   claims (:claims f)
   te (str "thread:" id)
   rv (if (or (= pred "depends_on") (= pred "part_of")) (str "thread:" value) value)
   cand (k/apply-assert claims (k/->Claim te pred rv))
   viol (k/violations cand te)]
  (if (not (empty? viol)) (println (str "REJECTED — " (str/join "; " viol))) (do
  (chelonia.rt/append-assertion log (fold/->Assertion (+ (:version f) 1) "assert" te pred rv))
  (println (str "ok — " id " " pred " = " rv " (v" (+ (:version f) 1) ")"))))))

(defn run [args ^String threads-dir ^String log]
  (let [cmd (if (empty? args) "" (first args))]
  (cond
  (= cmd "import") (cmd-import threads-dir log)
  (= cmd "ready") (cmd-ready log)
  (= cmd "blocked") (cmd-blocked log)
  (= cmd "leverage") (cmd-leverage log)
  (= cmd "next") (cmd-next log)
  (= cmd "plate") (cmd-plate log)
  (= cmd "validate") (cmd-validate log)
  (= cmd "show") (cmd-show log (if (> (count args) 1) (nth args 1) ""))
  (= cmd "set") (if (>= (count args) 4) (cmd-set log (nth args 1) (nth args 2) (nth args 3)) (println "usage: set <id> <pred> <value>"))
  :else (println "usage: import | ready | blocked | leverage | next | plate | validate | show <id> | set <id> <pred> <value>"))))

(defn -main [& args]
  (run (vec args) (chelonia.rt/threads-dir) (chelonia.rt/log-path)))
