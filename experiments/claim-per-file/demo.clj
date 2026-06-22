#!/usr/bin/env bb
;; demo — exercises the four load-bearing behaviors of the claim-per-file store:
;;   1. write + fold (UUIDv7 ordering gives last-write-wins for single-valued preds)
;;   2. supersede / retract via causal edges
;;   3. ATOMIC multi-claim tx via a commit-claim (members invisible until commit lands)
;;   4. federation: two coordinator-less stores merge by union of files
(require '[babashka.fs :as fs])
(load-file (str (fs/parent *file*) "/cpf.clj"))
(require '[cpf :as c])

(defn fresh [n] (let [d (str "/tmp/cpf-demo-" n)] (fs/delete-tree d) (c/ensure-store d) d))
(defn line [s] (println (str "\n=== " s " ===")))

;; --- 1. write + single-valued last-write-wins -------------------------------
(line "1. write + last-write-wins (single-valued title)")
(let [d (fresh "lww")]
  ;; declare `title` single-valued (graph-sourced cardinality, like the real engine)
  (c/put-claim d {:l "title" :p "cardinality" :r "single"})
  (c/put-claim d {:l "@site" :p "title" :r "Old name"})
  (Thread/sleep 2)                                   ; ensure a later ms (also covered by intra-ms ctr)
  (c/put-claim d {:l "@site" :p "title" :r "New name"})
  (let [{:keys [state]} (c/load-store d)]
    (println "  @site title =>" (get state ["@site" "title"]))
    (assert (= "New name" (get state ["@site" "title"])) "last write must win")))

;; --- 2. supersede + retract via causal edges --------------------------------
(line "2. supersede / retract edges")
(let [d (fresh "edges")]
  (c/put-claim d {:l "tag" :p "cardinality" :r "single"}) ; keep single sets out of this case
  (let [a (c/put-claim d {:l "@x" :p "note" :r "draft"})]
    ;; multi-valued note accumulates...
    (c/put-claim d {:l "@x" :p "note" :r "revised"})
    ;; ...but an explicit supersede of `a` removes the draft regardless of order
    (c/put-claim d {:l "@x" :p "note" :r "final" :supersedes [a]})
    (let [{:keys [state]} (c/load-store d)]
      (println "  @x note =>" (get state ["@x" "note"]))
      (assert (= #{"revised" "final"} (get state ["@x" "note"])) "draft superseded out"))))

;; --- 3. ATOMIC multi-claim tx via commit-claim ------------------------------
(line "3. atomic tx — members invisible until the commit-claim lands")
(let [d (fresh "tx")]
  (c/put-claim d {:l "item" :p "cardinality" :r "single"})
  (c/put-claim d {:l "qty"  :p "cardinality" :r "single"})
  (let [m1  (c/stage-member d {:l "@order" :p "item" :r "widget"})
        m2  (c/stage-member d {:l "@order" :p "qty"  :r "3"})]
    ;; BEFORE the commit lands: members are staged on disk but MUST be invisible
    (let [{:keys [state dropped]} (c/load-store d)]
      (println "  pre-commit  @order =>" (get state ["@order" "item"]) "  (dropped:" (count dropped) "pending)")
      (assert (nil? (get state ["@order" "item"])) "staged members must not be visible pre-commit"))
    ;; land the commit referencing both members -> they go live atomically
    (c/put-commit d {:members [m1 m2] :by "agent-a"})
    (let [{:keys [state dropped]} (c/load-store d)]
      (println "  post-commit state =>" state "  (dropped:" (count dropped) ")")
      (assert (= "widget" (get state ["@order" "item"])) "member visible after commit")
      (assert (= "3"      (get state ["@order" "qty"]))  "all-or-nothing"))))

;; --- 4. federation: two coordinator-less stores merge by union --------------
(line "4. federation — union of files, no coordinator, causal order preserved")
(let [a (fresh "fed-a") b (fresh "fed-b") m (fresh "fed-merged")]
  (c/put-claim a {:l "owner" :p "cardinality" :r "single"})
  (c/put-claim a {:l "@doc" :p "owner" :r "alice"})      ; alice writes offline in store A
  (Thread/sleep 3)
  (c/put-claim b {:l "@doc" :p "owner" :r "bob"})        ; bob writes LATER offline in store B
  ;; merge both into a third store = pure file union (no coordination, any order)
  (c/merge-into! m a) (c/merge-into! m b)
  (let [{:keys [state claims]} (c/load-store m)]
    (println "  merged @doc owner =>" (get state ["@doc" "owner"]) "(later-id wins: bob)")
    (println "  merged claim count =>" (count claims))
    (assert (= "bob" (get state ["@doc" "owner"])) "UUIDv7 time-order resolves the conflict deterministically")))

(println "\nALL DEMO ASSERTIONS PASSED")
