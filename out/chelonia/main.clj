(ns chelonia.main
  (:gen-class)
  (:require [chelonia.kernel :as k]
            [chelonia.fold :as fold]
            [chelonia.projections :as proj]
            [chelonia.import :as imp]
            [chelonia.export :as exp]
            [chelonia.audit :as audit]
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

(defrecord AgendaItem [te do_on])

(defn agendaitem-te [r] (:te r))

(defn agendaitem-do_on [r] (:do_on r))

(defn cmd-import [^String threads-dir ^String log]
  (let [as (imp/load-corpus threads-dir)]
  (chelonia.rt/write-log log as)
  (println (str "imported -> " (count as) " claims -> " log))))

(defn- ^String claim-sig [c]
  (str (:l c) "|" (:p c) "|" (:r c)))

(defn- sig-set [claims]
  (vec (sort (mapv claim-sig claims))))

(defn cmd-export [^String threads-dir ^String log ^String out-dir]
  (let [log-claims (:claims (fold/fold (chelonia.rt/read-log log)))
   file-claims (:claims (fold/fold (imp/load-corpus threads-dir)))]
  (if (not (= (sig-set log-claims) (sig-set file-claims))) (println (str "REFUSING export: threads/ has changes not in the log " "(concurrent edits?). Run `import` first, or write via the coordinator.")) (let [idx (k/build-index log-claims)
   tes (k/thread-ids-i idx)]
  (chelonia.rt/ensure-dir out-dir)
  (doseq [te tes]
  (chelonia.rt/spit-file (str out-dir "/" (exp/thread-filename idx te)) (exp/thread-md idx te)))
  (println (str "exported " (count tes) " threads -> " out-dir))))))

(defn cmd-audit [^String log]
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   td (audit/tag-drift idx)
   rd (audit/repo-drift idx)
   lt (audit/long-tail-tags idx)]
  (println (str "TAG DRIFT — " (count td) " normalized-collision group(s):"))
  (doseq [g td]
  (println (str "  " (:norm g) ": " (str/join ", " (mapv (fn [f] (subs f 4)) (:forms g))))))
  (println (str "REPO DRIFT — " (count rd) " group(s):"))
  (doseq [g rd]
  (println (str "  " (:norm g) ": " (str/join ", " (mapv (fn [f] (subs f 5)) (:forms g))))))
  (println (str "LONG-TAIL TAGS (used once) — " (count lt) ":"))
  (println (str "  " (str/join " " (mapv (fn [t] (subs t 4)) lt))))))

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
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   problems (reduce (fn [acc te] (reduce (fn [a v] (conj a (str (short-id te) ": " v))) acc (k/violations-i idx te))) [] (k/thread-ids-i idx))]
  (if (empty? problems) (println (str "OK — " (count (k/thread-ids-i idx)) " threads, no violations.")) (do
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

(defn cmd-agenda [^String log]
  (let [idx (k/build-index (:claims (fold/fold (chelonia.rt/read-log log))))
   today (chelonia.rt/today-iso)
   cands (filterv (fn [te] (and (not (k/terminal-i? idx te)) (some? (k/one-i idx te "do_on")))) (k/thread-ids-i idx))
   items (mapv (fn [te] (->AgendaItem te (let [d (k/one-i idx te "do_on")]
  (if (some? d) d "")))) cands)
   overdue (vec (sort-by (fn [it] (:do_on it)) (filterv (fn [it] (chelonia.rt/str-lt? (:do_on it) today)) items)))
   todayb (filterv (fn [it] (= (:do_on it) today)) items)
   upcoming (vec (sort-by (fn [it] (:do_on it)) (filterv (fn [it] (chelonia.rt/str-lt? today (:do_on it))) items)))]
  (println (str "AGENDA — " today))
  (println (str "OVERDUE (" (count overdue) ")"))
  (doseq [it overdue]
  (println (str "  " (:do_on it) "  " (short-id (:te it)) "  " (trunc (title-of idx (:te it)) 44))))
  (println (str "TODAY (" (count todayb) ")"))
  (doseq [it todayb]
  (println (str "  " (:do_on it) "  " (short-id (:te it)) "  " (trunc (title-of idx (:te it)) 44))))
  (println (str "UPCOMING (" (count upcoming) ")"))
  (doseq [it upcoming]
  (println (str "  " (:do_on it) "  " (short-id (:te it)) "  " (trunc (title-of idx (:te it)) 44))))))

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
  (chelonia.rt/append-assertion log (fold/->Assertion (+ (:version f) 1) "assert" te pred rv "cli"))
  (println (str "ok — " id " " pred " = " rv " (v" (+ (:version f) 1) ")"))))))

(defn- claims->assertions [claims ^String frame]
  (loop [cs claims
   i 1
   acc []]
  (if (empty? cs) acc (let [c (first cs)]
  (recur (rest cs) (+ i 1) (conj acc (fold/->Assertion i "assert" (:l c) (:p c) (:r c) frame)))))))

(defn cmd-merge [^String log ^String from ^String to]
  (let [claims (:claims (fold/fold (chelonia.rt/read-log log)))
   rewritten (mapv (fn [c] (k/->Claim (if (= (:l c) from) to (:l c)) (:p c) (if (= (:r c) from) to (:r c)))) claims)
   withrec (conj rewritten (k/->Claim from "merged_into" to))
   deduped (:claims (fold/fold (claims->assertions withrec "merge")))]
  (chelonia.rt/write-log log (claims->assertions deduped "merge"))
  (println (str "merged " from " -> " to "  (" (count claims) " claims -> " (count deduped) ")"))))

(defn- ^String tell-once [port ^String op ^String te ^String pred ^String rv]
  (let [v (chelonia.rt/coord-version port)]
  (if (< v 0) "nodaemon" (if (= op "assert") (chelonia.rt/coord-assert port te pred rv v) (chelonia.rt/coord-retract port te pred rv v)))))

(defn- ^String tell-retry [port ^String op ^String te ^String pred ^String rv tries]
  (let [resp (tell-once port op te pred rv)]
  (if (and (= resp "conflict") (> tries 0)) (tell-retry port op te pred rv (- tries 1)) resp)))

(defn cmd-tell [^String op ^String id ^String pred ^String value]
  (let [te (str "thread:" id)
   rv (if (or (= pred "depends_on") (= pred "part_of")) (str "thread:" value) value)
   resp (tell-retry (chelonia.rt/coord-port) op te pred rv 5)]
  (cond
  (= resp "nodaemon") (println "no coordinator on 127.0.0.1:7977 — run `chelonia serve`, or use `set` (single-writer)")
  (= resp "conflict") (println "rejected: write conflict after retries (another agent is racing this id+pred)")
  (str/starts-with? resp "ok:") (println (str "committed via coordinator (v" (subs resp 3) "): " id " " pred " = " rv))
  :else (println (str "REJECTED by coordinator: " resp)))))

(defn cmd-doctor [^String threads-dir ^String log]
  (let [port (chelonia.rt/coord-port)
   status (chelonia.rt/coord-status port)
   up (not (= status "down"))
   serving (str/includes? status log)
   log-claims (:claims (fold/fold (chelonia.rt/read-log log)))
   file-claims (:claims (fold/fold (imp/load-corpus threads-dir)))
   synced (= (sig-set log-claims) (sig-set file-claims))]
  (println "chelonia doctor")
  (if up (do
  (println (str "  [ok]    coordinator UP on 127.0.0.1:" port))
  (if serving (println "  [ok]    serving the canonical log") (println (str "  [WARN]  daemon is NOT serving " log " — status: " status)))) (println (str "  [DOWN]  no coordinator on 127.0.0.1:" port " — writes won't serialize. Run `chelonia-up`.")))
  (if synced (println "  [ok]    files <-> claim log in sync") (println "  [WARN]  files diverge from the log — run `import` to absorb file edits before any `export`"))
  (if (and up (and serving synced)) (println "  => healthy: tell/untell + warm reads are safe") (println "  => DEGRADED: fix the warnings above"))))

(defn run [args ^String threads-dir ^String log]
  (let [cmd (if (empty? args) "" (first args))]
  (cond
  (= cmd "import") (cmd-import threads-dir log)
  (= cmd "export") (if (> (count args) 1) (cmd-export threads-dir log (nth args 1)) (println "usage: export <out-dir>"))
  (= cmd "ready") (cmd-ready log)
  (= cmd "blocked") (cmd-blocked log)
  (= cmd "leverage") (cmd-leverage log)
  (= cmd "next") (cmd-next log)
  (= cmd "agenda") (cmd-agenda log)
  (= cmd "plate") (cmd-plate log)
  (= cmd "audit") (cmd-audit log)
  (= cmd "doctor") (cmd-doctor threads-dir log)
  (= cmd "validate") (cmd-validate log)
  (= cmd "show") (cmd-show log (if (> (count args) 1) (nth args 1) ""))
  (= cmd "set") (if (>= (count args) 4) (cmd-set log (nth args 1) (nth args 2) (nth args 3)) (println "usage: set <id> <pred> <value>"))
  (= cmd "merge") (if (>= (count args) 3) (cmd-merge log (nth args 1) (nth args 2)) (println "usage: merge <from-entity> <to-entity>"))
  (= cmd "tell") (if (>= (count args) 4) (cmd-tell "assert" (nth args 1) (nth args 2) (nth args 3)) (println "usage: tell <id> <pred> <value>"))
  (= cmd "untell") (if (>= (count args) 4) (cmd-tell "retract" (nth args 1) (nth args 2) (nth args 3)) (println "usage: untell <id> <pred> <value>"))
  :else (println "usage: import | export <out-dir> | ready | blocked | leverage | next | agenda | plate | audit | doctor | validate | show <id> | set <id> <pred> <value> | tell <id> <pred> <value> | untell <id> <pred> <value> | merge <from> <to>"))))

(defn -main [& args]
  (run (vec args) (chelonia.rt/threads-dir) (chelonia.rt/log-path)))
