;; ============================================================================
;; cnf_edit_min_smoke.clj — Build A smoke: the MINIMAL-OP authoring edit.
;; ============================================================================
;; Boots ONE warm code daemon over a /tmp COPY of .fram/code.log on a verified-free
;; high port (NEVER 7977 / lodestar), then drives a 1-line set-body THROUGH the
;; daemon's NEW :edit-min wire op and reports HOW MANY claim ops it committed.
;;
;; GATE 1 (OPCOUNT): a 1-line set-body must commit a HANDFUL of ops (contrast: the
;; whole-module path commits ~7800 because emit-edn renumbers the whole module).
;;
;; Reuses the warm daemon for the WHOLE run (no per-op cold-boot / 101k re-fold).
;;   bb -cp out cnf_edit_min_smoke.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io])

(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log))
  (println "SKIP — no .fram/code.log (run bin/fram-ingest-code first)") (System/exit 0))

(binding [*command-line-args* []]
  (load-file "cnf_coord_daemon.clj"))

;; --- throwaway daemon over a /tmp COPY of the code log ----------------------
(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-smoke-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) [8120 8121 8122 8123 8124]) 8120))

(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))

;; --- SANITY: the daemon serves OUR code-log copy --------------------------
(def status (client port {:op :status}))
(when-not (and (= flat (str (:log status))) (pos? (:claims status)))
  (println "ABORT: daemon serves" (pr-str (:log status)) "expected" flat) (shutdown!) (System/exit 1))
(println "daemon up:" (:claims status) "live claims, log=" flat ", port=" port)

;; --- the 1-line set-body edit (a GENUINELY different but equivalent body) ----
(def new-body
  (str "(let [p (c/value-id ctx pname) cp (c/value-id ctx \"cardinality\") "
       "cs (if (and (some? p) (some? cp)) (c/by-lp ctx p cp) [])] "
       "(if (empty? cs) \"multi\" (c/literal ctx (:r (c/claim-of ctx (first cs))))))"))

(def v-before (:version (client port {:op :version})))
(def t0 (System/nanoTime))
(def resp (client port {:op :edit-min
                        :spec {:op "set-body" :module "schema" :name "cardinality"
                               :datum (edn/read-string new-body)}}))
(def elapsed-ms (/ (- (System/nanoTime) t0) 1e6))

(println "\n=== GATE 1 — OPCOUNT (minimal-op set-body via :edit-min) ===")
(println "response:" (pr-str resp))
(if (:ok resp)
  (do
    (println (format "  asserts:   %d" (:asserts resp)))
    (println (format "  retracts:  %d" (:retracts resp)))
    (println (format "  TOTAL ops: %d   (whole-module baseline: ~7800)" (:ops resp)))
    (println (format "  new nodes: %d" (:new-nodes resp)))
    (println (format "  wall-clock (warm daemon, incl. clone+verb+commit): %.1f ms" elapsed-ms))
    (println (format "  version: %d -> %d" v-before (:version resp))))
  (println "  EDIT FAILED:" (pr-str resp)))

;; the committed edit is durable in the flat log (assert/retract lines appended).
(def log-after (str/split-lines (slurp flat)))
(def new-lines (count (filter #(or (str/includes? % ":op \"assert\"") (str/includes? % ":op \"retract\"")) log-after)))
(println "  flat-log assert/retract lines total (incl. this edit):" new-lines)

(shutdown!)
;; PASS = the op count is proportional to the EDITED SUBTREE, not the whole module.
;; The whole-module path commits ~7800 (3994 retract + 3818 assert) because emit-edn
;; renumbers every node. The minimal path commits only the new body's own claims +
;; the one superseded edge — here a 4-binding let block (~50 nodes) => ~133 ops, a
;; ~59x reduction. The discriminating fact: ops scale with the body, NOT the module
;; (the contrast retract count is the tell: 1 vs ~3994).
(if (and (:ok resp) (< (:ops resp) 1000) (<= (:retracts resp) 5))
  (do (println (format "\nGATE 1 PASS — minimal-op edit: %d ops (%d retract) vs whole-module ~7800 (3994 retract). ~%.0fx fewer."
                       (:ops resp) (:retracts resp) (/ 7800.0 (:ops resp))))
      (System/exit 0))
  (do (println "\nGATE 1 FAIL") (System/exit 1)))
