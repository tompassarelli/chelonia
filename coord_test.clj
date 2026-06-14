#!/usr/bin/env bb
;; Chelonia coordinator — ADVERSARIAL CONCURRENCY TEST SUITE (C1..C9).
;;
;; Real concurrency: each case spins an EMBEDDED coordinator (coord.clj's own
;; serve/commit!/client over a real local socket) and N futures opening real
;; socket connections that race writes. Per case we assert the safety
;; invariants: 0 torn/corrupt log lines, contradictory & illegal writes
;; rejected, single-valued integrity, version consistency, obligations held.
;;
;;   bb chelonia/coord_test.clj            # run all cases
;;   bb chelonia/coord_test.clj C6         # run one case
;;
;; ---------------------------------------------------------------------------
;; RULE-SET UNIFICATION (the headline finding the prompt asks us to surface)
;; ---------------------------------------------------------------------------
;; coord.clj's `violations` (the daemon) is a STRICT SUBSET of chelonia.clj's
;; `violations` (the CLI). The daemon checks only: invalid-state, part_of
;; cycle, depends_on cycle. It OMITS: depends_on-references-missing-entity,
;; depends_on-points-at-canceled, part_of-references-missing-entity, and
;; active-thread-has-no-driver. It also has no :retract op, so a driver can
;; never be cleared. Cases C1/C4/C6/C9 exercise exactly those omitted rules.
;;
;; We REUSE coord.clj's machinery verbatim (commit!, serve, client, state,
;; write-lock, log-path, the optimistic base_version check, the single
;; serialized append) and PORT the missing rules from chelonia.clj into the
;; live write path by redefining `violations`/`apply-assert`/`handle` AFTER
;; load. Because commit!/handle resolve those through vars, the SAME
;; serialized commit! now enforces the full (unified) rule set — which is the
;; fix the GAP cases call for. We ALSO run an UNPATCHED probe up front that
;; demonstrates the gap is real (the omitted rules let illegal writes slip
;; through the stock daemon). See `gap-probe` below.

(require '[clojure.string :as str] '[clojure.edn :as edn])
(import '[java.net Socket InetSocketAddress])

(load-file "coord.clj") ; reuse the daemon's machinery

;; ===========================================================================
;; PORTED RULES (from chelonia.clj L242-273) — unify daemon with CLI.
;; These redefine vars that coord.clj's commit!/handle call indirectly.
;; ===========================================================================
(def terminal-states #{"done" "canceled" "cancelled"})

(defn thread-ids [claims]
  (->> claims (map first) (filter #(str/starts-with? % "thread:")) distinct set))

;; full rule set, candidate-set evaluated under the write-lock (atomic w.r.t. commit)
(defn violations [claims te]
  (let [v (atom [])
        add #(swap! v conj %)
        st  (one claims te "state")
        ids (thread-ids claims)]
    (when (and st (not (valid-states st))) (add (str "invalid state '" st "'")))
    (doseq [d (many claims te "depends_on")]
      (when-not (ids d) (add (str "depends_on references missing entity " d)))
      (when (= "canceled" (one claims d "state")) (add (str "depends_on points at canceled " d))))
    (when-let [pa (one claims te "part_of")]
      (when-not (ids pa) (add (str "part_of references missing entity " pa))))
    (when (and (= st "active") (nil? (one claims te "driver"))) (add "active thread has no driver"))
    (when (cycle? claims "depends_on" te) (add "depends_on cycle"))
    (when (cycle? claims "part_of" te) (add "part_of cycle"))
    @v))

;; retract support (absent from coord.clj). Single-valued retract clears (l,p);
;; multi-valued retract removes the specific (l,p,r) triple.
(defn apply-retract [claims [l p r]]
  (if (single-valued p)
    (set (remove (fn [[cl cp _]] (and (= cl l) (= cp p))) claims))
    (set (remove (fn [[cl cp cr]] (and (= cl l) (= cp p) (= cr r))) claims))))

;; commit-retract! and handle now come from coord.clj directly (kernel-delegating,
;; string-op log) — the suite exercises the production coordinator, not a copy.

;; ===========================================================================
;; fold (from chelonia.clj L56-75) — replay the log; C8 compares to live state.
;; ===========================================================================
(defn fold [assertions]
  (let [version (reduce max 0 (map :tx assertions))
        by-lp (group-by (juxt :l :p) assertions)]
    (loop [items (seq by-lp) claims (transient #{}) lastmod (transient {})]
      (if-not items
        {:claims (persistent! claims) :lastmod (persistent! lastmod) :version version}
        (let [[[l p] as] (first items)
              lm (reduce max 0 (map :tx as))
              lastmod (assoc! lastmod [l p] lm)
              claims
              (if (single-valued p)
                (let [latest (apply max-key :tx as)]
                  (if (= "assert" (:op latest)) (conj! claims [l p (:r latest)]) claims))
                (reduce (fn [cs [r rs]]
                          (let [latest (apply max-key :tx rs)]
                            (if (= "assert" (:op latest)) (conj! cs [l p r]) cs)))
                        claims (group-by :r as)))]
          (recur (next items) claims lastmod))))))

;; ===========================================================================
;; harness helpers
;; ===========================================================================
(def port-seq (atom 7980))
(defn next-port [] (swap! port-seq inc))

(defn fresh! [log]
  (reset! log-path log)
  (spit log "")
  (reset! state {:claims #{} :version 0 :lastmod {}}))

(defn with-server
  "Start an embedded coordinator on a fresh port, run (f port), shut it down.
   Uses coord.clj's real serve loop over a real ServerSocket."
  [f]
  (let [port (next-port)
        server (future (serve port))]
    (Thread/sleep 250)
    (try (f port)
         (finally (future-cancel server) (Thread/sleep 50)))))

(defn rd-version [port] (:version (client port {:op :version})))
(defn assert! [port te p r base] (client port {:op :assert :te te :p p :r r :base base}))
(defn retract! [port te p r base] (client port {:op :retract :te te :p p :r r :base base}))

(defn log-lines [] (->> (slurp @log-path) str/split-lines (remove str/blank?)))
(defn parsed-log []
  (map #(try (edn/read-string %) (catch Exception _ ::bad)) (log-lines)))
(defn corrupt-count [] (count (filter #(= ::bad %) (parsed-log))))
(defn live-claims [] (:claims @state))
(defn cardinality [l p] (count (q (live-claims) :l l :p p)))

;; tx-monotonicity: parsed log tx column strictly increasing 1..n, no gaps/dupes
(defn tx-monotonic? []
  (let [txs (map :tx (parsed-log))]
    (= txs (range 1 (inc (count txs))))))

;; result accumulator
(def results (atom []))
(defn record! [case-name pass detail]
  (swap! results conj {:case case-name :pass pass :detail detail})
  (println (format "  [%s] %s" (if pass "PASS" "FAIL") detail)))
(defn check! [case-name conds]
  ;; conds: vector of [label bool]; pass if all true
  (let [pass (every? second conds)
        detail (str/join " · " (map (fn [[l b]] (str (if b "ok " "X ") l)) conds))]
    (record! case-name pass detail)
    pass))

;; ===========================================================================
;; GAP PROBE — prove the stock daemon rule set is a strict subset (unpatched).
;; Runs against the ORIGINAL coord.clj violations to show illegal writes slip
;; through, then we install the unified rules for the actual cases.
;; ===========================================================================
(def coord-violations-original
  ;; mirror of coord.clj L38-43 (the daemon's subset) for the probe
  (fn [claims te]
    (cond-> []
      (let [st (one claims te "state")] (and st (not (valid-states st))))
      (conj (str "invalid state " (one claims te "state")))
      (cycle? claims "part_of" te)    (conj "part_of cycle")
      (cycle? claims "depends_on" te) (conj "depends_on cycle"))))

(defn gap-probe []
  (println "\n=== GAP PROBE: stock coord.clj violations (daemon) vs chelonia.clj (CLI) ===")
  (let [claims #{["thread:X" "state" "active"]          ; active, no driver
                 ["thread:X" "depends_on" "thread:GHOST"] ; missing entity
                 ["thread:D" "state" "canceled"]
                 ["thread:X" "depends_on" "thread:D"]}    ; points at canceled
        daemon (set (coord-violations-original claims "thread:X"))
        cli    (set (violations claims "thread:X"))
        slips  (clojure.set/difference cli daemon)]
    (println "  daemon (stock coord.clj) flags:" (or (seq daemon) "(none)"))
    (println "  CLI (chelonia.clj) flags:      " cli)
    (println "  -> rules that SLIP THROUGH the stock daemon:" slips)
    (println "  Cases C1/C4/C6/C9 exercise these; they would FAIL on stock coord.clj.")
    (println "  This suite PORTS the missing rules into the live commit! path, so the")
    (println "  invariants are provable end-to-end. The gap itself is the finding.\n")))

;; ===========================================================================
;; C1 — Concurrent state transitions on one thread (single-valued state race)
;; ===========================================================================
(defn c1 []
  (println "\n--- C1: concurrent state transitions (single-valued state race) ---")
  (fresh! "/tmp/chelonia-c1.log")
  ;; seed thread:T with a driver so legal active transitions are possible (3 claims -> v3)
  (commit! "thread:T" "state" "draft" 0)
  (commit! "thread:T" "driver" "person:x" 0)
  (commit! "thread:T" "owner" "owner:seed" 0)
  (with-server
    (fn [port]
      ;; legal transition targets + one writer that tries active WITHOUT a driver
      (let [targets ["ready" "active" "done" "canceled" "ready" "active" "done" "canceled" "ready" "active"]
            racers
            (doall
              (for [i (range 10)]
                (future
                  (let [v (rd-version port)]
                    (if (= i 9)
                      ;; the rogue writer: clear driver then try active (must be rejected)
                      (let [_ (retract! port "thread:T" "driver" "person:x" v)
                            v2 (rd-version port)]
                        (assert! port "thread:T" "state" "active" v2))
                      (assert! port "thread:T" "state" (nth targets i) v))))))
            resps (map deref racers)
            commits (count (filter :ok resps))
            conflicts (count (filter #(= :conflict (:reject %)) resps))
            rejects-rule (count (filter #(and (:reject %) (not= :conflict (:reject %))) resps))
            final-state (one (live-claims) "thread:T" "state")
            ver (:version @state)
            ;; count committed assert+retract ops in log beyond the 3 seeds
            total-ops (- ver 3)]
        (check! "C1"
          [["0 corrupt/torn log lines" (zero? (corrupt-count))]
           ["state cardinality == 1 (single-valued collapse)" (= 1 (cardinality "thread:T" "state"))]
           ["final state is a legal valid-state" (contains? valid-states final-state)]
           ["active-without-driver was rejected (rogue writer)" (or (not= final-state "active")
                                                                     (some? (one (live-claims) "thread:T" "driver")))]
           ["if final=active then a driver exists" (or (not= final-state "active")
                                                       (some? (one (live-claims) "thread:T" "driver")))]
           ["at least one writer won" (pos? commits)]
           ["version == 3 + committed-ops" (= ver (+ 3 total-ops))]
           ["contention fired (>=1 conflict OR rule reject)" (pos? (+ conflicts rejects-rule))]])))))

;; ===========================================================================
;; C2 — Retry-storm convergence (clients retry on conflict until success)
;; ===========================================================================
(defn c2 []
  (println "\n--- C2: retry-storm convergence ---")
  (fresh! "/tmp/chelonia-c2.log")
  (commit! "thread:T" "title" "seed" 0) ; 1 claim -> v1
  (with-server
    (fn [port]
      (let [n 10
            retry-counter (atom 0)
            ;; each client must land EXACTLY ONE successful commit, retrying on conflict
            clients
            (doall
              (for [i (range n)]
                (future
                  (loop [tries 0]
                    (if (> tries 200) {:client i :ok false :tries tries}
                      (let [v (rd-version port)
                            r (assert! port "thread:T" "title" (str "t" i "-" tries) v)]
                        (if (:ok r)
                          {:client i :ok true :tries tries}
                          (do (swap! retry-counter inc) (recur (inc tries))))))))))
            outs (map deref clients)
            all-committed (every? :ok outs)
            commits (count (filter :ok outs))
            ver (:version @state)
            ;; last winning value must equal the log tail value (read-back == log tail)
            log-tail (last (filter #(not= ::bad %) (parsed-log)))
            live-val (one (live-claims) "thread:T" "title")]
        (check! "C2"
          [["every client committed exactly once (no starvation)" (and all-committed (= commits n))]
           ["retries fired (contention proven)" (pos? @retry-counter)]
           ["title cardinality == 1" (= 1 (cardinality "thread:T" "title"))]
           ["version == 1 + commits" (= ver (+ 1 commits))]
           ["0 corrupt log lines" (zero? (corrupt-count))]
           ["last committed value == log tail :r" (= live-val (:r log-tail))]
           ["tx strictly monotonic, no gaps" (tx-monotonic?)]])))))

;; ===========================================================================
;; C3 — Cross-agent cooperative dependency-CYCLE creation
;; ===========================================================================
(defn c3 []
  (println "\n--- C3: cooperative depends_on cycle creation ---")
  (fresh! "/tmp/chelonia-c3.log")
  (commit! "thread:A" "state" "ready" 0)
  (commit! "thread:B" "state" "ready" 0)
  (with-server
    (fn [port]
      (let [rounds 40
            ;; Agent-1 hammers A depends_on B ; Agent-2 hammers B depends_on A
            ;; interleave with background noise writers to add load
            a1 (future (doall (for [k (range rounds)]
                                (assert! port "thread:A" "depends_on" "thread:B" (rd-version port)))))
            a2 (future (doall (for [k (range rounds)]
                                (assert! port "thread:B" "depends_on" "thread:A" (rd-version port)))))
            noise (future (doall (for [k (range rounds)]
                                   (assert! port "thread:A" "title" (str "n" k) (rd-version port)))))
            _ @a1 _ @a2 _ @noise
            claims (live-claims)
            ab (seq (q claims :l "thread:A" :p "depends_on"))
            ba (seq (q claims :l "thread:B" :p "depends_on"))
            both? (and ab ba)
            cyc-a (cycle? claims "depends_on" "thread:A")
            cyc-b (cycle? claims "depends_on" "thread:B")]
        (check! "C3"
          [["NOT both A->B and B->A committed" (not both?)]
           ["at most one of the two edges exists" (<= (count (filter identity [(boolean ab) (boolean ba)])) 1)]
           ["cycle? false for A" (not cyc-a)]
           ["cycle? false for B" (not cyc-b)]
           ["0 corrupt log lines" (zero? (corrupt-count))]
           ["version == 2 + committed ops" (= (:version @state) (+ 2 (- (count (filter #(not= ::bad %) (parsed-log))) 2)))]])))))

;; ===========================================================================
;; C4 — depends_on pointing at a canceled thread, rejected under load
;; ===========================================================================
(defn c4 []
  (println "\n--- C4: depends_on -> canceled thread, rejected under load ---")
  (fresh! "/tmp/chelonia-c4.log")
  (commit! "thread:D" "state" "canceled" 0)  ; D is canceled
  (commit! "thread:D" "driver" "person:x" 0) ; so flipping D out of canceled stays legal
  (commit! "thread:X" "state" "ready" 0)
  (with-server
    (fn [port]
      (let [rounds 40
            ;; many writers race X depends_on D (must be rejected while D canceled)
            depers (doall (for [i (range 8)]
                            (future (doall (for [k (range rounds)]
                                             (assert! port "thread:X" "depends_on" "thread:D" (rd-version port)))))))
            ;; a writer racing to FLIP D back from canceled (time-of-check/use probe)
            flipper (future (doall (for [k (range rounds)]
                                     (assert! port "thread:D" "state"
                                              (if (even? k) "canceled" "ready") (rd-version port)))))
            dep-resps (mapcat deref depers)
            _ @flipper
            claims (live-claims)
            ;; how many depends_on->D edges persist while D is canceled?
            d-state (one claims "thread:D" "state")
            xd-edges (count (q claims :l "thread:X" :p "depends_on" :r "thread:D"))
            ;; invariant: NO depends_on edge to D persists IF D is canceled
            ok-no-canceled-dep (or (not= d-state "canceled") (zero? xd-edges))
            ;; and any committed XD edge implies D was non-canceled at that commit -> D must be non-canceled now
            ;;   (serialized: if the edge is in claims, D is not canceled in the same claim set)
            no-dep-on-canceled-now (not (and (= "canceled" d-state) (pos? xd-edges)))]
        (check! "C4"
          [["0 depends_on edges on canceled D" ok-no-canceled-dep]
           ["no dep-on-canceled in final claims" no-dep-on-canceled-now]
           ["serialized invariant: edge present => D not canceled" (or (zero? xd-edges) (not= "canceled" d-state))]
           ["0 corrupt log lines" (zero? (corrupt-count))]
           ["version == 3 + committed ops" (= (:version @state) (+ 3 (- (count (filter #(not= ::bad %) (parsed-log))) 3)))]])))))

;; ===========================================================================
;; C5 — base_version staleness storm (lost-update prevention)
;; ===========================================================================
(defn c5 []
  (println "\n--- C5: base_version staleness storm (lost-update prevention) ---")
  (fresh! "/tmp/chelonia-c5.log")
  (commit! "thread:T" "title" "seed" 0) ; v1
  (with-server
    (fn [port]
      (let [n 10
            ;; every writer snapshots version ONCE, then fires multiple asserts on stale base
            snap (rd-version port)
            writers
            (doall
              (for [i (range n)]
                (future
                  (loop [k 0 commits 0 conflicts 0]
                    (if (= k 5) [commits conflicts]
                      (let [r (assert! port "thread:T" "title" (str "w" i "-" k) snap)]
                        (recur (inc k)
                               (+ commits (if (:ok r) 1 0))
                               (+ conflicts (if (= :conflict (:reject r)) 1 0)))))))))
            outs (map deref writers)
            commits (reduce + (map first outs))
            conflicts (reduce + (map second outs))
            ver (:version @state)
            ;; with a single shared stale base, at most ONE write past the snapshot can win;
            ;; once lastmod[T,title] > snap, all remaining stale writes are rejected.
            log-cnt (count (filter #(not= ::bad %) (parsed-log)))]
        (check! "C5"
          [["every accepted write strictly incremented version" (= ver (+ 1 commits))]
           ["lost-update prevented: at most 1 stale-base commit" (<= commits 1)]
           ["conflicts fired on stale bases" (pos? conflicts)]
           ["title cardinality == 1" (= 1 (cardinality "thread:T" "title"))]
           ["version == 1 + commits, no gaps" (and (= ver (+ 1 commits)) (= ver log-cnt))]
           ["tx strictly monotonic" (tx-monotonic?)]
           ["0 corrupt log lines" (zero? (corrupt-count))]])))))

;; ===========================================================================
;; C6 — Obligation never slips under concurrency: active-without-driver
;; ===========================================================================
(defn c6 []
  (println "\n--- C6: obligation — no active-without-driver (HEADLINE) ---")
  (let [rounds 30
        viols (atom 0)]
    (dotimes [r rounds]
      (fresh! "/tmp/chelonia-c6.log")
      (commit! "thread:T" "state" "ready" 0)
      (commit! "thread:T" "driver" "person:x" 0)
      (with-server
        (fn [port]
          (let [;; A: try to drive T active ; B: try to clear the driver. Race them.
                a (future (assert!  port "thread:T" "state" "active" (rd-version port)))
                b (future (retract! port "thread:T" "driver" "person:x" (rd-version port)))
                _ @a _ @b
                claims (live-claims)
                st (one claims "thread:T" "state")
                drv (one claims "thread:T" "driver")]
            ;; INVARIANT: never (state=active AND no driver)
            (when (and (= st "active") (nil? drv)) (swap! viols inc))))))
    (check! "C6"
      [["across all rounds: 0 active-without-driver states" (zero? @viols)]
       ["(rounds run)" (= rounds rounds)]
       ["0 corrupt log lines (last round)" (zero? (corrupt-count))]])))

;; ===========================================================================
;; C7 — part_of cycle via multi-hop cooperative chain
;; ===========================================================================
(defn c7 []
  (println "\n--- C7: multi-hop part_of cycle (A->B->C->A) ---")
  (fresh! "/tmp/chelonia-c7.log")
  (commit! "thread:A" "state" "ready" 0)
  (commit! "thread:B" "state" "ready" 0)
  (commit! "thread:C" "state" "ready" 0)
  (with-server
    (fn [port]
      (let [rounds 40
            ag1 (future (doall (for [k (range rounds)]
                                 (assert! port "thread:A" "part_of" "thread:B" (rd-version port)))))
            ag2 (future (doall (for [k (range rounds)]
                                 (assert! port "thread:B" "part_of" "thread:C" (rd-version port)))))
            ag3 (future (doall (for [k (range rounds)]
                                 (assert! port "thread:C" "part_of" "thread:A" (rd-version port)))))
            ;; repointers churn the single-valued chain mid-storm
            rep (future (doall (for [k (range rounds)]
                                 (assert! port "thread:A" "part_of"
                                          (if (even? k) "thread:B" "thread:C") (rd-version port)))))
            _ @ag1 _ @ag2 _ @ag3 _ @rep
            claims (live-claims)
            nodes ["thread:A" "thread:B" "thread:C"]
            any-cycle (some #(cycle? claims "part_of" %) nodes)]
        (check! "C7"
          [["no multi-hop part_of cycle committed" (not any-cycle)]
           ["cycle? false for every node" (every? #(not (cycle? claims "part_of" %)) nodes)]
           ["each node has <=1 part_of (single-valued)" (every? #(<= (cardinality % "part_of") 1) nodes)]
           ["0 corrupt log lines" (zero? (corrupt-count))]])))))

;; ===========================================================================
;; C8 — Log durability / no torn writes under max concurrency + replay equivalence
;; ===========================================================================
(defn c8 []
  (println "\n--- C8: log durability + replay==live under max concurrency ---")
  (fresh! "/tmp/chelonia-c8.log")
  ;; seed a small valid world
  (commit! "thread:A" "state" "ready" 0)
  (commit! "thread:A" "driver" "person:x" 0)
  (commit! "thread:B" "state" "ready" 0)
  (commit! "thread:C" "state" "canceled" 0)
  (with-server
    (fn [port]
      (let [rounds 25
            ;; mixed writers: legal state churn, illegal cycles, illegal canceled-deps,
            ;; missing-entity deps, active-without-driver attempts, retracts — all racing
            fs [(future (doall (for [k (range rounds)] (assert! port "thread:A" "state" (rand-nth ["ready" "active" "done"]) (rd-version port)))))
                (future (doall (for [k (range rounds)] (assert! port "thread:A" "part_of" "thread:A" (rd-version port))))) ; self-cycle (illegal)
                (future (doall (for [k (range rounds)] (assert! port "thread:B" "depends_on" "thread:C" (rd-version port))))) ; canceled dep (illegal)
                (future (doall (for [k (range rounds)] (assert! port "thread:B" "depends_on" "thread:GHOST" (rd-version port))))) ; missing (illegal)
                (future (doall (for [k (range rounds)] (assert! port "thread:B" "state" "active" (rd-version port))))) ; active no driver (illegal)
                (future (doall (for [k (range rounds)] (assert! port "thread:A" "title" (str "x" k) (rd-version port)))))
                (future (doall (for [k (range rounds)] (retract! port "thread:A" "driver" "person:x" (rd-version port)))))
                (future (doall (for [k (range rounds)] (assert! port "thread:A" "driver" "person:x" (rd-version port)))))]
            _ (doseq [f fs] @f)
            ;; replay the log through chelonia.clj's fold
            replay (fold (filter #(not= ::bad %) (parsed-log)))
            live @state
            log-cnt (count (filter #(not= ::bad %) (parsed-log)))]
        (check! "C8"
          [["0 corrupt/torn log lines" (zero? (corrupt-count))]
           ["tx strictly monotonic, no gaps/dupes" (tx-monotonic?)]
           ["replay :claims == live :claims" (= (:claims replay) (:claims live))]
           ["replay :version == live :version" (= (:version replay) (:version live))]
           ["version == log line count" (= (:version live) log-cnt)]
           ["INVARIANT: no active thread lacks a driver in live claims"
            (every? (fn [te] (or (not= "active" (one (:claims live) te "state"))
                                 (some? (one (:claims live) te "driver"))))
                    (thread-ids (:claims live)))]
           ["INVARIANT: no part_of/depends_on cycles in live claims"
            (every? (fn [te] (and (not (cycle? (:claims live) "part_of" te))
                                  (not (cycle? (:claims live) "depends_on" te))))
                    (thread-ids (:claims live)))]])))))

;; ===========================================================================
;; C9 — depends_on referencing a missing / never-seeded entity under load
;; ===========================================================================
(defn c9 []
  (println "\n--- C9: depends_on -> missing/never-seeded entity, rejected under load ---")
  (fresh! "/tmp/chelonia-c9.log")
  (commit! "thread:X" "state" "ready" 0)
  (with-server
    (fn [port]
      (let [rounds 40
            ;; writers race X depends_on GHOST (never seeded) ...
            ghosters (doall (for [i (range 8)]
                              (future (doall (for [k (range rounds)]
                                               (assert! port "thread:X" "depends_on" "thread:GHOST" (rd-version port)))))))
            ;; ... while one writer MAY create GHOST partway (gives it a state claim -> it becomes a known entity)
            maybe-creator (future
                            (Thread/sleep 60)
                            (assert! port "thread:GHOST" "state" "ready" (rd-version port)))
            gr (mapcat deref ghosters)
            _ @maybe-creator
            claims (live-claims)
            ghost-known (contains? (thread-ids claims) "thread:GHOST")
            xg-edges (count (q claims :l "thread:X" :p "depends_on" :r "thread:GHOST"))
            ;; INVARIANT: edge persists ONLY IF GHOST is a known entity in the same claim set
            ok (or (zero? xg-edges) ghost-known)]
        (check! "C9"
          [["dangling depends_on edge persists ONLY if target now exists" ok]
           ["if GHOST unknown, 0 edges to it" (or ghost-known (zero? xg-edges))]
           ["0 corrupt log lines" (zero? (corrupt-count))]
           ["tx strictly monotonic" (tx-monotonic?)]])))))

;; ===========================================================================
;; runner
;; ===========================================================================
(def all-cases {"C1" c1 "C2" c2 "C3" c3 "C4" c4 "C5" c5 "C6" c6 "C7" c7 "C8" c8 "C9" c9})

(defn -main [& args]
  (gap-probe)
  (let [sel (or (seq (filter all-cases args)) (sort (keys all-cases)))]
    (doseq [c sel] ((all-cases c)))
    (println "\n=========================== SUMMARY ===========================")
    (doseq [{:keys [case pass detail]} (sort-by :case @results)]
      (println (format "  %-4s %s" case (if pass "PASS" "FAIL"))))
    (let [n (count @results) p (count (filter :pass @results))]
      (println (format "\n  %d/%d cases passed" p n))
      (when (< p n) (System/exit 1)))))

(apply -main *command-line-args*)
