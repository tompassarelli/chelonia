;; ============================================================================
;; cnf_coord_sim.clj — #11 R2 (continuous arrival) + R3 (failure-heavy).
;; *** SIMULATION — LABELED. *** Real CI x the full arrival-rate x CI-duration sweep is
;; infeasible, so this is a discrete-event sim of the two coordination PROTOCOLS. The
;; protocol logic is faithful, not a strawman:
;;   GIT  = speculative-batching merge queue (Zuul / GitHub style): while the CI runner is
;;          busy, arrivals accumulate; when it frees, it takes the WHOLE accumulated batch and
;;          validates it in ONE CI run (amortization — this is why git wins R1). On a batch
;;          FAILURE it BISECTS (re-CI halves until the bad edit is isolated) — the realistic
;;          speculation-reset cost, not a serial strawman.
;;   FRAM = decoupled land/validate (view-relative): an edit LANDS at arrival + commit-latency
;;          (no queue, no per-edit CI); validation is a publish-time concern. Disjoint ⇒ no OCC.
;; GROUNDED params: Fram commit latency = 70ms (MEASURED — #14 socket: 2 concurrent in 141ms).
;; MODELED+SWEPT param: git CI duration C (the second free parameter — swept as a FAMILY, per the
;; arrival-rate sweep, so no single modeled constant hides a tuned number).
;; The comparison is LANDING LATENCY (edit-ready -> edit-in-shared-substrate). The TRADE is stated:
;; git keeps main continuously green (CI per batch); Fram lands cheap + defers validation to publish
;; (main view-locally incoherent between publishes). Different guarantees — reported, not hidden.
;;   bb cnf_coord_sim.clj
;; ============================================================================
(require '[clojure.string :as str])

(def FRAM-COMMIT-MS 70)            ; MEASURED (#14). Fram lands an edit this long after it's ready.
(def FRAM-PUBLISH-VALIDATIONS 1)   ; view-relative: validate the published view ONCE at the end.

;; ---- GIT speculative-batching queue (discrete-event) ----
;; arrivals: sorted vec of arrival times (ms). C: CI duration (ms). fails: set of edit indices
;; whose validation fails. Returns {:validation-runs :landed :failed :latencies}.
(defn sim-git [arrivals C fails]
  (let [n (count arrivals)]
    (loop [next-i 0          ; next edit index not yet queued
           runner-free 0     ; time the CI runner becomes free
           vruns 0           ; CI invocations
           landed 0
           lat (transient [])]
      (if (>= next-i n)
        {:validation-runs vruns :landed landed
         :failed (count (filter fails (range n))) :latencies (persistent! lat)}
        ;; the batch starts when the runner is free AND >=1 edit has arrived.
        (let [start (max runner-free (nth arrivals next-i))
              ;; speculative batch = every edit that has ARRIVED by `start` and isn't yet queued.
              batch (loop [j next-i] (if (and (< j n) (<= (nth arrivals j) start)) (recur (inc j)) j))
              idxs (range next-i batch)
              has-fail (some fails idxs)]
          (if-not has-fail
            ;; clean batch: ONE CI run lands all at start+C.
            (let [done (+ start C)]
              (recur batch done (inc vruns) (+ landed (count idxs))
                     (reduce (fn [a i] (conj! a (- done (nth arrivals i)))) lat idxs)))
            ;; failed batch: BISECT — re-CI halves until each bad edit isolated+rejected, good ones
            ;; land after. Cost = the bisection CI rounds (ceil(log2(batchsize))+1), serial on the runner.
            (let [bsz (count idxs)
                  rounds (+ 1 (long (Math/ceil (/ (Math/log (max 2 bsz)) (Math/log 2)))))
                  done (+ start (* rounds C))
                  good (remove fails idxs)]
              (recur batch done (+ vruns rounds) (+ landed (count good))
                     (reduce (fn [a i] (conj! a (- done (nth arrivals i)))) lat good)))))))))

;; ---- FRAM decoupled land/validate ----
(defn sim-fram [arrivals C fails]
  (let [good (remove fails (range (count arrivals)))]
    {:validation-runs FRAM-PUBLISH-VALIDATIONS   ; one publish-time validation of the view
     :landed (count good) :failed (count (filter fails (range (count arrivals))))
     ;; each edit lands commit-latency after it's ready — no queue, flat regardless of arrival rate.
     :latencies (mapv (fn [_] FRAM-COMMIT-MS) good)}))

(defn pctl [xs p] (if (empty? xs) 0 (let [s (vec (sort xs))] (nth s (min (dec (count s)) (long (* p (count s))))))))
(defn arrivals [k interval-ms] (vec (map #(* % interval-ms) (range k))))   ; evenly spaced

;; ============================================================================
;; R2 — CONTINUOUS ARRIVAL: sweep inter-arrival interval, for a FAMILY of CI durations.
;; report median + p99 landing latency, git vs fram, and the crossover (interval where git
;; latency exceeds fram's). NO failures here (R2 is the clean continuous-arrival regime).
;; ============================================================================
(def K 32)
;; CI duration ms. 3880 is MEASURED (real build-all recompile of the rendered schema module —
;; cnf_coord_experiment.clj, 2 runs 3912/3848ms); the others bracket it (modeled, swept).
(def C-family [100 1000 3880 10000])
(def intervals [5000 1000 200 50 10])        ; inter-arrival ms: slow -> fast
(println "=== #11 R2 — continuous arrival (K=" K "edits, evenly spaced). SIMULATION. ===")
(println "   Fram commit=70ms (measured #14); git CI duration C modeled+swept. latency = ready->landed (ms).\n")
(doseq [C C-family]
  (println (format "  -- CI duration C = %dms --" C))
  (println (format "    %-12s %-10s %-10s %-10s %-10s %-10s" "interval" "git-med" "git-p99" "fram-med" "fram-p99" "git/fram-med"))
  (doseq [iv intervals]
    (let [arr (arrivals K iv)
          g (sim-git arr C #{}) f (sim-fram arr C #{})
          gm (pctl (:latencies g) 0.5) g99 (pctl (:latencies g) 0.99)
          fm (pctl (:latencies f) 0.5) f99 (pctl (:latencies f) 0.99)]
      (println (format "    %-12s %-10d %-10d %-10d %-10d %.1fx"
                       (str iv "ms") gm g99 fm f99 (double (/ gm (max 1 fm)))))))
  ;; crossover: the slowest arrival interval at which git median latency already exceeds fram's.
  (let [cross (some (fn [iv] (let [g (sim-git (arrivals K iv) C #{})]
                               (when (> (pctl (:latencies g) 0.5) (* 2 FRAM-COMMIT-MS)) iv))) intervals)]
    (println (format "    crossover: git median latency exceeds ~2x Fram once inter-arrival <= %s\n"
                     (if cross (str cross "ms (= " (format "%.1f" (/ 1000.0 cross)) " edits/sec)") "never in swept range")))))

;; ============================================================================
;; GIT-ARM ANTI-STRAWMAN GOVERNOR — R1's acceptance test, applied to the SIM's git arm.
;; A faithful speculative queue AMORTIZES HARDER as arrival climbs (more edits per batch ->
;; FEWER CIs). A Bors/serial strawman would not. So git validation-runs must be NON-INCREASING
;; as the inter-arrival interval shrinks (arrival speeds up). If it rises, the sim's git arm is a
;; strawman and R2 is void — the same governor R1's REAL git arm had (validation-runs O(1) or void).
;; ============================================================================
(println "=== GIT-ARM governor (C=1000ms): does the sim's git queue WIDEN batches with arrival? ===")
(def gov (mapv (fn [iv] [iv (:validation-runs (sim-git (arrivals K iv) 1000 #{}))]) intervals))   ; intervals slow->fast
(doseq [[iv n] gov]
  (println (format "    interval=%-7s git validation-runs=%-3d (avg batch ~%d edits)" (str iv "ms") n (long (Math/ceil (/ K (max 1 n)))))))
(def gov-faithful (apply >= (map second gov)))   ; non-increasing slow->fast = batches widen
(println (format "    validation-runs NON-increasing as arrival speeds (batches WIDEN = faithful queue, NOT serial strawman)? %s" gov-faithful))
(println (if gov-faithful "    => the sim's git arm passes R1's anti-strawman test. R2 latency is vs a real batching queue.\n"
                          "    => STRAWMAN: git never amortizes; R2 is VOID. Fix the queue model.\n"))

;; ============================================================================
;; R3 — FAILURE-HEAVY: fixed fast arrival, sweep failure fraction. git's batch poisons +
;; bisects (validation-runs blow up, good edits delayed); fram isolates (per-edit).
;; ============================================================================
(println "=== #11 R3 — failure-heavy (K=" K ", interval=50ms, C=1000ms). SIMULATION. ===")
(println (format "    %-10s %-14s %-14s %-16s %-16s" "fail-frac" "git-vruns" "fram-vruns" "git-good-med-lat" "fram-good-med-lat"))
(let [iv 50 C 1000 arr (arrivals K iv)]
  (doseq [ff [0.0 0.1 0.25 0.5]]
    (let [nfail (long (* ff K))
          fails (set (take nfail (range K)))     ; first nfail edits fail (spread across batches)
          g (sim-git arr C fails) f (sim-fram arr C fails)]
      (println (format "    %-10s %-14d %-14d %-16d %-16d"
                       (str (long (* 100 ff)) "%") (:validation-runs g) (:validation-runs f)
                       (pctl (:latencies g) 0.5) (pctl (:latencies f) 0.5))))))

(println "\n=== R2/R3 READING (cold) ===")
(println "  R2: Fram landing latency is FLAT (= commit 70ms) regardless of arrival rate — landing is")
(println "      decoupled from validation. Git landing latency CLIMBS as arrival outpaces CI throughput")
(println "      (queue backs up); the crossover moves with C. TRADE: git keeps main continuously green")
(println "      (CI per batch); Fram defers validation to publish (main view-locally incoherent between).")
(println "  R3: one bad edit POISONS git's batch -> bisection -> validation-runs blow up + good edits in")
(println "      the batch are delayed. Fram isolates per-edit (bad edit doesn't delay/poison the others).")
(println "  R1 (real, committed c2dae60): GIT WINS — batching amortizes validation for clean disjoint work.")
(println "  NET: not a blowout. git wins clean batched throughput; Fram wins landing latency under")
(println "  continuous arrival and failure isolation — the view-relative decoupling of land-from-validate.")
(System/exit 0)
