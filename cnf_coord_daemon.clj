;; cnf_coord_daemon.clj — Stage 7: the reified coordinator as a socket daemon.
;; ============================================================================
;; Speaks the SAME wire protocol as coord.clj (:version/:assert/:retract/:ready/
;; :blocked/:leverage/:validate/:status/:subscribe), so chelonia.rt's socket
;; client + the CLI + the MCP work UNCHANGED after the cutover. Internally it
;; commits through the reified kernel (cnf_coord) over the v2 log, and serves
;; reads by projecting the reified live view into the EXISTING, proven projections
;; (chelonia.projections) — the read side of the cutover. The reified live view
;; is set-equal to the flat fold (cnf_domain_test/cnf_lifecycle_test), so those
;; projections return identical results.
;;
;;   bb -cp out cnf_coord_daemon.clj serve [port] [v2-log]
;;   bb -cp out cnf_coord_daemon.clj test  [port]
;; ============================================================================
(require '[clojure.string :as str] '[clojure.edn :as edn]
         '[chelonia.cnf :as c] '[chelonia.schema :as s]
         '[chelonia.kernel :as ck] '[chelonia.projections :as proj])
(import '[java.net ServerSocket Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter])
(load-file "cnf_coord.clj")          ; the reified coordinator library

;; ---- state: one reified coordinator + a cached read index ------------------
(def co (atom nil))                  ; {:store :log :lock}
(def cache (atom {:index nil :version -1}))
(def subscribers (atom []))
(def schema-preds #{"name" "cardinality" "value_kind" "cnf-supersedes"})

;; read-bridge: reified live view -> the flat (l p r) Claim vec build-index wants.
(defn reified->claims [c0]
  (let [st (:store c0)]
    (->> (c/current-claims st)
         (keep (fn [cid]
                 (let [cl (c/claim-of st cid) pstr (c/literal st (:p cl))]
                   (when-not (schema-preds pstr)
                     (ck/->Claim (s/name-of st (:l cl)) pstr
                                 (if (c/value-object? st (:r cl)) (c/literal st (:r cl)) (s/name-of st (:r cl))))))))
         vec)))

;; warm read index — cold-recompute on version change (the v1 decision).
(defn index! []
  (let [v (current-seq @co)]
    (when (not= v (:version @cache))
      (reset! cache {:index (ck/build-index (reified->claims @co)) :version v}))
    (:index @cache)))

(defn- notify-subs! [event]
  (let [line (str (pr-str event) "\n")]
    (reset! subscribers
            (vec (filter (fn [w]
                           (try (.write ^BufferedWriter w line) (.flush ^BufferedWriter w) true
                                (catch Exception _ false)))
                         @subscribers)))))

;; kind from the value: @-prefixed => ref (link), else literal (assert) — exactly
;; the convention the migration loader used, so daemon writes stay consistent with
;; the migrated store.
(defn- kind-of [r] (if (and r (str/starts-with? (str r) "@")) :link :assert))

(defn- do-assert [te p r base]
  (let [res (commit! @co "coord" te p (kind-of r) r base)]
    (if (:ok res)
      (do (notify-subs! {:event :commit :version (:ok res) :op "assert" :l te :p p :r r}) {:ok (:ok res)})
      {:reject (:reject res) :version (:version res)})))

(defn- do-retract [te p r base]
  (let [res (retract! @co "coord" te p r base)]
    (if (:ok res)
      (do (notify-subs! {:event :commit :version (:ok res) :op "retract" :l te :p p :r r}) {:ok (:ok res)})
      {:reject (:reject res) :version (:version res)})))

;; warm projections (same shapes coord.clj returns)
(defn- lev-top [idx]
  (->> (ck/thread-ids-i idx)
       (remove #(ck/terminal-i? idx %))
       (map (fn [te] [te (proj/leverage-score idx te)]))
       (filter #(pos? (second %)))
       ;; score desc, then id asc — a DETERMINISTIC tie-break, so the displayed
       ;; top-10 doesn't flip on internal storage order (latent bug in the bare
       ;; score sort; also makes flat/reified output exactly equal).
       (sort-by (juxt (comp - second) first)) (take 10) vec))
(defn- all-violations [idx]
  (->> (ck/thread-ids-i idx)
       (mapcat (fn [te] (map #(str (subs te 1) ": " %) (ck/violations-i idx te))))
       vec))

(defn handle [req]
  (case (:op req)
    :version  {:version (current-seq @co)}
    :assert   (do-assert (:te req) (:p req) (:r req) (:base req))
    :retract  (do-retract (:te req) (:p req) (:r req) (:base req))
    :ready    {:ready (proj/ready (index!))}
    :blocked  {:blocked (proj/blocked (index!))}
    :leverage {:leverage (lev-top (index!))}
    :validate {:violations (all-violations (index!))}
    :status   {:version (current-seq @co) :claims (count (c/current-claims (:store @co))) :log (:log @co)}
    {:error "unknown op"}))

;; ---- socket server (verbatim shape from the proven coord.clj) ---------------
(defn serve-conn [^Socket s]
  (try
    (let [r (BufferedReader. (InputStreamReader. (.getInputStream s)))
          w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))]
      (when-let [line (.readLine r)]
        (let [req (edn/read-string line)]
          (if (= (:op req) :subscribe)
            (do (swap! subscribers conj w)
                (.write w (pr-str {:subscribed (current-seq @co)})) (.newLine w) (.flush w)
                (loop [] (when (.readLine r) (recur))))
            (let [resp (handle req)] (.write w (pr-str resp)) (.newLine w) (.flush w))))))
    (finally (.close s))))

(defn serve [port]
  (let [ss (doto (ServerSocket.) (.setReuseAddress true)
                 (.bind (InetSocketAddress. (java.net.InetAddress/getLoopbackAddress) (int port))))]
    (println (str "reified coordinator listening on 127.0.0.1:" port " (sole writer, loopback-only)"))
    (loop [] (let [s (.accept ss)] (future (serve-conn s)) (recur)))))

(defn client [port m]
  (with-open [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))
          r (BufferedReader. (InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

;; ---- boot: replay the v2 log (or bootstrap a fresh one) --------------------
(defn boot! [log]
  (let [f (java.io.File. log)]
    (reset! co (if (and (.exists f) (pos? (.length f)))
                 {:store (replay log) :log log :lock (Object.)}
                 (new-coord log))))
  (index!)
  @co)

(defn serve-daemon [port log]
  (boot! log)
  (println (str "reified coordinator: " (count (c/current-claims (:store @co))) " live claims from " log))
  (serve port))

;; ---- adversarial socket test (mirrors coord.clj's run-test) ----------------
(defn run-test [port]
  (spit "/tmp/cnf-coord-daemon-test.log" "")     ; start clean (boot! replays a non-empty log)
  (boot! "/tmp/cnf-coord-daemon-test.log")
  (register-pred! @co "owner" "single" "literal")
  (register-pred! @co "title" "single" "literal")
  (register-pred! @co "part_of" "single" "ref")
  (let [server (future (serve port))
        _ (Thread/sleep 400)
        ;; seed thread @T via the socket
        _ (client port {:op :assert :te "@T" :p "title" :r "Race target" :base 0})
        n-clients 10 attempts 5
        racers (doall (for [i (range n-clients)]
                        (future
                          (loop [k 0 commits 0 rejects 0]
                            (if (= k attempts) [commits rejects]
                                (let [v (:version (client port {:op :version}))
                                      resp (client port {:op :assert :te "@T" :p "owner"
                                                         :r (str "owner-c" i "-" k) :base v})]
                                  (recur (inc k) (+ commits (if (:ok resp) 1 0))
                                         (+ rejects (if (:reject resp) 1 0)))))))))
        illegal (future (loop [k 0 ok 0]
                          (if (= k 20) ok
                              (let [r (client port {:op :assert :te "@T" :p "part_of" :r "@T" :base 0})]
                                (recur (inc k) (+ ok (if (:ok r) 1 0)))))))
        rc (map deref racers)
        illegal-ok @illegal
        total-commits (reduce + (map first rc))
        total-rejects (reduce + (map second rc))
        owner-live (count (live-cids-lp @co (s/resolve-name (:store @co) "@T") (c/value-id (:store @co) "owner")))
        ready-ok (vector? (:ready (client port {:op :ready})))
        status (client port {:op :status})
        rp (replay (:log @co))]
    (future-cancel server)
    (println "\n=== reified coordinator concurrency proof (over the socket) ===")
    (println (format "clients=%d attempts=%d -> commits=%d rejects=%d (contention fired: %s)"
                     n-clients attempts total-commits total-rejects (if (pos? total-rejects) "yes" "no")))
    (let [checks [["illegal (part_of-self) writes that slipped through = 0" (zero? illegal-ok)]
                  ["owner on @T is single-valued -> exactly 1 live" (= 1 owner-live)]
                  ["contention actually fired (some rejects)" (pos? total-rejects)]
                  [":ready served through the read-bridge" ready-ok]
                  [":status reports live claims" (pos? (:claims status))]
                  ["v2 log replays to the live view (durable)" (= (live-triples (:store @co)) (live-triples rp))]]
          fails (remove second checks)]
      (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
      (if (empty? fails)
        (println "\nStage 7 (daemon): reified coordinator over the socket —" (count checks) "/" (count checks) "PASS")
        (do (println "\nStage 7 (daemon):" (count fails) "FAILED") (System/exit 1))))))

(let [[cmd p log] *command-line-args*]
  (case cmd
    "serve" (serve-daemon (Integer/parseInt (or p "7977"))
                          (or log (str (System/getProperty "user.dir") "/chelonia-data/claims-v2.log")))
    "test"  (run-test (Integer/parseInt (or p "7988")))
    nil))
