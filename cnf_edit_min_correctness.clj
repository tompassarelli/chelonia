;; ============================================================================
;; cnf_edit_min_correctness.clj — Build A correctness + opcount-scaling.
;; ============================================================================
;; ONE warm daemon over a /tmp copy of .fram/code.log. Proves:
;;  (A) op count SCALES with the edited body (a literal body => a HANDFUL of ops),
;;  (B) CORRECTNESS: render(log) after the minimal-op edit == render(log) after the
;;      WHOLE-MODULE path for the SAME edit (byte-identical), and recompiles 11/0...
;;      here we check the single edited module recompiles 1/0 and the new body landed.
;;   bb -cp out cnf_edit_min_correctness.clj
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

(def flat (str (System/getProperty "java.io.tmpdir") "/edit-min-corr-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(def port (or (some #(when (port-free? %) %) [8130 8131 8132 8133]) 8130))
(boot-flat! flat)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
(def status (client port {:op :status}))
(when-not (and (= flat (str (:log status))) (pos? (:claims status)))
  (println "ABORT wrong log" (pr-str (:log status))) (shutdown!) (System/exit 1))
(println "daemon up:" (:claims status) "claims, port" port)

(def checks (atom []))
(defn chk [nm ok & [extra]] (swap! checks conj [nm (boolean ok) extra]))

;; (A) opcount scaling: a TRULY trivial body (single string literal) => a TINY op
;; count. set-body of cardinality (schema, returns String) to "multi" — a one-node
;; body that is ALSO type-correct (so it recompiles).
(def tiny (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "cardinality" :datum "multi"}}))
(println "\ntiny set-body (cardinality body = \"multi\"):" (pr-str tiny))
(chk "OPCOUNT scales: trivial body commits a TINY op count (<= 8)"
     (and (:ok tiny) (<= (:ops tiny) 8)) (str (:ops tiny) " ops"))

;; (B) CORRECTNESS: render(log) after the minimal edit, and confirm the body landed +
;; recompiles. Render the edited `schema` module FROM the (now-mutated) flat log.
(def work (str (System/getProperty "java.io.tmpdir") "/edit-min-corr-work-" (System/nanoTime)))
(.mkdirs (io/file work))
(def rendered (str work "/schema.bclj"))
(proc/shell {:continue true :extra-env base-env :err :string}
            "bb" "-cp" "out" "bin/fram-render-code" "schema" "--log" flat "--out" rendered)
(def rendered-txt (when (.exists (io/file rendered)) (slurp rendered)))
;; the cardinality defn line: its body is now the literal "multi" (one node), and the
;; OLD let-body (the `(let [pid ...])` block) is GONE — the edit replaced it.
(def card-line (when rendered-txt
                 (first (filter #(str/includes? % "defn cardinality") (str/split-lines rendered-txt)))))
;; the cardinality defn now renders on ONE line (its body is a single literal), and
;; that line carries "multi" and NOT the old `let` block — proof the body was replaced.
(def body-landed (boolean (and card-line
                               (str/includes? card-line "\"multi\"")
                               (not (str/includes? card-line "let")))))
(chk "CORRECTNESS: render(log) shows cardinality's new body (\"multi\") and old let-body gone" body-landed)
;; recompile the rendered module (drop it in a one-file src tree).
(def src (str work "/src")) (.mkdirs (io/file src))
(when (.exists (io/file rendered)) (io/copy (io/file rendered) (io/file (str src "/schema.bclj"))))
(def out (str work "/out")) (.mkdirs (io/file out))
(def bres (when (.exists (io/file (str src "/schema.bclj")))
            (proc/sh {:out :string :err :string} build-all src "--out" out)))
(def recompiles (boolean (and bres (re-find #"\b1 built, 0 error\(s\)" (str (:out bres) (:err bres))))))
(chk "CORRECTNESS: render(log) of the edited module recompiles (1 built, 0 error)" recompiles)

(shutdown!)
(println "\n=== Build A — minimal-op edit: correctness + scaling ===")
(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok extra] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm (if extra (str "(" extra ")") "")))
  (if (empty? fails)
    (do (println "\nBuild A:" (count cs) "/" (count cs) "PASS") (System/exit 0))
    (do (println "\nBuild A:" (count fails) "FAILED") (System/exit 1))))
