;; ============================================================================
;; !!! IDENTITY-MODEL GATE — NOT today's path. See cnf_gate_v2_hole.clj banner +
;;     cnf_gate_v2_read.clj: mainline refs are SPELLING+DERIVED, so this guard's
;;     green (150/0/0/150/0) is on a SYNTHETIC client-authored-refers_to path.
;;     The per-op guard it models also REGRESSED the flip (clean-slate set-body
;;     transiently retracts a referenced node's kind) — the real guard belongs at
;;     the delta boundary, inside dlock, through handle. Build when refs carry identity.
;; ============================================================================
;; cnf_gate_v2_guard.clj — GATE-V2 STEP 2: the guard, raced. FOUR INTEGERS.
;;
;; Same TRUE-concurrency race as the hole demo, now with the cross-key referential
;; guard live in do-assert/do-retract. Two races, four integers (Tom's discipline):
;;
;;  COLLISION race  (A: delete Xi  ‖  B: assert (Zi refers_to Xi), Xi clean) x N
;;   (i)   guard fired  — EXACTLY ONE of A/B rejected            (target: N)
;;   (iii) dangling     — live refers_to over a dead node        (target: 0)
;;         both-landed  — the collision must NOT both-commit      (target: 0)
;;
;;  INDEPENDENT race (A: delete Xi' (unref'd) ‖ B: ref a LIVE unrelated Zk) x M
;;   (ii)/(iv) no over-rejection — BOTH must commit              (target: M)
;;             spurious reject   — a reject-everything guard fails here (target: 0)
;;
;; PASS = (i)==N AND both-landed==0 AND dangling==0 AND (ii)==M AND spurious==0.
;; A guard that trivially rejects everything scores dangling=0 but FAILS (ii).
;;   bb -cp out cnf_gate_v2_guard.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.java.io :as io])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no .fram/code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

(def flat (str (System/getProperty "java.io.tmpdir") "/gate-v2-guard-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)
(def st (:store @co))
(materialize-refers-whole!)
(def KIND   (c/value-id st "kind"))
(def REFERS (c/value-id st "refers_to"))
(defn live-kind?  [x-id] (seq (c/by-lp st x-id KIND)))      ; live-only index
(defn referenced? [x-id] (seq (c/by-pr st REFERS x-id)))
(defn name-of     [x-id] (s/name-of st x-id))
(defn kindval     [x-id] (c/literal st (:r (c/claim-of st (first (c/by-lp st x-id KIND))))))

;; node pools: nodes that currently have a live kind, split into
;;  - clean   : live kind AND no incoming refers_to  -> collision + independent victims
;;  - targets : any live-kind node                   -> the LIVE Z the independent B references
(def all-kind (->> (c/by-p st KIND) (map #(:l (c/claim-of st %))) distinct (filter live-kind?) vec))
(def clean    (->> all-kind (filter #(not (referenced? %))) vec))
(def N (min 150 (quot (count clean) 2)))
(def M N)
(def coll-victims (subvec clean 0 N))
(def indep-victims (subvec clean N (+ N M)))
(def z-targets (->> all-kind (filter referenced?) (take M) vec))   ; live, stay-live referents
(println "pools — all-kind:" (count all-kind) " clean:" (count clean)
         " N(collision):" N " M(independent):" M " z-targets:" (count z-targets))

;; ---- COLLISION race ----------------------------------------------------------
(def guard-fired (atom 0)) (def coll-both (atom 0)) (def dangling (atom 0))
(doseq [[i x-id] (map-indexed vector coll-victims)]
  (let [xn (name-of x-id) kv (kindval x-id) zn (str "@gatev2c#ref" i) v0 (current-seq @co)
        fa (future (do-retract xn "kind" kv v0))
        fb (future (do-assert  zn "refers_to" xn v0))
        ra @fa rb @fb
        a-ok (boolean (:ok ra)) b-ok (boolean (:ok rb))]
    (when (not= a-ok b-ok) (swap! guard-fired inc))         ; exactly one rejected
    (when (and a-ok b-ok)  (swap! coll-both inc))
    (when (and (not (live-kind? x-id)) (referenced? x-id)) (swap! dangling inc))))

;; ---- INDEPENDENT race (no over-rejection) -----------------------------------
(def indep-both (atom 0)) (def spurious (atom 0))
(doseq [[i x-id] (map-indexed vector indep-victims)]
  (let [xn (name-of x-id) kv (kindval x-id)
        z-id (nth z-targets (mod i (count z-targets)))
        zn (name-of z-id) src (str "@gatev2i#ref" i) v0 (current-seq @co)
        fa (future (do-retract xn "kind" kv v0))            ; delete an UNREFERENCED node
        fb (future (do-assert  src "refers_to" zn v0))      ; ref a LIVE UNRELATED node
        ra @fa rb @fb]
    (if (and (:ok ra) (:ok rb)) (swap! indep-both inc) (swap! spurious inc))))

(println "\n================ GATE-V2 STEP 2 — GUARD, RACED (four integers) ================")
(println "COLLISION (delete X ‖ ref X), N =" N)
(println "  (i)   guard fired (exactly one rejected) :" @guard-fired "  / target" N)
(println "        both landed (must be 0)            :" @coll-both)
(println "  (iii) DANGLING refers_to (must be 0)     :" @dangling)
(println "INDEPENDENT (delete X' ‖ ref live Z), M =" M)
(println "  (ii)  both committed (no over-rejection) :" @indep-both "  / target" M)
(println "        spurious rejects (must be 0)       :" @spurious)
(def pass? (and (= @guard-fired N) (zero? @coll-both) (zero? @dangling)
                (= @indep-both M) (zero? @spurious)))
(println (if pass?
           "\nGATE-V2 PASS — guard catches the disjoint-key delete/ref collision (dangling=0) AND passes the independent pair (no over-rejection). §5's IOU serviced."
           "\nGATE-V2 FAIL — see integers above."))
(System/exit (if pass? 0 1))
