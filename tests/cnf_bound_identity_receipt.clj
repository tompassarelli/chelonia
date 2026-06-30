;; ============================================================================
;; cnf_bound_identity_receipt.clj — #(a) identity arc: bound_to is DURABLE but
;; FILTERED from read projections.  R1 (durable edge) + R6 (read-side unaffected).
;;   bb -cp out cnf_bound_identity_receipt.clj
;;
;; After an :edit-min rename on an in-process /tmp daemon:
;;   R1  the flat log carries durable `bound_to` lines (count > 0).
;;   R6a :warm-check returns :consistent true (the incrementally-maintained warm
;;       cache == a fresh whole rebuild — persisting+filtering bound_to didn't
;;       diverge the cache).
;;   R6b a :query for ALL triples does NOT surface any `bound_to` triple
;;       (read-hidden-preds keeps the durable identity edge out of :query/datalog/
;;       warm-cache — option-1 scope: render+resolve read it off the store directly).
;;
;; SAFE: a /tmp COPY of .fram/code.log + in-process daemon (boot-flat!, handle{});
;; NO socket, NEVER port 7977, NEVER the canonical tern log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[fram.cnf :as c] '[fram.fold :as fold] '[fram.query :as q] '[fram.rt])
(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — missing" code-log) (System/exit 0))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

;; /tmp copy — the daemon writes bound_to back into THIS file, never the source.
(def flat (str (System/getProperty "java.io.tmpdir") "/bound-identity-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)

;; rename a real schema helper with in-module references (same target the R4 receipt uses).
(def resp (handle {:op :edit-min :spec {:op "rename" :module "schema" :old "replace!" :new "supersede-prior!"}}))
(println "rename replace! -> supersede-prior!:" (pr-str resp))

;; ---- R1: durable bound_to lines in the flat log ----------------------------
(def flat-txt (slurp flat))
(def bound-lines (->> (str/split-lines flat-txt) (filter #(str/includes? % "\"bound_to\"")) vec))
(def r1 (pos? (count bound-lines)))
(println "\n=== R1 — durable bound_to in the flat log ===")
(println "  bound_to lines:" (count bound-lines))
(doseq [l (take 4 bound-lines)] (println "   " l))

;; ---- R6a: warm cache stays consistent with a fresh rebuild -----------------
(def wc (handle {:op :warm-check}))
(def r6a (true? (:consistent wc)))
(println "\n=== R6a — warm-check after the rename ===")
(println "  :consistent" (:consistent wc) " inc-triples" (:inc-triples wc) " fresh-triples" (:fresh-triples wc))

;; ---- R6b: a :query for ALL triples surfaces NO bound_to --------------------
;; Datalog over the base `triple(l,p,r)` relation, binding p so we read the predicate.
;; If bound_to leaked into the read projection it would appear as a (l "bound_to" r) row.
(def ALLQ {:find "out"
           :rules [{:head {:rel "out" :args [{:var "l"} {:var "p"} {:var "r"}]}
                    :body [{:rel "triple" :args [{:var "l"} {:var "p"} {:var "r"}]}]}]})
(def qres (handle {:op :query :query ALLQ}))
(def all-rows (:ok qres))
(def bound-rows (filter (fn [[_ p _]] (= "bound_to" p)) all-rows))
;; also confirm refers_to (a resolve-pred) is absent (sanity: read-side really is filtered)
(def refers-rows (filter (fn [[_ p _]] (= "refers_to" p)) all-rows))
(def r6b (and (seq all-rows) (empty? bound-rows) (empty? refers-rows)))
(println "\n=== R6b — :query (all triples) does NOT surface bound_to ===")
(println "  total triple rows:" (count all-rows)
         " bound_to rows:" (count bound-rows)
         " refers_to rows:" (count refers-rows)
         " engine:" (:engine qres))

(println "\n=== VERDICT ===")
(if (and (:ok resp) r1 r6a r6b)
  (do (println "PASS — bound_to is DURABLE (R1: flat-log lines) yet INVISIBLE to the read view"
               "(R6a: warm cache consistent; R6b: :query surfaces zero bound_to/refers_to rows).")
      (System/exit 0))
  (do (println "FAIL —"
               "rename-ok" (boolean (:ok resp)) "R1" r1 "R6a" r6a "R6b" r6b)
      (System/exit 1)))
