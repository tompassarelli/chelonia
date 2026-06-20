;; ============================================================================
;; cnf_crdt_coupled_receipt.clj — #36 bar: coupled mid-insert is DETECTED (not silent)
;;   bb -cp out cnf_crdt_coupled_receipt.clj
;;
;; Insert cpl_B after an anchor, then insert cpl_A (whose body references cpl_B) after
;; the SAME anchor -> cpl_A lands strictly BELOW cpl_B (both in the anchor gap). cpl_A
;; (lower) referencing cpl_B (higher) is a load-time forward-ref. The CRDT-aware guard
;; (resolved refers_to, top-level position by ord-cmp) DETECTS it — the no-silent-
;; misorder bar, now over CRDT keys.
;;
;; SAFE: isolated daemon on a /tmp COPY of .fram/code.log; never 7977 / canonical log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")
(def tmp (str "/tmp/cnf-crdtcpl-" (System/nanoTime) ".log"))
(io/copy (io/file ".fram/code.log") (io/file tmp))
(boot-flat! tmp)
(def st (:store @co))
(defn vof [e] (let [Vp (c/value-id st "v")] (some->> (c/by-lp st e Vp) first (c/claim-of st) :r (c/literal st))))
(defn forms-of [wrap]
  (->> (c/by-l st wrap) (keep (fn [cid] (let [cl (c/claim-of st cid) k (resolve/ord-parse (c/literal st (:p cl)))] (when k {:key k :child (:r cl)})))) (sort-by :key resolve/ord-cmp) vec))
(defn def-name [child] (let [kids (->> (c/by-l st child) (keep (fn [cid] (let [k (resolve/ord-parse (c/literal st (:p (c/claim-of st cid))))] (when k [k (c/claim-of st cid)])))) (sort-by first resolve/ord-cmp))] (when (>= (count kids) 2) (vof (:r (second (nth kids 1)))))))
(defn head-of [child] (vof (:child (first (forms-of child)))))
(defn wrapper [m] (let [NAME (c/value-id st "name") pfx (str "@" m "#")]
  (->> (c/by-p st NAME) (keep (fn [cid] (let [nm (c/literal st (:r (c/claim-of st cid)))] (when (and (string? nm) (str/starts-with? nm pfx)) (:l (c/claim-of st cid)))))) (filter (fn [e] (let [fs (forms-of e)] (and (seq fs) (= "beagle-file" (vof (:child (first fs)))))))) first)))

;; CRDT-aware forward-ref check: a refers_to edge whose referencer's top-level position
;; (ord-cmp) is BELOW its same-module target's -> use-before-def.
(defn forward-refs []
  (with-resolve-read st
    (let [psi (parent-slot-index st)
          topk (fn [n] (let [p (node-path psi n)] (when-let [s (first p)] (resolve/ord-parse s))))]
      (->> (c/by-p resolve/ctx resolve/REFERS) (map #(c/claim-of resolve/ctx %))
           (keep (fn [cl] (let [L (:l cl) D (resolve/ultimate (:r cl))
                                lk (topk L) dk (topk D)
                                lm (resolve/name->module (s/name-of resolve/ctx L))
                                dm (resolve/name->module (s/name-of resolve/ctx D))]
                            (when (and lm dm (= lm dm) lk dk (neg? (resolve/ord-cmp lk dk)))
                              {:def (resolve/binding-name D)}))))
           set))))

(def M "kernel")
(def wrap (wrapper M))
(def pre (forms-of wrap))
(def ai (first (filter (fn [i] (and (< (inc i) (count pre)) (#{"def" "defn" "defn-" "def-" "defonce"} (head-of (:child (nth pre i)))) (def-name (:child (nth pre i))))) (range (count pre)))))
(def anchor (def-name (:child (nth pre ai))))
(handle {:op :refers-ensure})
(def base-fr (forward-refs))
;; cpl_B first, then cpl_A (refs cpl_B) after the same anchor -> cpl_A below cpl_B
(def rB (handle {:op :edit-min :spec {:op "insert-form" :module M :after anchor :datum (list 'def 'fram_cpl_B 1)}}))
(def rA (handle {:op :edit-min :spec {:op "insert-form" :module M :after anchor :datum (list 'def 'fram_cpl_A (list 'identity 'fram_cpl_B))}}))
(handle {:op :refers-ensure})
(def post-fr (forward-refs))
(def new-fr (clojure.set/difference post-fr base-fr))
(println "=== #36 — coupled mid-insert detection ===")
(println "inserts:" (mapv #(boolean (:ok %)) [rB rA]) " base forward-refs:" (count base-fr) " new:" (count new-fr))
(doseq [f new-fr] (println "  NEW forward-ref ->" f))
(def detected (some #(= "fram_cpl_B" (:def %)) new-fr))
(println "\n=== VERDICT ===")
(if (and (:ok rA) (:ok rB) detected)
  (println "PASS — the coupled mid-insert (cpl_A below cpl_B, cpl_A refs cpl_B) is DETECTED as a NEW"
           "forward-ref over CRDT keys (ord-cmp positions). No-silent-misorder bar MET for mid-inserts.")
  (println "FAIL — coupled forward-ref not detected (or insert rejected)."))
