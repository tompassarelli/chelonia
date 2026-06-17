;; tools_test.clj — the schema-derived tool catalog + dispatch.
;; Proves: (1) the catalog is GENERATED from the vocabulary — single vs multi vs
;; ref decide which tools exist; (2) read tools return correct values off the fold;
;; (3) write tools return a coordinator-routable intent (with @-normalized refs);
;; (4) the `query` tool reaches fram.query; (5) unknown tool -> :error.
;;   bb -cp out tools_test.clj
(require '[fram.kernel :as k] '[fram.tools :as t])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

(def claims
  [(k/->Claim "@x" "title" "X thread")     ; single, literal
   (k/->Claim "@x" "owner" "personal")     ; single, literal
   (k/->Claim "@x" "depends_on" "@y")      ; multi, ref
   (k/->Claim "@y" "title" "Y thread")])

(def idx (k/build-index claims))
(def cat (t/catalog claims))
(defn has-tool? [nm] (boolean (some #(= (:name %) nm) cat)))
(defn call [tool args] (t/call claims idx cat tool args))

;; (1) catalog generated from vocabulary
(chk "single literal -> <pred>-of + set-<pred>"      (and (has-tool? "owner-of") (has-tool? "set-owner")))
(chk "single -> NO <pred>-list"                       (not (has-tool? "owner-list")))
(chk "multi ref -> <pred>-list + add/remove"          (and (has-tool? "depends_on-list")
                                                           (has-tool? "add-depends_on")
                                                           (has-tool? "remove-depends_on")))
(chk "ref pred -> reverse-edge <pred>-from"           (has-tool? "depends_on-from"))
(chk "literal pred -> NO <pred>-from (not a ref)"     (not (has-tool? "title-from")))
(chk "structural tools present"                       (and (has-tool? "threads") (has-tool? "dependents-of")
                                                           (has-tool? "validate") (has-tool? "show")
                                                           (has-tool? "query")))

;; (2) reads
(chk "owner-of @x = personal (bare id accepted)"      (= (:rows (call "owner-of" {:id "x"})) ["personal"]))
(chk "title-of @x = X thread (@-prefixed accepted)"   (= (:rows (call "title-of" {:id "@x"})) ["X thread"]))
(chk "depends_on-list @x = [@y]"                      (= (:rows (call "depends_on-list" {:id "x"})) ["@y"]))
(chk "depends_on-from @y = [@x] (reverse edge)"       (= (:rows (call "depends_on-from" {:id "y"})) ["@x"]))
(chk "dependents-of @y = [@x]"                        (= (:rows (call "dependents-of" {:id "y"})) ["@x"]))
(chk "threads lists @x and @y with titles"
     (= (set (map :id (:rows (call "threads" {})))) #{"@x" "@y"}))

;; (3) writes return a coordinator intent (refs @-normalized)
(chk "set-owner -> assert intent"
     (= (:write (call "set-owner" {:id "x" :value "work"}))
        {:op "assert" :l "@x" :p "owner" :r "work"}))
(chk "add-depends_on -> assert intent, value @-normalized"
     (= (:write (call "add-depends_on" {:id "x" :value "z"}))
        {:op "assert" :l "@x" :p "depends_on" :r "@z"}))
(chk "remove-depends_on -> retract intent"
     (= (:write (call "remove-depends_on" {:id "x" :value "@y"}))
        {:op "retract" :l "@x" :p "depends_on" :r "@y"}))

;; (4) the query tool reaches fram.query (transitive over the same fold)
(let [r (call "query"
          {:query {:find "reaches"
                   :rules [{:head {:rel "reaches" :args [{:var "a"} {:var "b"}]}
                            :body [{:rel "triple" :args [{:var "a"} "depends_on" {:var "b"}]}]}]}})]
  (chk "query tool returns :ok with the edge" (contains? (set (:ok r)) ["@x" "@y"])))

;; (5) unknown tool
(chk "unknown tool -> :error" (contains? (call "nope" {}) :error))

;; (6) FIXES from adversarial review
;; 6a. catalog names are unique (collision dedupe; structural reserved)
(chk "catalog has no duplicate tool names" (= (count (map :name cat)) (count (set (map :name cat)))))
;; 6b. server-side required-param enforcement (the "can't emit a broken call" guarantee)
(chk "missing required id -> :error"    (contains? (call "owner-of" {}) :error))
(chk "missing required value -> :error" (contains? (call "set-owner" {:id "x"}) :error))
(chk "query tool missing :query -> :error" (contains? (call "query" {}) :error))
;; 6c. mixed-ref predicate: literal write value stored VERBATIM (not spuriously @-prefixed),
;; so remove-P actually matches the stored claim. (depends_on, pure-ref, still @-normalizes.)
(let [mc [(k/->Claim "@x" "tag" "@refnode") (k/->Claim "@x" "tag" "plainword")]
      mi (k/build-index mc) mcat (t/catalog mc)]
  (chk "mixed-ref remove keeps literal verbatim (no spurious @)"
       (= (:write (t/call mc mi mcat "remove-tag" {:id "x" :value "plainword"}))
          {:op "retract" :l "@x" :p "tag" :r "plainword"}))
  (chk "mixed-ref still exposes <pred>-from (has @-values)"
       (boolean (some #(= (:name %) "tag-from") mcat))))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.tools:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.tools:" (count fails) "FAILED") (System/exit 1))))
