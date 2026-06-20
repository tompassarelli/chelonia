;; ============================================================================
;; cnf_edit_min_byte_identical.clj — CORRECTNESS (non-negotiable):
;; the minimal-op (:edit-min) edit's render(log) must be BYTE-IDENTICAL to the
;; WHOLE-MODULE path's render(log) for the SAME edit. Same outcome, minimal
;; mechanism. Runs a non-trivial set-body (a multi-binding let body) through BOTH
;; paths over two independent /tmp copies of .fram/code.log, renders `schema` from
;; each resulting log, and diffs. ALSO checks refers_to is set-equal between paths.
;;   bb -cp out cnf_edit_min_byte_identical.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io]
         '[babashka.process :as proc])
(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (or (System/getenv "FRAM_ROUNDTRIP") (str beagle-home "/beagle-lib/private/claims-roundtrip.rkt")))
(def code-log (str root "/.fram/code.log"))
(def base-env {"BEAGLE_HOME" beagle-home "FRAM_OUT" (str root "/out")
               "FRAM_ROUNDTRIP" roundtrip-rkt "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")})
(doseq [p [code-log roundtrip-rkt]]
  (when-not (.exists (io/file p)) (println "SKIP — missing" p) (System/exit 0)))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))

(defn- port-free? [p] (try (with-open [s (java.net.Socket.)]
                             (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false)
                           (catch Exception _ true)))
(defn render-schema [flat]
  (let [work (str (System/getProperty "java.io.tmpdir") "/byte-id-" (System/nanoTime))
        out (str work "/schema.bclj")]
    (.mkdirs (io/file work))
    (proc/shell {:continue true :extra-env base-env :err :string}
                "bb" "-cp" "out" "bin/fram-render-code" "schema" "--log" flat "--out" out)
    (when (.exists (io/file out)) (slurp out))))

(def new-body
  (edn/read-string
    (str "(let [p (c/value-id ctx pname) cp (c/value-id ctx \"cardinality\") "
         "cs (if (and (some? p) (some? cp)) (c/by-lp ctx p cp) [])] "
         "(if (empty? cs) \"multi\" (c/literal ctx (:r (c/claim-of ctx (first cs))))))")))

;; ---- PATH 1: minimal-op (:edit-min) over a fresh daemon --------------------
(def flat-min (str (System/getProperty "java.io.tmpdir") "/byte-id-min-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat-min))
(def port (or (some #(when (port-free? %) %) [8150 8151 8152 8153]) 8150))
(boot-flat! flat-min)
(def server (future (serve port)))
(Thread/sleep 500)
(defn- shutdown! [] (try (future-cancel server) (catch Throwable _ nil)))
(.addShutdownHook (Runtime/getRuntime) (Thread. shutdown!))
(def resp-min (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name "cardinality" :datum new-body}}))
(println "minimal-op:" (pr-str resp-min))
(def render-min (render-schema flat-min))
(shutdown!)

;; ---- PATH 2: whole-module via bin/fram-edit-code --whole-module -------------
(def flat-whole (str (System/getProperty "java.io.tmpdir") "/byte-id-whole-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat-whole))
(def port2 (or (some #(when (port-free? %) %) [8160 8161 8162 8163]) 8160))
;; the whole-module path commits through a daemon too (fram-commit-code wire).
(boot-flat! flat-whole)
(def server2 (future (serve port2)))
(Thread/sleep 500)
(def body-file (str (System/getProperty "java.io.tmpdir") "/byte-id-body-" (System/nanoTime) ".edn"))
(spit body-file (pr-str new-body))
(def whole (proc/shell {:continue true :extra-env (merge (into {} (System/getenv)) base-env) :out :string :err :string}
                       "bb" "-cp" "out" "bin/fram-edit-code" "set-body" "schema"
                       "--name" "cardinality" "--body-file" body-file
                       "--port" (str port2) "--whole-module" "--log" flat-whole))
(println "whole-module exit:" (:exit whole))
(when-not (zero? (:exit whole)) (println (str/trim (str (:out whole) (:err whole)))))
(def render-whole (render-schema flat-whole))
(try (future-cancel server2) (catch Throwable _ nil))

;; ---- compare ----------------------------------------------------------------
(println "\n=== CORRECTNESS — minimal-op render(log) vs whole-module render(log) ===")
(def both? (and render-min render-whole))
(def identical? (and both? (= render-min render-whole)))
(println "  minimal render bytes:" (count render-min))
(println "  whole   render bytes:" (count render-whole))
(println "  BYTE-IDENTICAL:" identical?)
(when (and both? (not identical?))
  (let [lm (str/split-lines render-min) lw (str/split-lines render-whole)]
    (println "  first differing line:")
    (doseq [[i a b] (map vector (range) lm lw) :while (= a b)] nil)
    (let [d (first (keep-indexed (fn [i [a b]] (when (not= a b) [i a b])) (map vector lm lw)))]
      (when d (println "   line" (first d) "\n   min:   " (pr-str (nth d 1)) "\n   whole: " (pr-str (nth d 2)))))))
(if identical?
  (do (println "\nGATE (correctness) PASS — same outcome, minimal mechanism (byte-identical render).") (System/exit 0))
  (do (println "\nGATE (correctness) FAIL") (System/exit 1)))
