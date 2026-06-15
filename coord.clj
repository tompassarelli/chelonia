#!/usr/bin/env bb
;; Chelonia coordinator (Phase 3) — the sole-writer daemon + an adversarial
;; concurrency proof. One process owns the in-memory state and the log;
;; clients connect over a local socket. Writes serialize through one lock;
;; stale/contradictory writes are rejected (optimistic base_version + rules).
;;
;;   bb chelonia/coord.clj serve [port]     # run the daemon
;;   bb chelonia/coord.clj test  [port]     # embedded daemon + N racing clients, assert invariants
(require '[clojure.string :as str] '[clojure.edn :as edn] '[chelonia.kernel :as ck] '[chelonia.projections :as proj])
(import '[java.net ServerSocket Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter])

;; ---- minimal store essentials (shared shape with chelonia.clj) ----
(def single-valued #{"title" "state" "owner" "lead" "driver" "source" "part_of"
                     "do_on" "valid_until" "estimate_hours" "created_at" "updated_at" "name" "body"})
(def valid-states #{"draft" "ready" "active" "done" "canceled"})

(defn q [claims & {:keys [l p]}]
  (filter (fn [[cl cp _]] (and (or (nil? l) (= l cl)) (or (nil? p) (= p cp)))) claims))
(defn one [claims l p] (some (fn [[_ _ r]] r) (q claims :l l :p p)))
(defn many [claims l p] (map (fn [[_ _ r]] r) (q claims :l l :p p)))

(defn apply-assert [claims [l p r]]
  (if (single-valued p)
    (conj (set (remove (fn [[cl cp _]] (and (= cl l) (= cp p))) claims)) [l p r])
    (conj (set claims) [l p r])))

;; Rule-checking delegates to the Beagle kernel — single source of truth. (P3
;; found a hand-copied rule set drifting from the CLI's; routing through
;; chelonia.kernel/violations makes that drift structurally impossible.)
(defn claims->vec [s] (mapv (fn [[l p r]] (ck/->Claim l p r)) s))

(defn cycle? [claims pred te]
  (let [succ (fn [x] (if (= pred "part_of")
                       (if-let [pp (one claims x "part_of")] [pp] [])
                       (many claims x "depends_on")))]
    (loop [front (succ te) seen #{}]
      (cond (empty? front) false
            (= (first front) te) true
            (seen (first front)) (recur (rest front) seen)
            :else (recur (into (rest front) (succ (first front))) (conj seen (first front)))))))

(defn thread-ids [claims]
  (->> claims (map first) (filter #(str/starts-with? % "thread:")) distinct set))

;; Full obligation rule set — unified with chelonia.clj's CLI. (P3 finding: the
;; daemon previously enforced only invalid-state + cycles, a strict subset of
;; the CLI; it now matches — missing-entity, canceled-dep, part_of-missing, and
;; active-without-driver are enforced in the live serialized commit path.)
(defn violations [claims te]
  (let [v (atom [])
        add #(swap! v conj %)
        st  (one claims te "state")
        ids (thread-ids claims)]
    (when (and st (not (valid-states st))) (add (str "invalid state '" st "'")))
    (doseq [d (many claims te "depends_on")]
      (when-not (ids d) (add (str "depends_on references missing entity " d)))
      (when (= "canceled" (one claims d "state")) (add (str "depends_on points at canceled " d))))
    (when-let [pa (one claims te "part_of")]
      (when-not (ids pa) (add (str "part_of references missing entity " pa))))
    (when (and (= st "active") (nil? (one claims te "driver"))) (add "active thread has no driver"))
    (when (cycle? claims "depends_on" te) (add "depends_on cycle"))
    (when (cycle? claims "part_of" te) (add "part_of cycle"))
    @v))

;; ---- log io (no fsync in bb; the JVM daemon adds it in P4) ----
(defn append-line! [path m]
  (with-open [os (java.io.FileOutputStream. (str path) true)]
    (.write os (.getBytes (str (pr-str m) "\n") "UTF-8")) (.flush os)))

;; ---- coordinator state + sole writer ----
(def state (atom {:claims #{} :version 0 :lastmod {}}))   ; the in-memory fold
(def write-lock (Object.))
(def log-path (atom "/tmp/chelonia-coord.log"))

;; warm read path: cache the kernel index in memory; rebuild on load + on commit.
(defn reindex! [] (swap! state assoc :index (ck/build-index (claims->vec (:claims @state)))))

;; --- pub/sub: push each commit to subscribed agents -------------------------
;; The worth-stealing bit from agentwire — but as a REACTIVE layer over the
;; shared graph, not a message bus. The sole writer is the natural broadcast
;; point; agents `subscribe` and react to changes instead of polling/relaying.
(def subscribers (atom []))
(defn- notify-subs! [event]
  (let [line (str (pr-str event) "\n")]
    (reset! subscribers
            (vec (filter (fn [w]
                           (try (.write ^java.io.BufferedWriter w line)
                                (.flush ^java.io.BufferedWriter w) true
                                (catch Exception _ false)))   ; drop dead subscribers
                         @subscribers)))))

(defn commit!
  "SOLE WRITER. Serialized. Optimistic base_version + obligation rules."
  [te p r base]
  (locking write-lock
    (let [{:keys [claims version lastmod]} @state
          conflict (and (single-valued p) (> (get lastmod [te p] 0) base))
          cand (apply-assert claims [te p r])
          viol (ck/violations (claims->vec cand) te)]
      (cond
        conflict   {:reject :conflict :version version}
        (seq viol) {:reject viol :version version}
        :else (let [tx (inc version)]
                (append-line! @log-path {:tx tx :op "assert" :l te :p p :r r :ts "t" :by "coord"})
                (reset! state {:claims cand :version tx :lastmod (assoc lastmod [te p] tx)})
                (reindex!)
                (notify-subs! {:event :commit :version tx :op "assert" :l te :p p :r r})
                {:ok tx})))))

;; retract: single-valued clears (l,p); multi-valued removes the (l,p,r) triple.
(defn apply-retract [claims [l p r]]
  (if (single-valued p)
    (set (remove (fn [[cl cp _]] (and (= cl l) (= cp p))) claims))
    (set (remove (fn [[cl cp cr]] (and (= cl l) (= cp p) (= cr r))) claims))))

(defn commit-retract!
  "SOLE WRITER (retract). Serialized; same lock, optimistic base, full rules —
   so clearing a driver out from under an active thread is rejected."
  [te p r base]
  (locking write-lock
    (let [{:keys [claims version lastmod]} @state
          conflict (and (single-valued p) (> (get lastmod [te p] 0) base))
          cand (apply-retract claims [te p r])
          viol (ck/violations (claims->vec cand) te)]
      (cond
        conflict   {:reject :conflict :version version}
        (seq viol) {:reject viol :version version}
        :else (let [tx (inc version)]
                (append-line! @log-path {:tx tx :op "retract" :l te :p p :r r :ts "t" :by "coord"})
                (reset! state {:claims cand :version tx :lastmod (assoc lastmod [te p] tx)})
                (reindex!)
                (notify-subs! {:event :commit :version tx :op "retract" :l te :p p :r r})
                {:ok tx})))))

;; warm read projections, served from the cached in-memory index (no file read,
;; no re-fold, no re-index per query — the cold CLI's ~360ms is gone here).
(defn- lev-top [idx]
  (->> (ck/thread-ids-i idx)
       (remove #(ck/terminal-i? idx %))
       (map (fn [te] [te (proj/leverage-score idx te)]))
       (filter #(pos? (second %)))
       (sort-by (comp - second))
       (take 10) vec))
(defn- all-violations [idx]
  (->> (ck/thread-ids-i idx)
       (mapcat (fn [te] (map #(str (subs te 7) ": " %) (ck/violations-i idx te))))
       vec))

(declare maybe-reload!)

(defn handle [req]
  (locking write-lock (maybe-reload!))   ; stay fresh if the log changed out-of-band
  (case (:op req)
    :version  {:version (:version @state)}
    :assert   (commit! (:te req) (:p req) (:r req) (:base req))
    :retract  (commit-retract! (:te req) (:p req) (:r req) (:base req))
    :ready    {:ready (proj/ready (:index @state))}
    :blocked  {:blocked (proj/blocked (:index @state))}
    :leverage {:leverage (lev-top (:index @state))}
    :validate {:violations (all-violations (:index @state))}
    :status   {:version (:version @state) :claims (count (:claims @state)) :log @log-path}
    {:error "unknown op"}))

;; ---- socket server ----
(defn serve-conn [^Socket s]
  (try
    (let [r (BufferedReader. (InputStreamReader. (.getInputStream s)))
          w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))]
      (when-let [line (.readLine r)]
        (let [req (edn/read-string line)]
          (if (= (:op req) :subscribe)
            (do                                  ; long-lived: register + stream commits
              (locking write-lock (swap! subscribers conj w))
              (.write w (pr-str {:subscribed (:version @state)})) (.newLine w) (.flush w)
              (loop [] (when (.readLine r) (recur))))   ; hold open until client disconnects
            (let [resp (handle req)]
              (.write w (pr-str resp)) (.newLine w) (.flush w))))))
    (finally (.close s))))

(defn serve [port]
  (let [ss (doto (ServerSocket.) (.setReuseAddress true) (.bind (InetSocketAddress. port)))]
    (println (str "coordinator listening on 127.0.0.1:" port " (sole writer)"))
    (loop [] (let [s (.accept ss)] (future (serve-conn s)) (recur)))))

;; --- daemon: load the append-only log into state, then serve -----------------
;; Reads the SAME log format the beagle CLI writes ({:op "assert"/"retract" ...}).
(defn load-log! [path]
  (reset! state {:claims #{} :version 0 :lastmod {}})
  (doseq [a (->> (try (str/split-lines (slurp path)) (catch Exception _ []))
                 (remove str/blank?)
                 (keep (fn [l] (try (edn/read-string l) (catch Exception _ nil))))
                 (sort-by :tx))]
    (let [cur @state
          tr [(:l a) (:p a) (:r a)]
          cand (if (= (:op a) "assert")
                 (apply-assert (:claims cur) tr)
                 (apply-retract (:claims cur) tr))]
      (reset! state {:claims cand
                     :version (max (:version cur) (:tx a))
                     :lastmod (assoc (:lastmod cur) [(:l a) (:p a)] (:tx a))}))))

;; --- stay-fresh: reload if the log changed on disk (other agent imported) -----
(defn- log-stamp [path] (let [f (java.io.File. path)] (str (.lastModified f) ":" (.length f))))

(defn maybe-reload!
  "Reload the log if it changed on disk out-of-band (e.g. another agent ran
   import). Call under write-lock so it serializes with commits. This is what
   keeps warm reads/writes fresh in a multi-agent, file-importing world."
  []
  (when (:log-mtime @state)            ; only once serve-daemon has initialized it
    (let [s (log-stamp @log-path)]
      (when (not= s (:log-mtime @state))
        (load-log! @log-path)
        (reindex!)
        (swap! state assoc :log-mtime s)))))

(defn serve-daemon [port log]
  (reset! log-path log)
  (load-log! log)
  (reindex!)
  (swap! state assoc :log-mtime (log-stamp log))
  (println (str "coordinator: loaded " (count (:claims @state)) " claims from " log))
  (serve port))

(defn client [port m]
  (with-open [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))
          r (BufferedReader. (InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

;; ---- adversarial concurrency proof ----
(defn run-test [port]
  (reset! log-path "/tmp/chelonia-coord-test.log")
  (spit @log-path "")
  (reset! state {:claims #{} :version 0 :lastmod {}})
  ;; seed thread:T  (3 claims -> version 3)
  (commit! "thread:T" "state" "ready" 0)
  (commit! "thread:T" "driver" "person:x" 0)
  (commit! "thread:T" "owner" "owner:seed" 0)
  (let [server (future (serve port))
        _ (Thread/sleep 300)
        n-clients 10  attempts 5
        ;; N clients race to set the SAME single-valued predicate with stale base versions
        racers (doall (for [i (range n-clients)]
                        (future
                          (loop [k 0 commits 0 rejects 0]
                            (if (= k attempts) [commits rejects]
                              (let [v (:version (client port {:op :version}))
                                    resp (client port {:op :assert :te "thread:T" :p "owner"
                                                        :r (str "owner:c" i "_" k) :base v})]
                                (recur (inc k) (+ commits (if (:ok resp) 1 0))
                                       (+ rejects (if (:reject resp) 1 0)))))))))
        ;; a client hammering an ILLEGAL write (part_of self => cycle) in parallel
        illegal (future (loop [k 0 ok 0]
                          (if (= k 20) ok
                            (let [r (client port {:op :assert :te "thread:T" :p "part_of"
                                                  :r "thread:T" :base 0})]
                              (recur (inc k) (+ ok (if (:ok r) 1 0)))))))
        rc (map deref racers)
        illegal-ok @illegal
        total-commits (reduce + (map first rc))
        total-rejects (reduce + (map second rc))
        ;; invariant checks
        loglines (->> (slurp @log-path) str/split-lines (remove str/blank?))
        parsed (map #(try (edn/read-string %) (catch Exception _ ::bad)) loglines)
        corrupt (count (filter #(= ::bad %) parsed))
        owner-claims (count (q (:claims @state) :l "thread:T" :p "owner"))
        ver (:version @state)
        expect-ver (+ 3 total-commits)]
    (future-cancel server)
    (println "\n=== Chelonia concurrency proof ===")
    (println (format "clients=%d attempts=%d  -> commits=%d rejects=%d (contention fired: %s)"
                     n-clients attempts total-commits total-rejects (if (pos? total-rejects) "yes" "no")))
    (println (format "illegal (part_of-self) writes that slipped through: %d" illegal-ok))
    (println (format "log lines: %d  corrupt/torn: %d" (count loglines) corrupt))
    (println (format "owner claims on thread:T (single-valued -> must be 1): %d" owner-claims))
    (println (format "version=%d  expected=3+commits=%d" ver expect-ver))
    (let [pass (and (zero? corrupt) (zero? illegal-ok) (= 1 owner-claims) (= ver expect-ver))]
      (println (str "\nRESULT: " (if pass "PASS — serialized, conflicts rejected, 0 corruption, obligations held under load"
                                       "FAIL")))
      (when-not pass (System/exit 1)))))

;; Only parse the port for the actual run modes — so `(load-file ...)` from the
;; test harness (which may pass case names as args) never hits Integer/parseInt.
(let [[cmd p log] *command-line-args*]
  (case cmd
    "serve" (serve-daemon (Integer/parseInt (or p "7977"))
                          (or log (str (System/getProperty "user.dir") "/claims.log")))
    "test"  (run-test (Integer/parseInt (or p "7977")))
    nil))
