;; #45 — continuous-arrival propagation under swept load λ.
;; #44 measured K writers arriving ALL AT ONCE; this models a steady stream: writers arrive on a
;; Poisson process at rate λ (writes/sec) for a fixed window, each commits a def and we measure
;; commit-to-visible. Pre-registered: Fram (commute, no global version) holds flat commit-to-visible
;; as λ rises; git (push to a shared ref = merge-queue) backs up — mean + tail latency climb with λ.
;;   bb -cp out experiments/propagation/continuous-arrival.clj   [CA_LAMBDAS=20,50,100,200] [CA_WINDOW_MS=2500]
;; SAFE: /tmp only, daemon on non-7977 port, shared bare git repo in /tmp; never the canonical log.
(require '[clojure.java.io :as io] '[clojure.string :as str] '[babashka.process :as proc]
         '[fram.cnf :as c] '[fram.schema :as s])
(def root (System/getProperty "user.dir"))
(defn nowns [] (System/nanoTime))
(defn ms [a b] (/ (double (- b a)) 1e6))
(defn p [& xs] (apply println xs) (flush))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def LAMBDAS (mapv #(Double/parseDouble %) (str/split (or (System/getenv "CA_LAMBDAS") "20,50,100,200") #",")))
(def WINDOW-MS (Long/parseLong (or (System/getenv "CA_WINDOW_MS") "2500")))
(defn exp-sample [lambda] (/ (- (Math/log (- 1.0 (rand)))) lambda))   ; inter-arrival ~ Exp(λ), seconds
(defn pctl [xs q] (if (empty? xs) 0.0 (let [v (vec (sort xs))] (nth v (min (dec (count v)) (long (* q (count v))))))))

;; ---- GIT arm: shared bare repo + push-hook fetch (the #44 merge-queue baseline) ----
(defn sh! [dir & args] (apply proc/sh {:dir dir :out :string :err :string} args))
(defn git-setup []
  (let [work (str "/tmp/ca-git-" (nowns)) bare (str work "/bare.git")]
    (.mkdirs (io/file work))
    (proc/sh {:dir work} "git" "init" "-q" "--bare" "bare.git")
    (proc/sh {:dir work} "git" "clone" "-q" "bare.git" "seed")
    (doseq [[k v] {"user.email" "s@x" "user.name" "s"}] (sh! (str work "/seed") "git" "config" k v))
    (spit (str work "/seed/mod.clj") "(ns mod)\n")
    (sh! (str work "/seed") "git" "add" "mod.clj") (sh! (str work "/seed") "git" "commit" "-qm" "seed")
    (sh! (str work "/seed") "git" "branch" "-M" "main") (sh! (str work "/seed") "git" "push" "-q" "-u" "origin" "main")
    (proc/sh {:dir work} "git" "clone" "-q" "bare.git" "B") (sh! (str work "/B") "git" "checkout" "-q" "main")
    (let [hook (str bare "/hooks/post-receive")]
      (spit hook (str "#!/usr/bin/env bash\nunset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_QUARANTINE_PATH GIT_PREFIX\n"
                      "timeout 30 git -C " work "/B fetch -q origin && timeout 30 git -C " work "/B reset -q --hard origin/main\n"))
      (proc/sh {:dir work} "chmod" "+x" hook))
    work))
(defn git-clone! [work i] (let [c (str work "/w" i)] (proc/sh {:dir work} "git" "clone" "-q" "bare.git" (str "w" i))
                            (doseq [[k v] {"user.email" (str "w" i "@x") "user.name" (str "w" i)}] (sh! c "git" "config" k v))
                            (sh! c "git" "checkout" "-q" "main") c))
(defn git-writer [work i]
  (let [c (str work "/w" i) f (str "w" i ".clj") t0 (nowns)]
    (spit (str c "/" f) (str "(def w" i "_def " i ")\n"))
    (sh! c "git" "add" f) (sh! c "git" "commit" "-qm" (str "w" i))
    (loop [tries 0]
      (let [r (sh! c "git" "push" "-q" "origin" "main")]
        (cond (zero? (:exit r)) (ms t0 (nowns))
              (> tries 80) (ms t0 (nowns))
              :else (do (sh! c "git" "fetch" "-q" "origin") (sh! c "git" "merge" "-q" "--no-edit" "origin/main") (recur (inc tries))))))))

;; ---- GRAPH arm: warm daemon, commute; commit->visible content-asserted via :seen ----
(defn graph-writer [port i]
  (let [nm (str "ca" i) t0 (nowns)]
    (client port {:op :edit-min :spec {:op "upsert-form" :module "greet" :datum (list 'def (symbol nm) i)}})
    (loop [n 0] (cond (:seen (client port {:op :seen :v nm})) (ms t0 (nowns))
                      (> n 200000) (ms t0 (nowns))
                      :else (recur (inc n))))))

;; run one λ for the window; spawn writers on a Poisson process; collect commit->visible latencies.
(defn run-lambda [arm lambda port git-work]
  (let [deadline (+ (System/currentTimeMillis) WINDOW-MS)
        lats (atom []) futs (atom []) idx (atom 0)]
    (loop []
      (when (< (System/currentTimeMillis) deadline)
        (let [i (swap! idx inc)]
          (swap! futs conj (future
                             (when (= arm :git) (git-clone! git-work i))   ; setup INSIDE the future (untimed: git-writer's t0 is after the clone), so arrivals aren't gated by clone latency
                             (let [lat (if (= arm :git) (git-writer git-work i) (graph-writer port i))]
                               (swap! lats conj lat)))))
        (Thread/sleep (long (* 1000 (exp-sample lambda))))
        (recur)))
    (doseq [f @futs] (deref f))
    (let [ls @lats]
      {:lambda lambda :n (count ls) :mean (if (seq ls) (/ (reduce + ls) (count ls)) 0.0)
       :p50 (pctl ls 0.5) :p99 (pctl ls 0.99) :max (if (seq ls) (apply max ls) 0.0)})))

;; ---------------------------------------------------------------------------
(def code-log (str root "/.fram/code.log"))
(def seed (or (System/getenv "FRAM_SEED")
              (let [s "/tmp/fram-small.log"] (if (.exists (io/file s)) s code-log))))
(def flat (str "/tmp/ca-graph-" (nowns) ".log"))
(io/copy (io/file seed) (io/file flat))
(boot-flat! flat)
(def port 8252)
(def server (future (serve port)))
(Thread/sleep 700)

(p (format "=== #45 continuous-arrival: commit->visible vs load λ (window=%dms, seed=%s) ===" WINDOW-MS seed))
(p "arm   λ(w/s)  done  mean(ms)  p50    p99     max")
(doseq [lambda LAMBDAS]
  (let [gw (git-setup)
        gr (run-lambda :graph lambda port nil)
        gi (run-lambda :git lambda nil gw)]
    (proc/sh "rm" "-rf" gw)
    (p (format "graph %-6.0f %-5d %-9.1f %-6.1f %-7.1f %.1f" (:lambda gr) (:n gr) (:mean gr) (:p50 gr) (:p99 gr) (:max gr)))
    (p (format "git   %-6.0f %-5d %-9.1f %-6.1f %-7.1f %.1f" (:lambda gi) (:n gi) (:mean gi) (:p50 gi) (:p99 gi) (:max gi)))))
(p "\nPre-registered: graph commit->visible flat in λ (commute); git mean+p99 climb with λ (merge-queue backs up).")
(future-cancel server)
(System/exit 0)
