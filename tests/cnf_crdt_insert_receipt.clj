;; ============================================================================
;; cnf_crdt_insert_receipt.clj — #36 the bar: concurrent MIDDLE-INSERTS commute
;;   bb -cp out cnf_crdt_insert_receipt.clj
;;
;; Two concurrent :edit-min insert-form ops, BOTH "insert after <anchor>" (the SAME
;; gap), through the real do-edit-min path. With the CRDT rep they compute the same
;; between-path on identical clones + get DISTINCT name-int ties -> DISTINCT keys ->
;; BOTH land strictly between the anchor and its old-next, no duplicate. This is the
;; thing append-only D could not do (middle-insert commute).
;;
;; SAFE: isolated daemon on a /tmp COPY of .fram/code.log; never 7977 / canonical log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")

(def tmp (str "/tmp/cnf-crdt-" (System/nanoTime) ".log"))
(io/copy (io/file ".fram/code.log") (io/file tmp))
(boot-flat! tmp)
(def st (:store @co))
(defn vof [e] (let [Vp (c/value-id st "v")] (some->> (c/by-lp st e Vp) first (c/claim-of st) :r (c/literal st))))
(defn forms-of [wrap]                              ; wrapper's children in CRDT order
  (->> (c/by-l st wrap)
       (keep (fn [cid] (let [cl (c/claim-of st cid) ks (c/literal st (:p cl)) k (resolve/ord-parse ks)]
                         (when k {:key k :keystr ks :child (:r cl)}))))
       (sort-by :key resolve/ord-cmp) vec))
(defn def-name [child]                             ; (def NAME ...) -> NAME (2nd child's v)
  (let [kids (->> (c/by-l st child)
                  (keep (fn [cid] (let [k (resolve/ord-parse (c/literal st (:p (c/claim-of st cid))))] (when k [k (c/claim-of st cid)]))))
                  (sort-by first resolve/ord-cmp))]
    (when (>= (count kids) 2) (vof (:r (second (nth kids 1)))))))
(defn wrapper [m]
  (let [NAME (c/value-id st "name") pfx (str "@" m "#")]
    (->> (c/by-p st NAME)
         (keep (fn [cid] (let [nm (c/literal st (:r (c/claim-of st cid)))] (when (and (string? nm) (str/starts-with? nm pfx)) (:l (c/claim-of st cid))))))
         (filter (fn [e] (let [fs (forms-of e)] (and (seq fs) (= "beagle-file" (vof (:child (first fs))))))))
         first)))

(println "=== #36 — concurrent MIDDLE-INSERTS commute ===")
(def M "kernel")
(def wrap (wrapper M))
(def pre (forms-of wrap))
(defn head-of [child] (vof (:child (first (forms-of child)))))   ; the form's head symbol (def/defn/ns/...)
(def ai (first (filter (fn [i] (and (< (inc i) (count pre))      ; a real def with a next sibling
                                    (#{"def" "defn" "defn-" "def-" "defonce"} (head-of (:child (nth pre i))))
                                    (def-name (:child (nth pre i)))))
                       (range (count pre)))))
(when (nil? ai) (println "FAIL: no def anchor with a next sibling found") (System/exit 1))
(def anchor-name (def-name (:child (nth pre ai))))
(def anchor-key (:key (nth pre ai)))
(def next-key (:key (nth pre (inc ai))))
(println "anchor:" anchor-name " gap between keys" (:keystr (nth pre ai)) "and" (:keystr (nth pre (inc ai))))

(def fA (future (handle {:op :edit-min :spec {:op "insert-form" :module M :after anchor-name :datum (list 'def 'fram_ins_A 1)}})))
(def fB (future (handle {:op :edit-min :spec {:op "insert-form" :module M :after anchor-name :datum (list 'def 'fram_ins_B 2)}})))
(def rA @fA) (def rB @fB)
(println "insert A ->" (select-keys rA [:ok :reject]) "  insert B ->" (select-keys rB [:ok :reject]))

(def post (forms-of wrap))
(def insA (first (filter #(= "fram_ins_A" (def-name (:child %))) post)))
(def insB (first (filter #(= "fram_ins_B" (def-name (:child %))) post)))
(defn between? [k] (and (neg? (resolve/ord-cmp anchor-key k)) (neg? (resolve/ord-cmp k next-key))))
(def keystrs (map :keystr post))
(def dup? (not= (count keystrs) (count (distinct keystrs))))
(println "A key:" (:keystr insA) " B key:" (:keystr insB))
(println "both present:" (and insA insB true) " both between anchor&next:" (and insA insB (between? (:key insA)) (between? (:key insB)))
         " distinct keys:" (and insA insB (not= (:keystr insA) (:keystr insB))) " duplicate-index:" dup? " +2 forms:" (= (count post) (+ 2 (count pre))))

(println "\n=== VERDICT ===")
(if (and (:ok rA) (:ok rB) insA insB
         (between? (:key insA)) (between? (:key insB))
         (not= (:keystr insA) (:keystr insB)) (not dup?) (= (count post) (+ 2 (count pre))))
  (println "PASS — two concurrent MIDDLE-INSERTS at the same gap both landed at DISTINCT CRDT keys,"
           "strictly between the anchor and its old-next, zero duplicate index, +2 forms. Insert-anywhere"
           "COMMUTE — the thing D (append-only) could not do.")
  (println "FAIL — see above."))
