;; cnf_cardinality_test.clj — #2 receipt: cardinality is GRAPH-SOURCED.
;; Proves the cold fold keys single vs multi from (P "cardinality" V) claims in the
;; log ITSELF — no env FRAM_SINGLE_VALUED, no hardcoded kernel list — and that the
;; migration tool makes a non-self-describing log self-describing. The RED->GREEN is
;; cases 1 vs 2: identical owner data, the lone cardinality claim flips the keying.
;;   bb -cp out cnf_cardinality_test.clj
(require '[fram.fold :as fold]
         '[fram.migrate-cardinality :as mc]
         '[fram.rt])

(def pass (atom true))
(defn check [label ok] (swap! pass #(and % ok))
  (println (str "  [" (if ok "PASS" "FAIL") "]  " label)))

(println "cnf_cardinality_test — #2: cardinality is graph-sourced")

;; live r-values for (@t,owner) after folding a vec of assertions.
(defn owner-vals [asserts]
  (->> (:claims (fold/fold (vec asserts)))
       (filter #(and (= (:l %) "@t") (= (:p %) "owner")))
       (mapv :r)))

;; --- 1. WITHOUT a cardinality claim, owner ACCUMULATES (default multi) ---------
(def no-card
  [(fold/->Assertion 1 "assert" "@t" "owner" "alice" "cli")
   (fold/->Assertion 2 "assert" "@t" "owner" "bob" "cli")])
(check "no cardinality claim -> owner is MULTI (both values kept)"
       (= #{"alice" "bob"} (set (owner-vals no-card))))

;; --- 2. WITH (owner cardinality single), owner SUPERSEDES (latest wins) --------
(def with-card
  [(fold/->Assertion 0 "assert" "owner" "cardinality" "single" "migrate")
   (fold/->Assertion 1 "assert" "@t" "owner" "alice" "cli")
   (fold/->Assertion 2 "assert" "@t" "owner" "bob" "cli")])
(check "(owner cardinality single) -> owner is SINGLE (bob wins, alice superseded)"
       (= ["bob"] (owner-vals with-card)))

;; --- 3. a RETRACTED cardinality claim reverts the pred to multi ----------------
(def card-then-retract
  [(fold/->Assertion 0 "assert"  "owner" "cardinality" "single" "migrate")
   (fold/->Assertion 1 "assert"  "@t" "owner" "alice" "cli")
   (fold/->Assertion 2 "assert"  "@t" "owner" "bob" "cli")
   (fold/->Assertion 3 "retract" "owner" "cardinality" "single" "migrate")])
(check "retracting the cardinality claim reverts owner to MULTI"
       (= #{"alice" "bob"} (set (owner-vals card-then-retract))))

;; --- 4. the migration tool's single-set heuristic (known vocab + emoji_) -------
(check "single-pred? owner (known vocab)"   (mc/single-pred? "owner"))
(check "single-pred? emoji_blocked (prefix)" (mc/single-pred? "emoji_blocked"))
(check "single-pred? tag is MULTI"          (not (mc/single-pred? "tag")))

;; --- 5. migrate-log! makes a real on-disk log self-describing ------------------
(def log (str (System/getProperty "java.io.tmpdir") "/fram-card-mig-"
              (System/currentTimeMillis) ".log"))
(fram.rt/append-assertion log (fold/->Assertion 1 "assert" "@t" "owner" "alice" "cli"))
(fram.rt/append-assertion log (fold/->Assertion 2 "assert" "@t" "owner" "bob" "cli"))
(check "before migrate: owner MULTI on disk"
       (= #{"alice" "bob"} (set (owner-vals (fram.rt/read-log log)))))
(def added (mc/migrate-log! log))
(check "migrate-log! added the owner cardinality claim" (>= added 1))
(check "after migrate: owner SINGLE on disk (bob wins)"
       (= ["bob"] (owner-vals (fram.rt/read-log log))))
;; idempotent at the live view: cardinality is itself single, so re-running collapses.
(mc/migrate-log! log)
(check "after 2nd migrate: still SINGLE (one live owner)"
       (= ["bob"] (owner-vals (fram.rt/read-log log))))

(if @pass
  (println "\n#2 cardinality receipt: PASS")
  (do (println "\n#2 cardinality receipt: FAIL") (System/exit 1)))
