;; set_tx_test.clj — regression for finding #17 (lost update on equal-tx).
;;
;; The cold `set` write path (fram.main/cmd-set) used to assign the new line's
;; tx from the fold it took at function ENTRY: tx = (:version f) + 1. Two cold
;; sets that both read the same base version (concurrent processes) therefore
;; both wrote tx = V+1 for the same single-valued (l,p). fold/keyed-latest
;; tie-breaks equal tx with a strict `>` (keep prev only if prev.tx is STRICTLY
;; greater), so on a tie the last-appended line silently wins and the other is
;; dropped — a lost update that max-tx can't even flag (both tx equal, version
;; high-water mark looks fine).
;;
;; The fix: cmd-set assigns tx = (fold/max-tx (read-log)) + 1 from a FRESH
;; re-read taken right BEFORE append (not the entry fold), so a `set` whose
;; append lands after a concurrent writer's line observes it and STRICTLY bumps
;; past it. Two cold sets can no longer collide on tx.
;;
;;   bb -cp out set_tx_test.clj            (uses fresh temp logs; no env needed)
(require '[fram.main :as main]
         '[fram.fold :as fold]
         '[fram.kernel :as k]
         '[fram.rt])

(def pass (atom true))
(defn check [label ok] (swap! pass #(and % ok))
  (println (str "  [" (if ok "PASS" "FAIL") "]  " label)))

(println "set_tx_test — finding #17: two cold sets never collide on tx")

;; ===========================================================================
;; PART 1 — the DISCRIMINATING test: a concurrent writer lands BETWEEN this
;; cmd-set's entry-fold and its append. Drives the REAL fram.main/cmd-set.
;; ===========================================================================
;; The race is: writer B folds the base (version 1), and before B appends,
;; writer A's line (tx 2) lands in the log. We reproduce A's mid-flight append
;; deterministically by redefining fram.rt/read-log to return the SHORT (pre-A)
;; log on B's FIRST read (the entry/validation fold) and the GROWN (post-A) log
;; on B's SECOND read.
;;   - OLD cmd-set reads the log ONCE and assigns tx = (:version f)+1 = 1+1 = 2,
;;     COLLIDING with A's already-landed tx 2 (silent lost update).
;;   - NEW cmd-set takes a SECOND fresh read before append; it sees A's line and
;;     assigns tx = max-tx+1 = 3, distinct from A. <-- this is what we assert.
;; A real log file backs the append (cmd-set's append goes through to disk).
(def log1 (str (System/getProperty "java.io.tmpdir") "/fram-set-tx-race-"
               (System/currentTimeMillis) ".log"))
;; on-disk reality: base @t title T (tx 1) + writer A's @t owner alice (tx 2).
(fram.rt/append-assertion log1 (fold/->Assertion 1 "assert" "@t" "title" "T" "seed"))
(fram.rt/append-assertion log1 (fold/->Assertion 2 "assert" "@t" "owner" "alice" "cli"))

(def short-log [(fold/->Assertion 1 "assert" "@t" "title" "T" "seed")])  ; B's stale entry view
(def reads (atom 0))
(def real-read-log fram.rt/read-log)
(with-redefs [fram.rt/read-log
              (fn [path]
                (let [n (swap! reads inc)]
                  ;; B's 1st read = stale base (no A yet); 2nd+ read = true on-disk log.
                  (if (= n 1) short-log (real-read-log path))))]
  (main/cmd-set log1 "t" "owner" "bob"))

(def owner-lines1 (filterv (fn [a] (and (= (:l a) "@t") (= (:p a) "owner")))
                           (real-read-log log1)))
(def bob-line (first (filterv (fn [a] (= (:r a) "bob")) owner-lines1)))
(check "writer B (cmd-set) appended its line under the race"
       (some? bob-line))
;; THE discriminating assertion: B's tx must NOT reuse A's already-landed tx 2.
;; OLD cmd-set assigns 2 (collision) -> this FAILS. NEW assigns 3 -> PASSES.
(check (str "B's tx did NOT collide with A's already-landed tx 2 (got tx "
            (:tx bob-line) ", expected > 2)")
       (and (some? bob-line) (> (:tx bob-line) 2)))
(check "both A and B are present as distinct log lines with DISTINCT tx (neither silently dropped)"
       (and (= 2 (count owner-lines1)) (= 2 (count (set (mapv :tx owner-lines1))))))
;; fold stays deterministic latest-wins: highest tx (B/bob) is live, A superseded.
(check "fold resolves @t owner deterministically to the higher-tx writer (bob)"
       (= (k/one (:claims (fold/fold (real-read-log log1))) "@t" "owner") "bob"))

;; ===========================================================================
;; PART 2 — the SHIPPED cmd-set assigns fresh, strictly-increasing tx (no redef).
;; ===========================================================================
;; Two ordered sets to one single-valued key must land distinct, increasing tx
;; and the loser is superseded (not dropped on an equal-tx tie).
(def log2 (str (System/getProperty "java.io.tmpdir") "/fram-set-tx-cmd-"
               (System/currentTimeMillis) ".log"))
(main/cmd-set log2 "t" "owner" "alice")
(main/cmd-set log2 "t" "owner" "bob")
(def owner-lines2 (filterv (fn [a] (and (= (:l a) "@t") (= (:p a) "owner")))
                           (fram.rt/read-log log2)))
(check "cmd-set x2: both writes present with DISTINCT tx"
       (and (= 2 (count owner-lines2)) (= 2 (count (set (mapv :tx owner-lines2))))))
(check "cmd-set x2: tx strictly increasing across the two sets"
       (apply < (mapv :tx owner-lines2)))
(check "cmd-set x2: fold resolves @t owner to bob (latest), alice superseded"
       (= (k/one (:claims (fold/fold (fram.rt/read-log log2))) "@t" "owner") "bob"))

(if @pass
  (println "\nfinding #17 regression: PASS")
  (do (println "\nfinding #17 regression: FAIL") (System/exit 1)))
