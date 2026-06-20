;; Build B — confirm the RENAME verb (the one whose no-capture check READS refers_to)
;; still works on the minimal-op path with the whole-corpus walk OFF: do-edit-min
;; pre-materializes refers_to (ensure-refers!), the clone inherits it, the verb's
;; capture-check reads the inherited edges. Rename a schema def + confirm the new name
;; lands in render(log) and it recompiles. NEVER 7977.
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io] '[babashka.process :as proc])
(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (or (System/getenv "FRAM_ROUNDTRIP") (str beagle-home "/beagle-lib/private/claims-roundtrip.rkt")))
(def build-all (or (System/getenv "FRAM_BUILD_ALL") (str beagle-home "/bin/beagle-build-all")))
(def code-log (str root "/.fram/code.log"))
(def base-env {"BEAGLE_HOME" beagle-home "FRAM_OUT" (str root "/out") "FRAM_ROUNDTRIP" roundtrip-rkt
               "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")})
(doseq [p [code-log roundtrip-rkt]] (when-not (.exists (io/file p)) (println "SKIP — missing" p) (System/exit 0)))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-rename-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)] (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false) (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) [8200 8201 8202 8203]) 8200))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
(println "daemon up:" (:claims (client port {:op :status})) "claims, port" port)

;; rename the schema-internal helper `replace!` -> `supersede-prior!` (a value def with
;; in-module references — exercises the capture-check + reference-follows-refers_to).
(def t0 (System/nanoTime))
(def resp (client port {:op :edit-min :spec {:op "rename" :module "schema" :old "replace!" :new "supersede-prior!"}}))
(def ms (/ (- (System/nanoTime) t0) 1e6))
(println (format "rename replace! -> supersede-prior!: %s  (%.1f ms)" (pr-str resp) ms))

(def out (str (System/getProperty "java.io.tmpdir") "/edit-min-rename-out-" (System/nanoTime) ".bclj"))
(proc/shell {:continue true :extra-env base-env :err :string} "bb" "-cp" "out" "bin/fram-render-code" "schema" "--log" flat "--out" out)
(def txt (when (.exists (io/file out)) (slurp out)))
(def renamed (boolean (and txt (str/includes? txt "supersede-prior!") (not (str/includes? txt "replace!")))))
;; recompile
(def work (str (System/getProperty "java.io.tmpdir") "/edit-min-rename-rc-" (System/nanoTime)))
(def src (str work "/src")) (.mkdirs (io/file src)) (def bout (str work "/out")) (.mkdirs (io/file bout))
(when txt (spit (str src "/schema.bclj") txt))
(def br (when txt (proc/sh {:out :string :err :string} build-all src "--out" bout)))
(def recompiles (boolean (and br (re-find #"\b1 built, 0 error\(s\)" (str (:out br) (:err br))))))
(shutdown!)
(println "\n=== Build B — rename on the minimal-op path (refers_to-dependent verb) ===")
(println "  rename ok          :" (boolean (:ok resp)) (str "(" (:ops resp) " ops)"))
(println "  new name in render :" renamed)
(println "  recompiles 1/0     :" recompiles)
(if (and (:ok resp) renamed recompiles)
  (do (println "\nPASS — rename works with the whole-corpus walk OFF (inherited refers_to drives the capture-check + reference following).") (System/exit 0))
  (do (println "\nFAIL") (System/exit 1)))
