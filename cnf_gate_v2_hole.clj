;; ============================================================================
;; !!! IDENTITY-MODEL GATE — NOT today's path. The discriminating read
;;     (cnf_gate_v2_read.clj) PROVED mainline references are SPELLING+DERIVED:
;;     refers_to is materializer-derived, never client-authored. This demo
;;     CLIENT-AUTHORS refers_to through raw do-assert — a SYNTHETIC path
;;     production never takes. Its 200/200 dangling is REAL ONLY in the
;;     identity-carrying model (db02df9). Do NOT cite it as a production result.
;; ============================================================================
;; cnf_gate_v2_hole.clj — GATE-V2 STEP 1: SHOW THE HOLE (no guard yet).
;;
;; The thesis's hard core = reference-integrity under TRUE concurrency. The hazard
;; (Tom's framing #2, the cross-key one): two same-base edits, DISJOINT (te,p) keys —
;;   A: delete node X            -> retract (X, kind, _)        key = (X, kind)
;;   B: reference X              -> assert  (Yi, refers_to, X)  key = (Yi, refers_to)
;; The per-(te,p) OCC (commit!/retract!, base_version) only fires when the SAME
;; single-valued (te,p) is stale. A and B touch DIFFERENT keys, so OCC passes BOTH —
;; and you get a LIVE refers_to -> a node with no live `kind` = a DANGLING reference.
;; That is exactly §5's conceded cardinality debt (the ref-existence check Datomic
;; gets free from typed cardinality, which CNF threw away) coming due.
;;
;; "X is a live binding" := X has >=1 live `kind` claim (the resolver's own notion —
;; a binding node carries kind; refers-target/ultimate walk to such a node). "delete X"
;; := retract X's live kind. This models, at the COORDINATOR layer where the guard must
;; live, the atomic effect a delete has on X and the materializer's refers_to write.
;;
;; ORDER-INDEPENDENT by design: both ops commit regardless of interleaving (no conflict
;; fires either way), so the hole is the DISJOINTNESS, not a hand-picked sequence.
;;   bb -cp out cnf_gate_v2_hole.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.java.io :as io])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no .fram/code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

;; boot on a TEMP copy — never the canonical code log, never 7977/lodestar.
(def flat (str (System/getProperty "java.io.tmpdir") "/gate-v2-hole-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)
(def st (:store @co))
(materialize-refers-whole!)                         ; populate refers_to over the warm store
(def KIND   (c/value-id st "kind"))
(def REFERS (c/value-id st "refers_to"))
(println "booted:" (count (c/current-claims st)) "claims; kind-pid" KIND "refers-pid" REFERS)

;; --- store reads (live = not superseded) ------------------------------------
(defn live? [cid] (not (contains? (:superseded @st) cid)))
(defn live-kind-cids [x-id]                          ; X's live kind claims (X live <=> seq)
  (filter (fn [cid] (and (live? cid) (= x-id (:l (c/claim-of st cid)))))
          (c/by-lp st x-id KIND)))
(defn live-refers-to [x-id]                          ; live refers_to claims whose :r = X
  (filter (fn [cid] (and (live? cid) (= x-id (:r (c/claim-of st cid)))))
          (c/by-p st REFERS)))
(defn name-of [x-id] (s/name-of st x-id))

;; --- pick N distinct victim nodes that each currently have a live kind ------
(def N 200)
(def victims
  (->> (c/by-p st KIND)
       (filter live?)
       (map #(:l (c/claim-of st %)))
       distinct
       (filter #(seq (live-kind-cids %)))            ; defensive
       (take N)
       vec))
(println "victims chosen:" (count victims))

;; --- the race: for each victim Xi, fire A (delete Xi) ‖ B (Zi refers_to Xi) --
;; same base v0, genuine concurrent futures. Zi is a fresh ref-source node per iter.
(def both-land (atom 0))
(def dangling  (atom 0))
(doseq [[i x-id] (map-indexed vector victims)]
  (let [x-name  (name-of x-id)
        kind-v  (c/literal st (:r (c/claim-of st (first (live-kind-cids x-id)))))
        z-name  (str "@gatev2#ref" i)                ; fresh referencing node
        v0      (current-seq @co)
        fa (future (do-retract x-name "kind" kind-v v0))         ; A: delete X
        fb (future (do-assert  z-name "refers_to" x-name v0))]   ; B: reference X
    @fa @fb
    (let [x-dead?    (empty? (live-kind-cids x-id))
          ref-live?  (seq (live-refers-to x-id))]
      (when (and x-dead? ref-live?) (swap! both-land inc))
      ;; DANGLING = a live refers_to -> X while X has NO live kind (orphaned reference)
      (when (and x-dead? ref-live?) (swap! dangling inc)))))

(println "\n================ GATE-V2 STEP 1 — HOLE (no guard) ================")
(println "iterations              :" (count victims))
(println "both ops landed         :" @both-land)
(println "DANGLING refers_to      :" @dangling "  <- live ref to a node with no live kind")
(println (if (pos? @dangling)
           "\nHOLE CONFIRMED — per-(te,p) OCC passes the disjoint delete/ref pair; reference-integrity is HOLED under concurrency."
           "\nNO HOLE SHOWN — investigate (the disjoint pair did not orphan)."))
(System/exit 0)
