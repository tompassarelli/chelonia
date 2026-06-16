;; datalog_test.clj — positive + recursive Datalog, semantic conformance to the
;; oracle's fixtures (join, transitive closure, claim-pattern, repeated-var join).
;;   bb -cp out datalog_test.clj
(require '[chelonia.cnf :as c] '[chelonia.datalog :as d])

(def ctx (c/new-store))
(def tx (c/begin-tx! ctx "test"))
(def edge (c/value! ctx "edge"))
(def kind (c/value! ctx "kind"))
(def hub  (c/value! ctx "hub"))
(def a (c/entity! ctx)) (def b (c/entity! ctx)) (def cc (c/entity! ctx)) (def dd (c/entity! ctx))
(c/claim! ctx a edge b tx) (c/claim! ctx b edge cc tx) (c/claim! ctx cc edge dd tx)  ; a->b->c->d
(c/claim! ctx b kind hub tx) (c/claim! ctx cc kind hub tx)                            ; b,c are hubs

;; Fixture A — 2-literal positive join: pth(X,H) :- triple(X,edge,H), triple(H,kind,hub)
(def dbA (d/run-rules ctx [(d/rule "pth" [(d/v :x) (d/v :h)]
                                   [(d/lit "triple" [(d/v :x) edge (d/v :h)])
                                    (d/lit "triple" [(d/v :h) kind hub])])]))
(def pth (set (d/facts dbA "pth")))

;; Fixture B — transitive closure (recursion)
(def dbB (d/run-rules ctx [(d/rule "reaches" [(d/v :x) (d/v :y)] [(d/lit "triple" [(d/v :x) edge (d/v :y)])])
                           (d/rule "reaches" [(d/v :x) (d/v :z)]
                                   [(d/lit "triple" [(d/v :x) edge (d/v :y)])
                                    (d/lit "reaches" [(d/v :y) (d/v :z)])])]))
(def reaches (set (d/facts dbB "reaches")))

;; Fixture C — rule over claim(Cid,L,P,R): ec(Cid,X,Y) :- claim(Cid,X,edge,Y)
(def dbC (d/run-rules ctx [(d/rule "ec" [(d/v :cid) (d/v :x) (d/v :y)]
                                   [(d/lit "claim" [(d/v :cid) (d/v :x) edge (d/v :y)])])]))
(def ec-pairs (set (map (fn [t] [(nth t 1) (nth t 2)]) (d/facts dbC "ec"))))

;; repeated-var equality join: selfloop(N) :- triple(N,edge,N)  (after adding a->a)
(c/claim! ctx a edge a tx)
(def loops (set (d/facts (d/run-rules ctx [(d/rule "selfloop" [(d/v :n)] [(d/lit "triple" [(d/v :n) edge (d/v :n)])])]) "selfloop")))

;; --- stratified negation: not-terminal finite-complement --------------------
(def nx (c/new-store))
(def ntx (c/begin-tx! nx "neg"))
(def kindp (c/value! nx "kind")) (def nodev (c/value! nx "node"))
(def statusp (c/value! nx "status")) (def donev (c/value! nx "done"))
(def na (c/entity! nx)) (def nb (c/entity! nx)) (def ncc (c/entity! nx)) (def ndd (c/entity! nx))
(doseq [e [na nb ncc ndd]] (c/claim! nx e kindp nodev ntx))           ; all are nodes
(c/claim! nx na statusp donev ntx) (c/claim! nx nb statusp donev ntx) ; a,b terminal

(def strata
  [[(d/rule "node" [(d/v :n)] [(d/lit "triple" [(d/v :n) kindp nodev])])
    (d/rule "terminal" [(d/v :n)] [(d/lit "triple" [(d/v :n) statusp donev])])]
   [(d/rule "active" [(d/v :n)] [(d/lit "node" [(d/v :n)]) (d/nlit "terminal" [(d/v :n)])])]])
(def ndb (d/run-strata nx strata))
(def active (set (d/facts ndb "active")))
(def terminal (set (d/facts ndb "terminal")))

;; recursion-through-negation must be rejected (x negates x in its own stratum)
(def bad-strata
  [[(d/rule "node" [(d/v :n)] [(d/lit "triple" [(d/v :n) kindp nodev])])
    (d/rule "x" [(d/v :n)] [(d/lit "node" [(d/v :n)]) (d/nlit "x" [(d/v :n)])])]])

(def checks
  [["[A] 2-literal positive join"          (= #{[a b] [b cc]} pth)]
   ["[B] transitive closure (6 pairs)"     (= #{[a b] [a cc] [a dd] [b cc] [b dd] [cc dd]} reaches)]
   ["[B] closure size correct"             (= 6 (count reaches))]
   ["[C] rule over claim() 4-ary pattern"  (= #{[a b] [b cc] [cc dd]} ec-pairs)]
   ["[C] one derived per edge claim"       (= 3 (count (d/facts dbC "ec")))]
   ["repeated var = equality join"         (= #{[a]} loops)]
   ["[neg] terminal (positive, lower stratum)" (= #{[na] [nb]} terminal)]
   ["[neg] not-terminal finite-complement"     (= #{[ncc] [ndd]} active)]
   ["[neg] stratifiable program: no violations" (empty? (d/strata-violations strata))]
   ["[neg] recursion-through-negation rejected" (not (empty? (d/strata-violations bad-strata)))]])

(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\ncnf datalog (positive+recursive):" (count checks) "/" (count checks) "PASS")
    (do (println "\ncnf datalog:" (count fails) "FAILED") (System/exit 1))))
