;; ============================================================================
;; experiments/propagation/sweep.clj — #44 / PROMPT 2 — the K sweep (the headline)
;; K concurrent writers (DISJOINT targets). DECOMPOSE write-side vs read-side per the
;; pre-registration and report SEPARATELY (do not conflate — that was a v1 bug):
;;   write = time to commit the change locally/to the store.
;;   prop  = commit -> a reader observes it (the propagation metric).
;; Pre-registered: graph PROP flat+low in K (eager store, no write barrier) while git
;; PROP climbs (pushes serialize through the bare repo's non-ff check -> fetch+merge+
;; retry = the merge-queue). Mirror cost: graph WRITE is high (eager index re-resolve),
;; git WRITE is low. The honest story is a TRADEOFF, not a clean win.
;;   bb -cp out experiments/propagation/sweep.clj   [SWEEP_KS=1,2,4,8]
;;
;; FENCES: graph read = cheap :status (corpus-from-store, no re-resolve, no racket);
;; git baseline = real shared bare repo + fetch/merge/push retry (NOT a poll loop).
;; SAFE: /tmp only, daemon on non-7977 port, never the canonical log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[babashka.process :as proc] '[fram.cnf :as c] '[fram.schema :as s])
(def root (System/getProperty "user.dir"))
(def KS (mapv #(Integer/parseInt %) (str/split (or (System/getenv "SWEEP_KS") "1,2,4,8") #",")))
(defn nowns [] (System/nanoTime))
(defn ms [a b] (/ (double (- b a)) 1e6))
(defn p [& xs] (apply println xs) (flush))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(defn mean [xs] (/ (reduce + xs) (double (count xs))))

;; ---- GIT arm: K concurrent writers to a shared bare repo, each push-with-retry ----
(defn sh! [dir & args] (apply proc/sh {:dir dir :out :string :err :string} args))
(defn git-setup [K]
  (let [work (str "/tmp/prop-sweep-git-" (System/nanoTime)) bare (str work "/bare.git")]
    (.mkdirs (io/file work))
    (proc/sh {:dir work} "git" "init" "-q" "--bare" "bare.git")
    (proc/sh {:dir work} "git" "clone" "-q" "bare.git" "seed")
    (doseq [[k v] {"user.email" "s@x" "user.name" "s"}] (sh! (str work "/seed") "git" "config" k v))
    (spit (str work "/seed/mod.clj") "(ns mod)\n")
    (sh! (str work "/seed") "git" "add" "mod.clj") (sh! (str work "/seed") "git" "commit" "-qm" "seed")
    (sh! (str work "/seed") "git" "branch" "-M" "main") (sh! (str work "/seed") "git" "push" "-q" "-u" "origin" "main")
    (dotimes [i K] (let [c (str work "/w" i)] (proc/sh {:dir work} "git" "clone" "-q" "bare.git" (str "w" i))
                     (doseq [[k v] {"user.email" (str "w" i "@x") "user.name" (str "w" i)}] (sh! c "git" "config" k v))
                     (sh! c "git" "checkout" "-q" "main")))
    (proc/sh {:dir work} "git" "clone" "-q" "bare.git" "B") (sh! (str work "/B") "git" "checkout" "-q" "main")
    (let [hook (str bare "/hooks/post-receive")]
      (spit hook (str "#!/usr/bin/env bash\nunset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_QUARANTINE_PATH GIT_PREFIX\n"
                      "timeout 30 git -C " work "/B fetch -q origin && timeout 30 git -C " work "/B reset -q --hard origin/main\n"))
      (proc/sh {:dir work} "chmod" "+x" hook))
    work))
(defn git-writer [work i]
  (let [c (str work "/w" i) f (str "w" i ".clj") marker (str "w" i "_def")
        t0 (nowns)
        _ (spit (str c "/" f) (str "(def " marker " " i ")\n"))
        _ (sh! c "git" "add" f) _ (sh! c "git" "commit" "-qm" marker)
        t-write (nowns)                                ; local commit done
        landed (loop [tries 0]
                 (let [r (sh! c "git" "push" "-q" "origin" "main")]
                   (cond (zero? (:exit r)) true
                         (> tries 50) false
                         :else (do (sh! c "git" "fetch" "-q" "origin")
                                   (sh! c "git" "merge" "-q" "--no-edit" "origin/main") (recur (inc tries))))))
        t-landed (nowns)]
    {:landed landed :write (ms t0 t-write) :prop (ms t-write t-landed)}))

;; ---- GRAPH arm: K concurrent writers to one warm daemon (distinct defs) ----
(defn graph-writer [port i base]
  (let [t0 (nowns)
        ins (client port {:op :edit-min :spec {:op "upsert-form" :module "kernel"
                                               :datum (list 'def (symbol (str base i)) i)}})
        t-write (nowns)                                ; commit returns; store eager-updated
        _ (:version (client port {:op :version-free}))  ; B's read — O(1) LOCK-FREE version (off the dlock; isolates true propagation)
        t-vis (nowns)]
    {:landed (boolean (:ok ins)) :write (ms t0 t-write) :prop (ms t-write t-vis)}))

(defn run-K [arm K port]
  (let [base (str "sw_" (System/nanoTime) "_")
        work (when (= arm :git) (git-setup K))
        futs (mapv (fn [i] (future (if (= arm :git) (git-writer work i) (graph-writer port i base)))) (range K))
        rs (mapv deref futs)]
    {:K K :landed (count (filter :landed rs)) :write (mean (map :write rs)) :prop (mean (map :prop rs))
     :prop-max (apply max (map :prop rs))}))

;; ---------------------------------------------------------------------------
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (p "SKIP — no .fram/code.log") (System/exit 0))
(def flat (str "/tmp/prop-sweep-graph-" (System/nanoTime) ".log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)
(def port 8196)
(def server (future (serve port)))
(Thread/sleep 600)

(p (format "=== #44 propagation K-sweep — DECOMPOSED write vs prop (ms; KS=%s) ===" (str/join "," KS)))
(p "prop = commit->reader-sees-it (the propagation metric). git=push-hook+merge-queue; graph=eager warm store.\n")
(def git-rows  (vec (for [K KS] (let [r (run-K :git K port)]  (p (format "  git   K=%-2d landed=%d/%d  write=%.1f  prop mean=%.1f max=%.1f" K (:landed r) K (:write r) (:prop r) (:prop-max r))) r))))
(def graph-rows (vec (for [K KS] (let [r (run-K :graph K port)] (p (format "  graph K=%-2d landed=%d/%d  write=%.1f  prop mean=%.1f max=%.1f" K (:landed r) K (:write r) (:prop r) (:prop-max r))) r))))
(future-cancel server)

(p "\n=== SHAPE (mean ms vs K) ===")
(p "  K    git-write git-prop  graph-write graph-prop")
(doseq [i (range (count KS))]
  (let [K (nth KS i) g (nth git-rows i) gr (nth graph-rows i)]
    (p (format "  %-4d %-9.1f %-9.1f %-11.1f %.1f" K (:write g) (:prop g) (:write gr) (:prop gr)))))
(p "\n  Read PROP columns for the propagation thesis (graph eager vs git merge-queue); WRITE columns")
(p "  for the mirror cost (graph eager-index vs git cheap-commit). landed=K/K both arms = no lost write.")
(System/exit 0)
