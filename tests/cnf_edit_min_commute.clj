;; ============================================================================
;; cnf_edit_min_commute.clj — GATE 2 (THE MAKE-OR-BREAK): two edits to DIFFERENT
;; defns in the SAME module COMMUTE through ONE warm coordinator.
;; ============================================================================
;; The thesis in miniature: disjoint same-module edits must BOTH survive — the exact
;; thing the whole-module path (git's weakness) breaks (second clobbers first /
;; false-conflict). We set-body fnA (`cardinality`) AND set-body fnB (`lookup`) in
;; `schema` through the SAME warm :edit-min daemon. PASS = BOTH new bodies present in
;; render(log) AND schema recompiles 1/0 AND neither got :conflict. Then we run the
;; WHOLE-MODULE path on the SAME scenario and show it does NOT commute (the contrast).
;;   bb -cp out cnf_edit_min_commute.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[babashka.process :as proc])
(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (or (System/getenv "FRAM_ROUNDTRIP") (str beagle-home "/beagle-lib/private/claims-roundtrip.rkt")))
(def build-all (or (System/getenv "FRAM_BUILD_ALL") (str beagle-home "/bin/beagle-build-all")))
(def code-log (str root "/.fram/code.log"))
(def base-env {"BEAGLE_HOME" beagle-home "FRAM_OUT" (str root "/out")
               "FRAM_ROUNDTRIP" roundtrip-rkt "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")})
(doseq [p [code-log roundtrip-rkt]]
  (when-not (.exists (io/file p)) (println "SKIP — missing" p) (System/exit 0)))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(defn render-schema [flat tag]
  (let [out (str (System/getProperty "java.io.tmpdir") "/commute-" tag "-" (System/nanoTime) ".bclj")]
    (proc/shell {:continue true :extra-env base-env :err :string}
                "bb" "-cp" "out" "bin/fram-render-code" "schema" "--log" flat "--out" out)
    (when (.exists (io/file out)) (slurp out))))
(defn recompiles? [render-txt]
  (let [work (str (System/getProperty "java.io.tmpdir") "/commute-rc-" (System/nanoTime))
        src (str work "/src") out (str work "/out")]
    (.mkdirs (io/file src)) (.mkdirs (io/file out))
    (spit (str src "/schema.bclj") render-txt)
    (let [r (proc/sh {:out :string :err :string} build-all src "--out" out)]
      (boolean (re-find #"\b1 built, 0 error\(s\)" (str (:out r) (:err r)))))))

;; two DISJOINT, type-correct bodies. cardinality :- String ; lookup :- Any.
(def body-card (edn/read-string "(if (some? (c/value-id ctx pname)) \"single-or-multi\" \"multi\")"))
(def body-lookup (edn/read-string "(first (lookup-all ctx subj pname))"))
;; markers proving each NEW body landed (and the OLD one is gone).
(def card-marker "single-or-multi")
(def lookup-marker "(first (lookup-all ctx subj pname))")

;; ============================================================================
;; PATH 1 — :edit-min through ONE warm daemon (the claim path).
;; ============================================================================
(def flat (str (System/getProperty "java.io.tmpdir") "/commute-min-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(def port (or (some #(when (port-free? %) %) [8170 8171 8172 8173]) 8170))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))

;; both edits go through the SAME warm coordinator. Each :edit-min clones the live
;; store and commits ONLY its own (te,p) groups — cardinality's body fN vs lookup's
;; body fN are DISJOINT groups, so neither OCC-conflicts the other.
(def edit-a (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "cardinality" :datum body-card}}))
(def edit-b (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "lookup" :datum body-lookup}}))
(println "edit A (cardinality):" (pr-str edit-a))
(println "edit B (lookup)     :" (pr-str edit-b))
(def no-conflict (and (:ok edit-a) (:ok edit-b)
                      (not (and (:reject edit-a) (str/includes? (str (:reject edit-a)) "conflict")))
                      (not (and (:reject edit-b) (str/includes? (str (:reject edit-b)) "conflict")))))
(def render-min (render-schema flat "min"))
(def both-present (boolean (and render-min
                                (str/includes? render-min card-marker)
                                (str/includes? render-min lookup-marker))))
(def min-recompiles (and render-min (recompiles? render-min)))
(shutdown!)

;; ============================================================================
;; PATH 2 — WHOLE-MODULE on the SAME scenario (the contrast). Two CONCURRENT
;; whole-module edits each computed against the SAME pre-edit log: render fnA-edit
;; AND fnB-edit independently (each from the ORIGINAL log via --no-commit), then
;; commit BOTH through one daemon. The whole-module path renumbers the WHOLE module,
;; so the two commits collide on the SAME (te,p) groups => the second false-conflicts
;; or clobbers the first (only ONE body survives).
;; ============================================================================
(def flat-w (str (System/getProperty "java.io.tmpdir") "/commute-whole-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat-w))
(def portw (or (some #(when (port-free? %) %) [8180 8181 8182 8183]) 8180))
(boot-flat! flat-w)
(def serverw (future (serve portw)))
(Thread/sleep 500)
(def bf-card (str (System/getProperty "java.io.tmpdir") "/commute-bc-" (System/nanoTime) ".edn"))
(def bf-lookup (str (System/getProperty "java.io.tmpdir") "/commute-bl-" (System/nanoTime) ".edn"))
(spit bf-card (pr-str body-card)) (spit bf-lookup (pr-str body-lookup))
;; CONCURRENT whole-module: each edit is RENDERED from a PRISTINE copy of the original
;; log (so both see the SAME pre-edit state — true concurrency, neither sees the
;; other), THEN both whole-module deltas are committed through the SAME daemon. The
;; whole-module path re-keys the ENTIRE module to @schema#1..n and diffs vs the
;; coordinator, so the two deltas overlap on the SAME (te,p) groups: committing B's
;; pristine-rendered whole-module delta RE-ASSERTS the old cardinality body and
;; RETRACTS A's — the clobber. (--no-commit renders only; --log is the pristine base.)
(def pristine-a (str (System/getProperty "java.io.tmpdir") "/commute-pa-" (System/nanoTime) ".code.log"))
(def pristine-b (str (System/getProperty "java.io.tmpdir") "/commute-pb-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file pristine-a))
(io/copy (io/file code-log) (io/file pristine-b))
(def render-a (str (System/getProperty "java.io.tmpdir") "/commute-ra-" (System/nanoTime) ".bclj"))
(def render-b (str (System/getProperty "java.io.tmpdir") "/commute-rb-" (System/nanoTime) ".bclj"))
;; 1. render both whole-module edits from pristine bases (no commit).
(proc/shell {:continue true :extra-env (merge (into {} (System/getenv)) base-env) :err :string}
            "bb" "-cp" "out" "bin/fram-edit-code" "set-body" "schema"
            "--name" "cardinality" "--body-file" bf-card "--no-commit" "--out" render-a "--log" pristine-a)
(proc/shell {:continue true :extra-env (merge (into {} (System/getenv)) base-env) :err :string}
            "bb" "-cp" "out" "bin/fram-edit-code" "set-body" "schema"
            "--name" "lookup" "--body-file" bf-lookup "--no-commit" "--out" render-b "--log" pristine-b)
;; 2. commit BOTH whole-module deltas through the daemon (A first, then B — B's delta
;;    was computed against the PRISTINE base, concurrent with A).
(def w-a (proc/shell {:continue true :extra-env (merge (into {} (System/getenv)) base-env) :out :string :err :string}
                     "bb" "-cp" "out" "bin/fram-commit-code" "schema" render-a "--port" (str portw)))
(def w-b (proc/shell {:continue true :extra-env (merge (into {} (System/getenv)) base-env) :out :string :err :string}
                     "bb" "-cp" "out" "bin/fram-commit-code" "schema" render-b "--port" (str portw)))
(println "\nwhole-module commit A exit:" (:exit w-a) " B exit:" (:exit w-b))
(println "  whole B output:" (str/trim (str (:out w-b) (:err w-b))))
(def render-whole (render-schema flat-w "whole"))
(def whole-both (boolean (and render-whole
                              (str/includes? render-whole card-marker)
                              (str/includes? render-whole lookup-marker))))
(try (future-cancel serverw) (catch Throwable _ nil))

;; ============================================================================
(println "\n=== GATE 2 — COMMUTATION (two disjoint same-module edits) ===")
(println (format "  [claim/:edit-min] edit A ok=%s  edit B ok=%s  no-conflict=%s"
                 (boolean (:ok edit-a)) (boolean (:ok edit-b)) no-conflict))
(println (format "  [claim/:edit-min] BOTH bodies present in render(log): %s" both-present))
(println (format "  [claim/:edit-min] render(log) recompiles 1/0:        %s" min-recompiles))
(println (format "  [whole-module]    BOTH bodies present (contrast):     %s   <- expect FALSE (clobber/conflict)" whole-both))

(def pass (and no-conflict both-present min-recompiles))
(if pass
  (do (println "\nGATE 2 PASS — disjoint same-module edits COMMUTE on the claim graph "
               "(both survive, recompiles). The whole-module path " (if whole-both "ALSO kept both (no contrast)" "did NOT (it clobbered/conflicted) — the contrast holds") ".")
      (System/exit 0))
  (do (println "\nGATE 2 FAIL — the crown jewel is dead: disjoint same-module edits did NOT both survive.")
      (System/exit 1)))
