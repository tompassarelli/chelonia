;; ============================================================================
;; cnf_code_flip_test.clj — THE FLIP gate (CODE source-of-truth demotion).
;; ============================================================================
;; Sibling of cnf_flip_test.clj (which is the THREAD cutover, NOT the code flip).
;; Drives the KEYSTONE + gates 3/4/5 over a THROWAWAY daemon booted on a /tmp COPY
;; of .fram/code.log (the canonical CODE log), with trap-kill and a :status sanity
;; assertion (the daemon's :log MUST be our code-log copy before any result is
;; trusted). NEVER touches port 7977 or the lodestar log.
;;
;; PROVES (the flip thesis: the .bclj is a pure function of the CODE claim log):
;;   K  KEYSTONE  render(log) == render(text-path) BYTE-IDENTICAL (the documented
;;               normalization: the renderer canonicalizes #lang -> (define-target)
;;               and reflows comments, so byte-identity is vs render(TEXT), which is
;;               the projection the existing authoring edge already trusts), AND
;;               after a set-body delta committed THROUGH the coordinator,
;;               render(log) recompiles '0 error'.
;;   3  INGEST (lossless)  emit-edn(schema) re-keyed @schema#n == the warm store's
;;               @schema#* AST claims (read off the daemon); symdiff 0.
;;   4  CROSS-FRAME  a bridge claim @schema#<node> relates_to @<foreign-thread>
;;               (code-owned subject, foreign-thread object) commits through the ONE
;;               coordinator and reads back — proof the code + thread frames compose.
;;   5  WARM READS  ensure-refers! then :callers / :query off the warm materialized
;;               refers_to over the CODE-log-booted store returns real rows.
;;
;; Gates 1 (rename = one triple) and 2 (recompiles) are driven by the bin scripts +
;; experiments/flip/run-gates.sh; this in-process test covers K + 3/4/5.
;;
;;   bb -cp out cnf_code_flip_test.clj      (run from the fram repo root)
;; Needs: racket + bb + out/ + chartroom/src/resolve.clj + beagle + .fram/code.log
;; (run bin/fram-ingest-code first). Skips with a clear message if a prereq is missing.
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[fram.fold :as fold] '[fram.rt]
         '[clojure.set :as set] '[clojure.string :as str]
         '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[babashka.process :as proc])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))

(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (or (System/getenv "FRAM_ROUNDTRIP") (str beagle-home "/beagle-lib/private/claims-roundtrip.rkt")))
(def build-all (or (System/getenv "FRAM_BUILD_ALL") (str beagle-home "/bin/beagle-build-all")))
(def code-log (str root "/.fram/code.log"))

(def needed
  [[roundtrip-rkt "claims-roundtrip.rkt"]
   [build-all "beagle-build-all"]
   [(str root "/chartroom/src/resolve.clj") "chartroom resolve.clj"]
   [(str root "/out/fram/tools.clj") "out/ (build first)"]
   [(str root "/src/fram/schema.bclj") "src/fram/schema.bclj"]
   [code-log ".fram/code.log (run bin/fram-ingest-code first)"]])
(doseq [[p label] needed]
  (when-not (.exists (io/file p))
    (println "SKIP — missing prerequisite:" label "(" p ")") (System/exit 0)))

;; --- load the daemon machinery + resolver IN-PROCESS (cnf_dropin_test pattern) --
(load-file "cnf_coord_daemon.clj")
(load-file (str root "/chartroom/src/resolve.clj"))

;; --- throwaway daemon over a /tmp COPY of the code log ----------------------
(def flat (str (System/getProperty "java.io.tmpdir") "/code-flip-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
;; verified-free high port (re-probe pattern); NEVER 7977.
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) [7992 7993 7994 7991 7999]) 7992))

(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)

;; trap-kill: ALWAYS stop the throwaway daemon, whatever happens below.
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))

;; --- :status SANITY — refuse to trust any result unless the daemon serves OUR
;;     code-log copy (mirrors graph_arm.sh STATUS_OK). Falsifies "wrong log".
(def status (client port {:op :status}))
(def status-ok (and (= flat (str (:log status))) (pos? (:claims status))))
(chk "SANITY: daemon :status :log == our /tmp code-log copy (not 7977/lodestar)" status-ok)
(when-not status-ok
  (println "  ABORT: daemon serves" (pr-str (:log status)) "expected" flat) (shutdown!) (System/exit 1))

;; ===========================================================================
;; GATE 3 — INGEST lossless: emit-edn(schema) re-keyed @schema#n == warm AST.
;; ===========================================================================
(defn- emit-edn-triples [path module]
  (let [r (proc/sh {:out :string :err :string} "racket" roundtrip-rkt "--emit-edn" path)]
    (into #{}
          (->> (str/split-lines (:out r))
               (keep (fn [line]
                       (when (str/starts-with? line "[")
                         (let [[sb p o] (edn/read-string line)]
                           [(str "@" module "#" sb) (str p)
                            (if (integer? o) (str "@" module "#" o) (str o))]))))))))

;; the warm store's @schema#* AST triples (the daemon's read view filters out
;; refers_to/render markers, so this is the raw AST).
(defn- warm-ast-triples [module]
  (let [st (:store @co)]
    (into #{}
          (->> (c/current-claims st)
               (keep (fn [cid]
                       (let [cl (c/claim-of st cid)
                             l  (s/name-of st (:l cl))
                             p  (c/literal st (:p cl))]
                         (when (and l (str/starts-with? l (str "@" module "#"))
                                    (not (#{"name" "cardinality" "value_kind" "cnf-supersedes"} p))
                                    (not (#{"refers_to" "keep_spelling" "qualifier" "ctor_prefix" "accessor_field" "supersedes"} p)))
                           [l p (if (c/value-object? st (:r cl)) (c/literal st (:r cl)) (s/name-of st (:r cl)))]))))))))

(def emit-set (emit-edn-triples (str root "/src/fram/schema.bclj") "schema"))
(def warm-set (warm-ast-triples "schema"))
;; the warm set lacks the @schema#root file claim's churn; compare AST only (drop root).
(def emit-ast (set (remove (fn [[l _ _]] (= l "@schema#root")) emit-set)))
(def warm-ast (set (remove (fn [[l _ _]] (= l "@schema#root")) warm-set)))
(def only-emit (set/difference emit-ast warm-ast))
(def only-warm (set/difference warm-ast emit-ast))
(chk "GATE 3 INGEST: emit-edn(schema) re-keyed == warm-store AST (symdiff 0)"
     (and (empty? only-emit) (empty? only-warm)))
(when (or (seq only-emit) (seq only-warm))
  (println "  symdiff — only in emit-edn:" (count only-emit) "  only in warm:" (count only-warm))
  (doseq [t (take 6 only-emit)] (println "   emit-only:" (pr-str t)))
  (doseq [t (take 6 only-warm)] (println "   warm-only:" (pr-str t))))
;; and: the CODE LOG itself carries 0 refers_to/render-marker lines (derived-only).
(def log-lines (str/split-lines (slurp flat)))
(def no-derived-in-log
  (not-any? (fn [ln] (re-find #":p \"(refers_to|keep_spelling|qualifier|ctor_prefix|accessor_field|supersedes)\"" ln)) log-lines))
(chk "GATE 3 INGEST: code log carries 0 derived (refers_to/marker) lines" no-derived-in-log)

;; ===========================================================================
;; GATE 5 — WARM READS off the materialized refers_to over the CODE-log store.
;; ===========================================================================
;; ensure-refers! materializes refers_to (cold whole-corpus on first call), then
;; :callers / :query answer off the warm graph. Proves the warm graph is CORPUS-
;; backed (closes the audit Q4 gap: real @schema#N nodes, not a proxy store).
(client port {:op :refers-ensure})                  ; force materialize
(def a-name "name-of")                               ; a known top-level defn in schema
(def callers-resp (client port {:op :callers :module "schema" :name a-name}))
(chk "GATE 5 WARM: :callers schema/name-of resolves a binding + returns a set"
     (and (:target callers-resp) (vector? (:callers callers-resp))))
;; :query over AST claims returns rows (e.g. every kind=list node).
(def q-resp (client port {:op :query :scan true
                          :query {:find "out"
                                  :rules [{:head {:rel "out" :args [{:var "l"}]}
                                           :body [{:rel "triple" :args [{:var "l"} "kind" "list"]}]}]}}))
(chk "GATE 5 WARM: :query over warm AST returns rows (kind=list nodes)"
     (and (:ok q-resp) (pos? (count (:ok q-resp)))))

;; ===========================================================================
;; GATE 4 — CROSS-FRAME: a bridge claim composes through the ONE coordinator.
;; ===========================================================================
;; code-owned subject (@schema#<a node>), foreign-thread object (@flip-foreign-thread).
;; relates_to is a domain ref pred — not schema/resolve reserved — so do-assert lands
;; it on the code log + warm store. Read it back via :query. Proof the frames compose.
(def bridge-subj
  ;; pick a stable code node: the @schema#root's sibling — use a known AST node id.
  (let [st (:store @co)
        ;; first kind=symbol node named name-of's def — any @schema# node works; use #1 (root list).
        n1 "@schema#1"]
    n1))
(def foreign "@flip-foreign-thread")
(def v0 (:version (client port {:op :version})))
(def bridge-resp (client port {:op :assert :te bridge-subj :p "relates_to" :r foreign :base v0}))
(chk "GATE 4 CROSS-FRAME: bridge @schema#1 relates_to @flip-foreign-thread committed" (:ok bridge-resp))
;; read it back via :query.
(def bridge-q (client port {:op :query :scan true
                            :query {:find "out"
                                    :rules [{:head {:rel "out" :args [{:var "r"}]}
                                             :body [{:rel "triple" :args [bridge-subj "relates_to" {:var "r"}]}]}]}}))
(def bridge-read (and (:ok bridge-q) (some #(= [foreign] (mapv str %)) (:ok bridge-q))))
(chk "GATE 4 CROSS-FRAME: bridge claim reads back over the warm view" bridge-read)
;; and it landed in the code log (durable).
(def bridge-in-log
  (some (fn [ln] (and (str/includes? ln (str ":l \"" bridge-subj "\""))
                      (str/includes? ln ":p \"relates_to\"")
                      (str/includes? ln (str ":r \"" foreign "\"")))) (str/split-lines (slurp flat))))
(chk "GATE 4 CROSS-FRAME: bridge claim is durable in the code log" bridge-in-log)

;; ===========================================================================
;; KEYSTONE — render(log) == render(text), + delta-commit recompiles.
;; ===========================================================================
;; K-A: render(log) byte-identical to render(text-path). We render BOTH off a CLEAN
;; copy of the log (the bridge claim above is a domain claim, filtered out of the AST
;; render — but to be hermetic we render from the original committed log copy here).
(def kbuild (str (System/getProperty "java.io.tmpdir") "/code-flip-k-" (System/nanoTime)))
(.mkdirs (io/file kbuild))
(def rfl (str kbuild "/render-from-log.bclj"))
(def rft (str kbuild "/render-from-text.bclj"))
(def base-env {"BEAGLE_HOME" beagle-home "FRAM_OUT" (str root "/out")
               "FRAM_ROUNDTRIP" roundtrip-rkt "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")})
;; render-from-log over a FRESH copy of the committed code log (no bridge claim).
(def klog (str kbuild "/code.log")) (io/copy (io/file code-log) (io/file klog))
(proc/shell {:extra-env base-env :err :string} "bb" "-cp" "out" "bin/fram-render-code" "schema" "--log" klog "--out" rfl)
;; render-from-text: emit-edn -> resolve -> render (the existing authoring projection).
(def kresolve (str kbuild "/resolve-out")) (.mkdirs (io/file kresolve))
(proc/sh {:out (io/file (str kresolve "/schema-emit.edn")) :err :string} "racket" roundtrip-rkt "--emit-edn" (str root "/src/fram/schema.bclj"))
(proc/sh {:err :string :extra-env (assoc base-env "RESOLVE_OUT" kresolve)}
         "bb" "-cp" "out" "chartroom/src/resolve.clj" "resolve" (str kresolve "/schema-emit.edn"))
(proc/sh {:out (io/file rft) :err :string} "racket" roundtrip-rkt "--render" (str kresolve "/resolved-schema.bclj.edn"))
(def k-byte-identical
  (and (.exists (io/file rfl)) (.exists (io/file rft))
       (= (slurp rfl) (slurp rft))))
(chk "KEYSTONE-A: render(log) == render(text-path) BYTE-IDENTICAL" k-byte-identical)

;; K-B: render(log) recompiles '0 error' (gate 2 over the render-from-log tree).
(def ksrc (str kbuild "/src")) (.mkdirs (io/file ksrc))
(io/copy (io/file rfl) (io/file (str ksrc "/schema.bclj")))
(def kout (str kbuild "/out")) (.mkdirs (io/file kout))
(def kbuild-res (proc/sh {:out :string :err :string} build-all ksrc "--out" kout))
(def kbuilt (str (:out kbuild-res) (:err kbuild-res)))
;; exact errcount parse (never includes? "0 error").
(def k-recompiles (boolean (re-find #"\b1 built, 0 error\(s\)" kbuilt)))
(chk "KEYSTONE-B: render-from-log tree recompiles (1 built, 0 error)" k-recompiles)

;; K-C: COMMIT-THE-DELTA — a set-body edit committed THROUGH the coordinator, then
;; render(log) recompiles. This is the inversion: the log is mutated, the .bclj is
;; downstream. We drive it over THIS throwaway daemon (port serves the code log).
;;   1. produce the new rendered .bclj via the verb (set-body name-of's trivial body
;;      to an equivalent form), 2. commit the delta via bin/fram-commit-code,
;;      3. render-from-log over THIS daemon's now-updated log, 4. recompile.
(def kc-work (str (System/getProperty "java.io.tmpdir") "/code-flip-kc-" (System/nanoTime)))
(.mkdirs (io/file kc-work))
;; the verb runs over the emit-edn of the CURRENT schema (text), producing a new render.
(def kc-resolve (str kc-work "/resolve-out")) (.mkdirs (io/file kc-resolve))
(proc/sh {:out (io/file (str kc-resolve "/schema-emit.edn")) :err :string} "racket" roundtrip-rkt "--emit-edn" (str root "/src/fram/schema.bclj"))
(def kc-body (str kc-work "/body.edn"))
;; set-body of `cardinality` to a GENUINELY DIFFERENT but semantically-equivalent
;; body (internal let bindings renamed pid->p, card-pid->cp). This forces a REAL
;; (non-empty) retract+assert delta that must survive render-from-log + recompile —
;; the inversion proof. (A bare `nil` would mint as a nil-node rendering as `()`, a
;; verb quirk unrelated to the flip; we avoid it.)
(spit kc-body (str "(let [p (c/value-id ctx pname) cp (c/value-id ctx \"cardinality\") "
                   "cs (if (and (some? p) (some? cp)) (c/by-lp ctx p cp) [])] "
                   "(if (empty? cs) \"multi\" (c/literal ctx (:r (c/claim-of ctx (first cs))))))"))
(def kc-verb (proc/sh {:err :string :extra-env (assoc base-env "RESOLVE_OUT" kc-resolve)}
                      "bb" "-cp" "out" "chartroom/src/resolve.clj" "set-body" "cardinality" "schema" kc-body
                      (str kc-resolve "/schema-emit.edn")))
(def kc-verb-ok (zero? (:exit kc-verb)))
(chk "KEYSTONE-C: set-body verb produced a render (claim op)" kc-verb-ok)
;; render the verb's output to the new .bclj.
(def kc-newbclj (str kc-work "/schema-new.bclj"))
(when kc-verb-ok
  (proc/sh {:out (io/file kc-newbclj) :err :string} "racket" roundtrip-rkt "--render" (str kc-resolve "/resolved-schema.bclj.edn")))
;; commit the delta THROUGH this daemon's coordinator (the code log).
(def kc-commit
  (when (.exists (io/file kc-newbclj))
    (proc/shell {:continue true :extra-env base-env :out :string :err :string}
                "bb" "-cp" "out" "bin/fram-commit-code" "schema" kc-newbclj "--port" (str port))))
(def kc-commit-ok (and kc-commit (zero? (:exit kc-commit))))
(chk "KEYSTONE-C: set-body delta COMMITTED through the coordinator (log mutated)" kc-commit-ok)
;; render-from-log over the NOW-UPDATED daemon log (flat), recompile.
(def kc-render (str kc-work "/schema-from-log.bclj"))
(when kc-commit-ok
  (proc/shell {:continue true :extra-env base-env :err :string}
              "bb" "-cp" "out" "bin/fram-render-code" "schema" "--log" flat "--out" kc-render))
(def kc-src (str kc-work "/src")) (.mkdirs (io/file kc-src))
(when (.exists (io/file kc-render)) (io/copy (io/file kc-render) (io/file (str kc-src "/schema.bclj"))))
(def kc-out (str kc-work "/out")) (.mkdirs (io/file kc-out))
(def kc-build (when (.exists (io/file (str kc-src "/schema.bclj")))
                (proc/sh {:out :string :err :string} build-all kc-src "--out" kc-out)))
(def kc-recompiles (boolean (and kc-build (re-find #"\b1 built, 0 error\(s\)" (str (:out kc-build) (:err kc-build))))))
(chk "KEYSTONE-C: render(updated log) recompiles after the delta-commit (0 error)" kc-recompiles)
;; and: the new body is actually IN the post-commit render (the edit took effect) —
;; the renamed local `cp` only exists in the new body, so its presence proves the
;; delta landed and survived render-from-log.
(def kc-took (boolean (and (.exists (io/file kc-render))
                           (str/includes? (slurp kc-render) "cp (c/value-id ctx"))))
(chk "KEYSTONE-C: the committed body is present in render(updated log)" kc-took)

;; --- verdict ----------------------------------------------------------------
(shutdown!)
(println "\n=== THE FLIP — code source-of-truth demotion (gate) ===")
(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (do (println "\nTHE FLIP:" (count cs) "/" (count cs) "PASS — the .bclj is a pure function of the CODE claim log.")
        (System/exit 0))
    (do (println "\nTHE FLIP:" (count fails) "FAILED") (System/exit 1))))
