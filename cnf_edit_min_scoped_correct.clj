;; ============================================================================
;; cnf_edit_min_scoped_correct.clj — Build B CORRECTNESS: the SCOPED re-resolve
;; the daemon maintains after a minimal-op edit must equal the WHOLE-CORPUS
;; re-resolve (refers_to set-equal). After several :edit-min edits across modules,
;; :refers-keyset re-resolves SCOPED (off the dirty set) and diffs it against a
;; fresh WHOLE-CORPUS rebuild over a clone — symdiff MUST be 0.
;;   bb -cp out cnf_edit_min_scoped_correct.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no .fram/code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-scoped-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) [8190 8191 8192 8193]) 8190))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
(def status (client port {:op :status}))
(when-not (and (= flat (str (:log status))) (pos? (:claims status)))
  (println "ABORT wrong log") (shutdown!) (System/exit 1))
(println "daemon up:" (:claims status) "claims, port" port)

;; warm refers_to once (cold whole-corpus), then do disjoint minimal-op edits, then
;; check scoped == whole-corpus ground truth.
(def k0 (client port {:op :refers-keyset}))
(println "\nbaseline (cold materialize) symdiff:" (:symdiff-size k0)
         " scoped=" (:scoped-size k0) " ground=" (:ground-size k0))

;; two disjoint same-module set-body edits + a rename in another module (exercise both
;; the no-walk capture-only path AND the rename path that reads materialized refers_to).
(def e1 (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "cardinality"
                                           :datum (edn/read-string "(if (some? (c/value-id ctx pname)) \"x\" \"multi\")")}}))
(def e2 (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "lookup"
                                           :datum (edn/read-string "(first (lookup-all ctx subj pname))")}}))
(println "edit1 (schema/cardinality):" (:ok e1) (:ops e1) "ops")
(println "edit2 (schema/lookup)     :" (:ok e2) (:ops e2) "ops")

;; AFTER the minimal-op edits: scoped re-resolve (dirty=schema) vs whole-corpus truth.
(def dbg (client port {:op :refers-ensure}))
(def k1 (client port {:op :refers-keyset}))
(println "\nafter minimal-op edits — scoped re-resolve vs whole-corpus ground truth:")
(println "  scoped edge count :" (:scoped-size k1))
(println "  ground edge count :" (:ground-size k1))
(println "  symdiff           :" (:symdiff-size k1) (when (pos? (:symdiff-size k1)) (pr-str (:symdiff k1))))
(println "  last materialize  :" (pr-str (select-keys (:last-materialize dbg) [:mode :walked :stripped :forms-walked])))

(shutdown!)
(if (and (:ok e1) (:ok e2) (zero? (:symdiff-size k1)))
  (do (println "\nBuild B CORRECTNESS PASS — scoped re-resolve == whole-corpus re-resolve (refers_to set-equal) after minimal-op edits.")
      (System/exit 0))
  (do (println "\nBuild B CORRECTNESS FAIL — scoped != whole-corpus") (System/exit 1)))
