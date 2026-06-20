;; ============================================================================
;; cnf_crdt_commute_order.clj — #36 GATE 3: do same-gap concurrent inserts CONVERGE
;; to a delivery-order-INDEPENDENT total order? (commute, demonstrated not asserted)
;;   bb -cp out tests/cnf_crdt_commute_order.clj
;;
;; Gate 2 (cnf_crdt_insert_receipt) shows two concurrent same-gap inserts BOTH LAND at
;; distinct keys, no duplicate (no-clobber/no-loss). Gate 3 asks the STRONGER CRDT
;; question: is the FINAL TOTAL ORDER independent of which insert is delivered/committed
;; first? Final order is a pure function of (path, tie): both inserts share the same
;; between-path, so the ORDER is decided entirely by the TIE. So the question reduces to:
;; WHAT IS THE TIE?
;;
;; This test inserts two defs concurrently at the same gap and checks the SOURCE of each
;; tie. The daemon sets the tie to the new form node's atomic name-int (reserve-name-ints!,
;; a monotonic counter reserved at COMMIT). We assert tie == the node's @<mod>#<int>
;; name-int. If so, the order is the COMMIT order (whoever commits first gets the lower
;; tie -> lands first), NOT a generation-time site-id. That is the honest boundary:
;;   commute == no-clobber + no-dup + a deterministic order PER EXECUTION,
;;   NOT delivery-order-independent convergence (that needs a site-id/content tie).
;;
;; This is deterministic and single-boot (fast): the tie SOURCE is the whole question;
;; an N-trial race would only sample commit orders and could be schedule-biased.
;;
;; SAFE: isolated in-process boot on a /tmp COPY of .fram/code.log; never 7977, never
;; the canonical log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")
(def M "kernel")
(when-not (.exists (io/file ".fram/code.log")) (println "SKIP — no .fram/code.log") (System/exit 0))

(def tmp (str "/tmp/cnf-g3-" (System/nanoTime) ".log"))
(io/copy (io/file ".fram/code.log") (io/file tmp))
(boot-flat! tmp)
(def st (:store @co))

(defn vof [e] (let [Vp (c/value-id st "v")] (some->> (c/by-lp st e Vp) first (c/claim-of st) :r (c/literal st))))
(defn name-of [e] (let [NAME (c/value-id st "name")] (some->> (c/by-lp st e NAME) first (c/claim-of st) :r (c/literal st))))
(defn forms-of [wrap]
  (->> (c/by-l st wrap)
       (keep (fn [cid] (let [cl (c/claim-of st cid) ks (c/literal st (:p cl)) k (resolve/ord-parse ks)]
                         (when k {:key k :keystr ks :child (:r cl)}))))
       (sort-by :key resolve/ord-cmp) vec))
(defn def-name [child]
  (let [kids (->> (c/by-l st child)
                  (keep (fn [cid] (let [k (resolve/ord-parse (c/literal st (:p (c/claim-of st cid))))] (when k [k (c/claim-of st cid)]))))
                  (sort-by first resolve/ord-cmp))]
    (when (>= (count kids) 2) (vof (:r (second (nth kids 1)))))))
(defn head-of [child] (vof (:child (first (forms-of child)))))
(defn wrapper [m]
  (let [NAME (c/value-id st "name") pfx (str "@" m "#")]
    (->> (c/by-p st NAME)
         (keep (fn [cid] (let [nm (c/literal st (:r (c/claim-of st cid)))] (when (and (string? nm) (str/starts-with? nm pfx)) (:l (c/claim-of st cid))))))
         (filter (fn [e] (let [fs (forms-of e)] (and (seq fs) (= "beagle-file" (vof (:child (first fs))))))))
         first)))
(defn name-int [nm] (when (and (string? nm) (str/includes? nm "#")) (parse-long (subs nm (inc (str/index-of nm "#"))))))

(def wrap (wrapper M))
(def pre (forms-of wrap))
(def ai (first (filter (fn [i] (and (< (inc i) (count pre))
                                    (#{"def" "defn" "defn-" "def-" "defonce"} (head-of (:child (nth pre i))))
                                    (def-name (:child (nth pre i)))))
                       (range (count pre)))))
(when (nil? ai) (println "FAIL: no def anchor with a next sibling") (System/exit 1))
(def anchor (def-name (:child (nth pre ai))))

(println "=== #36 GATE 3 — same-gap concurrent insert convergence (tie-source test) ===")
(println "anchor:" anchor)
;; CONCURRENT: both clone pre-insert state lock-free, race to commit at the SAME gap.
(def fA (future (handle {:op :edit-min :spec {:op "insert-form" :module M :after anchor :datum (list 'def 'fram_g3_A 1)}})))
(def fB (future (handle {:op :edit-min :spec {:op "insert-form" :module M :after anchor :datum (list 'def 'fram_g3_B 2)}})))
(def rA @fA) (def rB @fB)

(def post (forms-of wrap))
(defn entry-for [nm] (first (filter #(= nm (def-name (:child %))) post)))
(def eA (entry-for "fram_g3_A"))
(def eB (entry-for "fram_g3_B"))
(def landed (and eA eB true))
(def keystrs (map :keystr post))
(def no-dup (= (count keystrs) (count (distinct keystrs))))
(def distinct-keys (and eA eB (not= (:keystr eA) (:keystr eB))))
(def same-path (and eA eB (= (:path (:key eA)) (:path (:key eB)))))

;; the SOURCE of each tie: is it the form node's atomic name-int (commit-order alloc)?
(def tieA (:tie (:key eA)))
(def tieB (:tie (:key eB)))
(def niA (name-int (name-of (:child eA))))
(def niB (name-int (name-of (:child eB))))
(def tie-is-nameint (and tieA tieB niA niB (= tieA niA) (= tieB niB)))

(println (format "  inserts ok: A=%s B=%s" (boolean (:ok rA)) (boolean (:ok rB))))
(println (format "  both landed=%s  same between-path=%s  distinct keys=%s  no duplicate index=%s" landed same-path distinct-keys no-dup))
(println (format "  A: key=%s name=%s name-int=%s tie=%s" (:keystr eA) (name-of (:child eA)) niA tieA))
(println (format "  B: key=%s name=%s name-int=%s tie=%s" (:keystr eB) (name-of (:child eB)) niB tieB))
(println (format "  tie == form-node atomic name-int (commit-order alloc)?  %s" tie-is-nameint))

(println "\n=== VERDICT (gate 3) ===")
(cond
  (not (and (:ok rA) (:ok rB) landed no-dup distinct-keys same-path))
  (do (println "FAIL — the no-clobber/no-dup floor (gate 2) did not hold here; investigate before reading convergence.") (System/exit 1))

  tie-is-nameint
  (do (println "CHARACTERIZED (honest boundary) — both same-gap inserts share the between-path; the ORDER is")
      (println "decided by the TIE, and each tie IS the form node's atomic name-int, reserved at COMMIT by a")
      (println "monotonic counter (reserve-name-ints!/allocate-positions). So whoever COMMITS first gets the")
      (println "lower tie and lands first => the final total order is the COMMIT order, deterministic PER")
      (println "EXECUTION, but NOT delivery-order-independent. #36 'commute' = no-clobber + no-dup + per-")
      (println "execution-deterministic ordering; it is NOT strong (order-independent) CRDT convergence — that")
      (println "would require a generation-time site-id/content tie, not the receiver-side commit-order name-int.")
      (println "For sibling top-level defs the relative order is semantically free; a forward-ref BETWEEN them")
      (println "is separately DETECTED (cnf_crdt_coupled_receipt). Gate 3 result banked as that boundary.")
      (System/exit 0))

  :else
  (do (println "UNEXPECTED — ties are NOT the commit-order name-int (tieA=%s niA=%s tieB=%s niB=%s)." tieA niA tieB niB)
      (println "The order may be order-independent after all; re-examine the tie source before claiming the boundary.")
      (System/exit 2)))
