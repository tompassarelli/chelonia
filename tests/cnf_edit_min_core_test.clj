;; ============================================================================
;; cnf_edit_min_core_test.clj — IN-PROCESS green-core proof for the minimal-op
;; authoring path (no socket, no Beagle → no #14 flakiness). Proves do-edit-min
;; commits the verb's exact delta for the SAFE-on-spelling verbs (set-body /
;; upsert-form) and that graph RENAME is explicitly REJECTED (identity-deferred),
;; never silently rewriting only the def.
;;   bb -cp out cnf_edit_min_core_test.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s] '[clojure.edn :as edn] '[clojure.java.io :as io])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-core-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)
(def st (:store @co))
(def Vp (c/value-id st "v"))
(defn spell-count [s] (count (filter (fn [cid] (= s (c/literal st (:r (c/claim-of st cid))))) (c/by-p st Vp))))
(def fails (atom 0))
(defn chk [name ok] (if ok (println "  [PASS]" name) (do (swap! fails inc) (println "  [FAIL]" name))))

;; edit-min! mirrors the daemon's :edit-min handler arm EXACTLY — (try (do-edit-min ...)
;; (catch Throwable -> {:reject ...})) — so the rename guard's throw becomes the same
;; {:reject ...} a client sees, without paying handle's per-call maybe-reload! re-fold.
(defn edit-min! [spec]
  (try (do-edit-min spec)
       (catch Throwable t {:reject [(str "edit-min: " (.getMessage t))] :version (current-seq @co)})))

;; --- set-body: commit a new body carrying a UNIQUE marker token, in-process -----
(def marker "uniqzzqmarker")
(def before-marker (spell-count marker))
(def sb (edit-min! {:op "set-body" :module "schema" :name "cardinality"
                    :datum (edn/read-string (str "(if (some? (c/value-id ctx pname)) (" marker " pname) \"multi\")"))}))
(chk "set-body committed (ok, ops>0)" (and (:ok sb) (pos? (or (:ops sb) 0))))
(chk "set-body's new marker token landed in the store" (and (zero? before-marker) (pos? (spell-count marker))))

;; --- upsert-form: add a NEW top-level def, in-process ---------------------------
(def newfn "uniqzzqnewdef")
(def uf (edit-min! {:op "upsert-form" :module "schema"
                    :datum (edn/read-string (str "(defn " newfn " [x] x)"))}))
(chk "upsert-form committed (ok, ops>0)" (and (:ok uf) (pos? (or (:ops uf) 0))))
(chk "upsert-form's new def name landed in the store" (pos? (spell-count newfn)))

;; --- #25 LOCK: upsert-form is NAME-keyed — a REPLACE preserves the def name (CANNOT rename) ---
;; This is the receipt behind the rename-ONLY guard's sufficiency: the only :edit-min verb that
;; changes a binding's spelling is `rename` (guarded). If a future refactor made upsert node/
;; position-keyed, a (defn cardinality …) -> different-name swap could rename past the guard;
;; this asserts it doesn't — the name survives a body replace.
(def card-before (spell-count "cardinality"))
(def upmark "uniqzzqupsertrepl")
(def uf2 (edit-min! {:op "upsert-form" :module "schema"
                     :datum (edn/read-string (str "(defn cardinality [ctx pname] (" upmark " pname))"))}))
(chk "upsert-form REPLACE of existing def committed" (:ok uf2))
(chk "upsert REPLACE PRESERVED the def name (cardinality still present — not renamed)"
     (and (pos? card-before) (pos? (spell-count "cardinality"))))
(chk "upsert REPLACE landed the new body token" (pos? (spell-count upmark)))

;; --- rename: MUST be rejected (identity-deferred), MUST NOT mutate spellings -----
(def before-replace (spell-count "replace!"))
(def rn (edit-min! {:op "rename" :module "schema" :old "replace!" :new "supersede-prior!"}))
(chk "graph rename REJECTED (not :ok)" (not (:ok rn)))
(chk "rename reject mentions identity-deferred"
     (boolean (re-find #"(?i)identity|deferred" (pr-str (:reject rn)))))
(chk "rename did NOT silently rewrite the def spelling (replace! count unchanged)"
     (= before-replace (spell-count "replace!")))
(chk "rename did NOT partially introduce the new spelling" (zero? (spell-count "supersede-prior!")))

;; --- #26: unknown verb rejects cleanly (must NOT hard-exit the daemon) ---
;; If the unknown-op path still fell through to run-verb-warm!'s (System/exit 2), this whole
;; test JVM would die here and NONE of the assertions below would run — so reaching them is itself
;; the proof that the daemon does not hard-exit on a malformed :edit-min verb.
(def bogus (edit-min! {:op "bogus-verb-zzz" :module "schema"}))
(chk "unknown :edit-min verb rejected (not :ok)" (not (:ok bogus)))
(chk "unknown-verb reject names it as unknown" (boolean (re-find #"(?i)unknown" (pr-str (:reject bogus)))))
(chk "process continued after unknown-verb (no hard-exit)" true)

(println (str "\n---- edit-min CORE (in-process): " (if (zero? @fails) "ALL PASS" (str @fails " FAIL")) " ----"))
(System/exit (if (zero? @fails) 0 1))
