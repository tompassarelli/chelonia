;; ============================================================================
;; experiments/propagation/harness.clj — #44 / PROMPT 2
;; Propagation latency: time from agent A COMMITTING a code change to agent B's
;; READ reflecting it. Graph (warm Fram daemon, corpus-from-store) vs text+git
;; (shared bare repo, PUSH-HOOK propagation — no poll loop). Same metric both arms.
;;   bb -cp out experiments/propagation/harness.clj   [PROP_N=1]
;;
;; FENCES (pre-registered): metric = commit-to-visible against the maintained store
;; (NOT a re-resolve); git baseline = post-receive hook drives B's fetch (NOT a poll
;; loop with an arbitrary interval). See experiments/propagation/PREREGISTER.md.
;;
;; SAFE: isolated daemon on a /tmp COPY of .fram/code.log, non-7977 port; git arms in
;; /tmp. Never the canonical log, never 7977.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[babashka.process :as proc] '[fram.cnf :as c] '[fram.schema :as s])
(def root (System/getProperty "user.dir"))
(def N (Integer/parseInt (or (System/getenv "PROP_N") "1")))
(defn nowns [] (System/nanoTime))
(defn ms [a b] (/ (double (- b a)) 1e6))
(defn p [& xs] (apply println xs))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))  ; boot-flat!/serve/client

;; ---------------------------------------------------------------------------
;; GIT ARM — shared bare repo; A commits+pushes; a post-receive hook fetches into
;; B's clone (push-notification-driven, NOT polling). t0 = A starts commit; t1 = B's
;; working tree reflects A's change.
;; ---------------------------------------------------------------------------
(defn sh! [dir & args] (apply proc/sh {:dir dir :out :string :err :string} args))
(defn git-arm [trial]
  (let [work (str "/tmp/prop-git-" trial "-" (System/nanoTime))
        bare (str work "/bare.git") A (str work "/A") B (str work "/B")]
    (.mkdirs (io/file work))
    (proc/sh {:dir work :out :string :err :string} "git" "init" "-q" "--bare" "bare.git")
    (proc/sh {:dir work} "git" "clone" "-q" "bare.git" "A")
    (doseq [[k v] {"user.email" "a@x" "user.name" "A"}] (sh! A "git" "config" k v))
    ;; seed: A writes a module, commits, pushes main.
    (spit (str A "/mod.clj") "(ns mod)\n(def seed 0)\n")
    (sh! A "git" "add" "mod.clj") (sh! A "git" "commit" "-qm" "seed")
    (sh! A "git" "branch" "-M" "main") (sh! A "git" "push" "-q" "-u" "origin" "main")
    (proc/sh {:dir work} "git" "clone" "-q" "bare.git" "B")
    (sh! B "git" "checkout" "-q" "main")
    ;; PUSH-HOOK: post-receive on the bare repo fetches B to the just-pushed state.
    ;; This is the propagation mechanism — triggered by A's push, no poll interval.
    (let [hook (str bare "/hooks/post-receive")]
      ;; unset git env (hooks export GIT_DIR/GIT_WORK_TREE -> would point B's fetch at the
      ;; bare repo and stall the push). timeout guards against any hang.
      (spit hook (str "#!/usr/bin/env bash\n"
                      "unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_QUARANTINE_PATH GIT_PREFIX\n"
                      "timeout 30 git -C " B " fetch -q origin && timeout 30 git -C " B " reset -q --hard origin/main\n"))
      (proc/sh {:dir work} "chmod" "+x" hook))
    (binding [*out* *err*] (println "  git: seeded + hook installed, measuring...") (flush))
    ;; MEASURE: A appends a new def, commits, pushes (hook propagates to B). t1 = B sees it.
    (let [marker (str "prop_" trial "_" (System/nanoTime))
          t0 (nowns)
          _ (spit (str A "/mod.clj") (str "(def " marker " 1)\n") :append true)
          _ (sh! A "git" "add" "mod.clj") _ (sh! A "git" "commit" "-qm" marker)
          t-commit (nowns)
          _ (sh! A "git" "push" "-q" "origin" "main")     ; hook fires B's fetch+reset here
          t-push (nowns)
          ;; confirm visibility on B (hook is synchronous within push, so this is ~immediate)
          visible? (fn [] (str/includes? (slurp (str B "/mod.clj")) marker))
          t-vis (loop [] (if (visible?) (nowns) (do (Thread/sleep 1) (recur))))]
      {:commit (ms t0 t-commit) :push+hook (ms t-commit t-push)
       :commit->visible (ms t-commit t-vis) :total (ms t0 t-vis)})))

;; ---------------------------------------------------------------------------
;; GRAPH ARM — warm daemon over a /tmp code.log copy. A commits an insert-form via
;; the socket; B reads via a warm daemon query (corpus-from-store). t1 = B's read
;; reflects A's change. The store updates eagerly on commit, so propagation is ~the
;; read round-trip, flat in agent count.
;; ---------------------------------------------------------------------------
(defn graph-arm [trial port]
  (let [code-log (str root "/.fram/code.log")
        flat (str "/tmp/prop-graph-" trial "-" (System/nanoTime) ".log")]
    (when-not (.exists (io/file code-log)) (p "SKIP graph — no .fram/code.log") (System/exit 0))
    (io/copy (io/file code-log) (io/file flat))
    (boot-flat! flat)
    (let [server (future (serve port))]
      (Thread/sleep 600)
      (try
        ;; B's read = a CHEAP warm read off `co` (:status claim-count — corpus-from-store,
        ;; NO re-resolve, NO racket). The store updates EAGERLY inside commit, so one read
        ;; after :ok already reflects A's write (no poll loop needed). Visibility = the
        ;; warm store's claim count advanced to include A's upsert.
        (let [marker (str "prop_g_" trial "_" (System/nanoTime))
              status-claims (fn [] (:claims (client port {:op :status})))
              c0 (status-claims)
              t0 (nowns)
              ins (client port {:op :edit-min :spec {:op "upsert-form" :module "kernel"
                                                     :datum (list 'def (symbol marker) 1)}})
              t-commit (nowns)
              c1 (status-claims)        ; ONE cheap corpus-from-store read
              t-vis (nowns)]
          {:ok (boolean (:ok ins)) :visible (and c0 c1 (> c1 c0))
           :commit (ms t0 t-commit) :commit->visible (ms t-commit t-vis) :total (ms t0 t-vis)})
        (finally (future-cancel server))))))

;; ---------------------------------------------------------------------------
(p (format "=== #44 propagation latency (N=%d trials, ms) ===" N))
(p "metric: commit -> B's read reflects it. git=push-hook propagation; graph=warm corpus-from-store.\n")

(def git-rs (vec (for [i (range N)] (let [r (git-arm i)] (p (format "  git   trial %d: commit=%.1f push+hook=%.1f commit->visible=%.1f total=%.1f" i (:commit r) (:push+hook r) (:commit->visible r) (:total r))) r))))
(def graph-rs (vec (for [i (range N)] (let [r (graph-arm i (+ 8190 i))] (p (format "  graph trial %d: ok=%s visible=%s commit=%.1f commit->visible=%.1f total=%.1f" i (:ok r) (:visible r) (:commit r) (:commit->visible r) (:total r))) r))))

(defn avg [ks rs] (/ (reduce + (map ks rs)) (double (count rs))))
(p "\n=== MEANS (ms) ===")
(p (format "  GIT   commit=%.1f  push+hook=%.1f  commit->visible=%.1f  total=%.1f"
           (avg :commit git-rs) (avg :push+hook git-rs) (avg :commit->visible git-rs) (avg :total git-rs)))
(p (format "  GRAPH commit=%.1f                  commit->visible=%.1f  total=%.1f"
           (avg :commit graph-rs) (avg :commit->visible graph-rs) (avg :total graph-rs)))
(p "\n  commit->visible is the PROPAGATION number (pre-registered metric). git includes")
(p "  push+post-receive-hook fetch (no poll loop); graph is the warm corpus-from-store read.")
(System/exit 0)
