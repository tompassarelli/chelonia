;; schema_test.clj — the CNF schema layer: cardinality-driven supersession,
;; refs, find-by, identity (name/rename/resolve). Mirrors the oracle's
;; schema.rkt/graph.rkt semantics.
;;   bb -cp out schema_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s])

(def ctx (c/new-store))
(def tx (c/begin-tx! ctx "test"))
(s/setup! ctx tx)
(s/def-predicate! ctx "title" "single" "literal" tx)
(s/def-predicate! ctx "tag" "multi" "literal" tx)
(s/def-predicate! ctx "depends_on" "multi" "ref" tx)

(def t (c/entity! ctx))
(s/name! ctx t "@t1" tx)
(s/assert! ctx t "title" "First" tx)
(def title1 (s/lookup ctx t "title"))
(s/assert! ctx t "title" "Second" tx)            ; single -> supersede "First"
(def title2 (s/lookup ctx t "title"))
(def title-all (s/lookup-all ctx t "title"))
(s/assert! ctx t "tag" "a" tx)
(s/assert! ctx t "tag" "b" tx)                   ; multi -> keep both
(def tags (s/lookup-all ctx t "tag"))
(def u (c/entity! ctx))
(s/name! ctx u "@u1" tx)
(s/link! ctx t "depends_on" u tx)                ; ref
(def deps (s/lookup-all ctx t "depends_on"))
(def resolved (s/resolve-name ctx "@t1"))
(def nm (s/name-of ctx t))
(s/name! ctx t "@t1-renamed" tx)                 ; rename -> supersede old name
(def nm2 (s/name-of ctx t))
(def resolved2 (s/resolve-name ctx "@t1-renamed"))
(def old-resolves (s/resolve-name ctx "@t1"))

(def checks
  [["assert! then lookup"                  (= "First" title1)]
   ["single-valued update supersedes"      (= "Second" title2)]
   ["lookup-all shows only the live value" (= ["Second"] title-all)]
   ["multi-valued keeps all values (ord)"  (= ["a" "b"] tags)]
   ["link! ref resolves to the entity"     (= [u] deps)]
   ["resolve-name -> the entity"           (= t resolved)]
   ["name-of returns the name"             (= "@t1" nm)]
   ["rename supersedes the old name"       (= "@t1-renamed" nm2)]
   ["rename: id stable, new name resolves" (= t resolved2)]
   ["old name no longer resolves"          (nil? old-resolves)]
   ["cardinality read: single"             (= "single" (s/cardinality ctx "title"))]
   ["cardinality read: multi"              (= "multi" (s/cardinality ctx "tag"))]
   ["unregistered predicate defaults multi" (= "multi" (s/cardinality ctx "nope"))]])

(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\ncnf schema:" (count checks) "/" (count checks) "PASS")
    (do (println "\ncnf schema:" (count fails) "FAILED") (System/exit 1))))
