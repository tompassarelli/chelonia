;; ============================================================================
;; cnf_commute_receipt.clj — R1: concurrent same-module appends COMMUTE (closes #31)
;;   bb -cp out cnf_commute_receipt.clj
;;
;; Two concurrent :edit-min upsert-form appends to ONE module, driven through the REAL
;; do-edit-min path. With D (atomic append-position allocation under dlock) they land at
;; DISTINCT fN positions -> no duplicate index, no render corruption, BOTH defs survive.
;; Pre-D (#31, commit 4c6a0bf) the clone-frozen next-n raced -> two live children at one fN.
;;
;; SAFETY: isolated daemon on a /tmp COPY of .fram/code.log. Never port 7977, never the
;; canonical log, never the original corpus.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")

(def tmp (str "/tmp/cnf-commute-" (System/nanoTime) ".log"))
(io/copy (io/file ".fram/code.log") (io/file tmp))
(boot-flat! tmp)
(def st (:store @co))

(defn fN-edges [e]                       ; N -> [child ...] over live f\d+ edges
  (->> (c/by-l st e) (map #(c/claim-of st %))
       (keep (fn [cl] (let [p (c/literal st (:p cl))]
                        (when (and (string? p) (re-matches #"f\d+" p)) [(parse-long (subs p 1)) (:r cl)]))))
       (reduce (fn [m [n r]] (update m n (fnil conj []) r)) {})))

(defn child-v [e]                        ; v (symbol spelling) of node e, or nil
  (let [Vp (c/value-id st "v")] (some->> (c/by-lp st e Vp) first (c/claim-of st) :r (c/literal st))))

(defn head-sym-of [e]                    ; sym-val of the lowest-fN child (mirrors resolve/head-sym)
  (let [edges (fN-edges e)]
    (when (seq edges) (child-v (first (get edges (apply min (keys edges))))))))

(defn wrapper-of-module [m]              ; the module's beagle-file node (head symbol == "beagle-file")
  (let [NAME (c/value-id st "name") pfx (str "@" m "#")]
    (->> (c/by-p st NAME) (map #(c/claim-of st %))
         (keep (fn [cl] (let [nm (c/literal st (:r cl))]
                          (when (and (string? nm) (str/starts-with? nm pfx)) (:l cl)))))
         (filter (fn [e] (= "beagle-file" (head-sym-of e))))
         first)))

(defn def-name-of [root]                  ; (def NAME ...) -> "NAME" (f1 child's symbol v)
  (let [f1 (first (get (fN-edges root) 1))] (when f1 (child-v f1))))

(println "=== R1 — concurrent same-module appends COMMUTE (closes #31) ===")
(def M "kernel")
(def wrap (wrapper-of-module M))
(when (nil? wrap)
  (println "FAIL: no beagle-file wrapper found for module" M
           "— sample @kernel# head-syms:"
           (->> (c/by-p st (c/value-id st "name")) (map #(c/claim-of st %))
                (keep (fn [cl] (let [nm (c/literal st (:r cl))]
                                 (when (and (string? nm) (str/starts-with? nm "@kernel#"))
                                   [nm (head-sym-of (:l cl))])))) (take 8) vec))
  (System/exit 1))
(def pre (fN-edges wrap))
(def pre-count (reduce + (map count (vals pre))))
(def pre-dups (count (filter (fn [[_ cs]] (> (count cs) 1)) pre)))
(println (format "module %s wrapper %s  pre: forms=%d maxfN=%d preexisting-dups=%d"
                 M (s/name-of st wrap) pre-count (apply max (keys pre)) pre-dups))

;; two concurrent edit-min upsert-form appends, raced
(def fA (future (handle {:op :edit-min :spec {:op "upsert-form" :module M :datum '(def fram_commute_probe_A 111)}})))
(def fB (future (handle {:op :edit-min :spec {:op "upsert-form" :module M :datum '(def fram_commute_probe_B 222)}})))
(def rA @fA) (def rB @fB)
(println "edit A ->" (select-keys rA [:ok :reject]) "  edit B ->" (select-keys rB [:ok :reject]))

(def post (fN-edges wrap))
(def post-count (reduce + (map count (vals post))))
(def dups (filter (fn [[_ cs]] (> (count cs) 1)) post))
(def roots (mapcat second post))
(def hasA (some #(= "fram_commute_probe_A" (def-name-of %)) roots))
(def hasB (some #(= "fram_commute_probe_B" (def-name-of %)) roots))
(println (format "post: forms=%d distinctfN=%d DUPLICATE-positions=%d  probeA=%s probeB=%s"
                 post-count (count post) (count dups) (boolean hasA) (boolean hasB)))
(when (seq dups) (println "  !!! duplicate-index at fN:" (vec (map first dups))))

(println "\n=== VERDICT ===")
(if (and (:ok rA) (:ok rB) (zero? pre-dups) (empty? dups) hasA hasB (= post-count (+ 2 pre-count)))
  (println "PASS — both appends landed at DISTINCT fN (COMMUTE), zero duplicate index, both defs"
           "present, +2 forms. #31 CLOSED via atomic append-position allocation.")
  (println "FAIL — see above (a duplicate position, a missing def, or a reject means D is wrong)."))
