;; cnf_coord_daemon.clj — Stage 7: the reified coordinator as a socket daemon.
;; ============================================================================
;; Speaks the SAME wire protocol as coord.clj (:version/:assert/:retract/:ready/
;; :blocked/:leverage/:validate/:status/:subscribe), so fram.rt's socket
;; client + the CLI + the MCP work UNCHANGED after the cutover. Internally it
;; commits through the reified kernel (cnf_coord) over the v2 log, and serves
;; reads by projecting the reified live view into the EXISTING, proven projections
;; (fram.projections) — the read side of the cutover. The reified live view
;; is set-equal to the flat fold (cnf_domain_test/cnf_lifecycle_test), so those
;; projections return identical results.
;;
;;   bb -cp out cnf_coord_daemon.clj serve [port] [v2-log]
;;   bb -cp out cnf_coord_daemon.clj test  [port]
;; ============================================================================
(require '[clojure.string :as str] '[clojure.edn :as edn]
         '[fram.cnf :as c] '[fram.schema :as s]
         '[fram.kernel :as ck]
         '[fram.fold :as fold] '[fram.rt])
(import '[java.net ServerSocket Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter FileInputStream]
        '[javax.net.ssl SSLContext KeyManagerFactory TrustManagerFactory]
        '[java.security KeyStore])
(load-file "cnf_coord.clj")          ; the reified coordinator library

;; ---- state: one reified coordinator + a cached read index ------------------
(def co (atom nil))                  ; {:store :log :lock} — reified canonical (v2 log)
(def flat-log (atom nil))            ; the flat log, now a PROJECTION the cold CLI folds
(def cache (atom {:index nil :version -1}))
(def subscribers (atom []))
(def dlock (Object.))                ; serializes reload + writes + reads (drop-in mode)
(def flat-mtime (atom nil))          ; last-seen flat-log stamp (to detect external edits)
(def flat-canonical? (atom false))   ; drop-in mode: flat log is canonical, reload absorbs edits
(def schema-preds #{"name" "cardinality" "value_kind" "cnf-supersedes"})

(defn- stamp [f] (let [fi (java.io.File. (str f))] (str (.lastModified fi) ":" (.length fi))))

;; flat-log projection: each reified commit also appends the flat {:op :l :p :r}
;; line the CLI's cold fold reads — so "files are pure projections of the reified
;; store" (Stage 7) and existing reads keep working UNCHANGED across the cutover.
;; Refreshes flat-mtime so our OWN write isn't mistaken for an external edit.
(defn- append-flat! [op te p r seq]
  (when @flat-log
    (with-open [os (java.io.FileOutputStream. (str @flat-log) true)]
      (.write os (.getBytes (str (pr-str {:tx seq :op op :l te :p p :r r :ts (fram.rt/now-ts) :by "coord"}) "\n") "UTF-8"))
      (.flush os))
    (reset! flat-mtime (stamp @flat-log))))

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

;; reserved engine predicates (identity + metadata) — a DOMAIN write to one would
;; collide with the reified schema layer and silently corrupt; reject at the boundary.
(defn- do-assert [te p r base]
  (if (schema-preds p)
    {:reject [(str "reserved predicate '" p "' (engine-internal; use a domain predicate)")] :version (current-seq @co)}
    (let [res (commit! @co "coord" te p (kind-of r) r base)]
      (if (:ok res)
        (do (when-not (:idempotent res) (append-flat! "assert" te p r (:ok res)))
            (notify-subs! {:event :commit :version (:ok res) :op "assert" :l te :p p :r r})
            {:ok (:ok res)})
        {:reject (:reject res) :version (:version res)}))))

(defn- do-retract [te p r base]
  (if (schema-preds p)
    {:reject [(str "reserved predicate '" p "'")] :version (current-seq @co)}
    (let [res (retract! @co "coord" te p r base)]
      (if (:ok res)
        (do (append-flat! "retract" te p r (:ok res))
            (notify-subs! {:event :commit :version (:ok res) :op "retract" :l te :p p :r r})
            {:ok (:ok res)})
        {:reject (:reject res) :version (:version res)}))))

;; §1.2: ready/blocked/leverage are DOMAIN projections — the engine no longer
;; serves them. The CLI/MCP fold the log locally (main/cmd-ready, cmd-json), so
;; these daemon ops were vestigial wire-protocol surface. Dropped along with the
;; fram.projections require → the daemon depends on no domain code. (:validate
;; stays: it's kernel-level structural integrity, not lifecycle.)
(defn- all-violations [idx]
  (->> (ck/thread-ids-i idx)
       (mapcat (fn [te] (map #(str (subs te 1) ": " %) (ck/violations-i idx te))))
       vec))

(declare maybe-reload!)

(defn handle [req]
  (locking dlock                       ; serialize reload + writes + reads (drop-in mode)
    (maybe-reload!)                     ; absorb external flat edits (no-op in v2-log mode)
    (case (:op req)
      :version  {:version (current-seq @co)}
      :assert   (do-assert (:te req) (:p req) (:r req) (:base req))
      :retract  (do-retract (:te req) (:p req) (:r req) (:base req))
      :validate {:violations (all-violations (index!))}
      :status   {:version (current-seq @co) :claims (count (c/current-claims (:store @co))) :log (or @flat-log (:log @co))}
      {:error "unknown op"})))

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

;; bind address: loopback by default (no existing single-machine user is silently
;; exposed); honor FRAM_BIND for gateway-fronted / cross-host deployment. The wire
;; protocol is UNAUTHENTICATED by design (auth is the gateway's job), so a
;; non-loopback bind is only safe behind a network boundary where the ONLY thing
;; that can reach the port is the authenticating gateway / a firewall.
;; Recommended cross-host value: FRAM_BIND=0.0.0.0 — binds ALL interfaces including
;; loopback, so the local CLI + `fram-up` doctor (which connect to 127.0.0.1) keep
;; working, and isolation is enforced by the network rather than by binding one IP.
;; loopback-ness is decided from FRAM_BIND itself (not by introspecting the
;; resolved InetAddress — that reflective call isn't reliable on babashka).
(defn- bind-cfg []
  (let [b (System/getenv "FRAM_BIND")
        loopback? (or (nil? b) (boolean (#{"" "loopback" "127.0.0.1"} b)))]
    {:addr (if loopback?
             (java.net.InetAddress/getLoopbackAddress)
             (java.net.InetAddress/getByName b))
     :loopback? loopback?
     :label (if loopback? "127.0.0.1" b)}))

;; engine-terminated mTLS (JVM-only — this daemon runs on the JVM). When
;; FRAM_TLS_KEYSTORE / FRAM_TLS_TRUSTSTORE / FRAM_TLS_PASS are all set, the listener
;; is an SSLServerSocket that REQUIRES + verifies a client cert (mutual TLS), so a
;; non-loopback link is safe over an untrusted network. Unset => plaintext (default,
;; unchanged). The EDN wire protocol is identical inside the TLS session.
(defn- tls-cfg []
  (let [ks (System/getenv "FRAM_TLS_KEYSTORE")
        ts (System/getenv "FRAM_TLS_TRUSTSTORE")
        pass (System/getenv "FRAM_TLS_PASS")]
    ;; fail CLOSED: a partial config (typo / missing var / secrets-manager glitch)
    ;; must NOT silently serve plaintext where mTLS was intended.
    (when (and (or ks ts pass) (not (and ks ts pass)))
      (binding [*out* *err*]
        (println "FATAL: FRAM_TLS_* partially set — need ALL of FRAM_TLS_KEYSTORE / FRAM_TLS_TRUSTSTORE / FRAM_TLS_PASS (refusing to serve plaintext)"))
      (System/exit 2))
    (when (and ks ts pass) {:ks ks :ts ts :pass pass})))

(defn- load-keystore [path pw]
  (with-open [in (FileInputStream. (str path))]
    (doto (KeyStore/getInstance "PKCS12") (.load in pw))))

(defn- tls-context [{:keys [ks ts pass]}]
  (let [pw (.toCharArray (str pass))
        kmf (doto (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
              (.init (load-keystore ks pw) pw))
        tmf (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
              (.init (load-keystore ts pw)))]
    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil))))

(defn- listen-socket [addr port tls]
  (if tls
    (doto (.createServerSocket (.getServerSocketFactory (tls-context tls)))
      (.setNeedClientAuth true)                                  ; mutual TLS: require + verify client cert
      (.setEnabledProtocols (into-array String ["TLSv1.3" "TLSv1.2"]))
      (.setReuseAddress true)
      (.bind (InetSocketAddress. addr (int port))))
    (doto (ServerSocket.) (.setReuseAddress true)
          (.bind (InetSocketAddress. addr (int port))))))

(defn serve [port]
  (let [cfg (bind-cfg)
        addr (:addr cfg) loopback? (:loopback? cfg) label (:label cfg)
        tls (tls-cfg)
        ss (listen-socket addr port tls)]
    (when (and (not loopback?) (not tls))
      (binding [*out* *err*]
        (println (str "WARNING: coordinator bound to " label ":" port
                      " (non-loopback, NO TLS). The wire protocol is UNAUTHENTICATED — it MUST sit "
                      "behind the gateway / a firewall, or set FRAM_TLS_* for mutual TLS; never publish this port."))))
    (println (str "reified coordinator listening on " label ":" port
                  (cond tls " (sole writer, mTLS)"
                        loopback? " (sole writer, loopback-only)"
                        :else " (sole writer, behind-gateway)")))
    (loop [] (let [s (.accept ss)] (future (serve-conn s)) (recur)))))

(defn client [port m]
  (with-open [s (Socket.)]
    (.connect s (InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (let [w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))
          r (BufferedReader. (InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

;; ---- boot: replay the v2 log (or bootstrap a fresh one) --------------------
(defn boot!
  ([log] (boot! log nil))
  ([log flat]
   (reset! flat-log flat)
   (let [f (java.io.File. log)]
     (reset! co (if (and (.exists f) (pos? (.length f)))
                  {:store (replay log) :log log :lock (Object.)}
                  (new-coord log))))
   (index!)
   @co))

(defn serve-daemon [port log flat]
  (boot! log flat)
  (println (str "reified coordinator: " (count (c/current-claims (:store @co))) " live claims from " log
                (when flat (str "; flat projection -> " flat))))
  (serve port))

;; ===========================================================================
;; DROP-IN cutover (design B): the flat log stays canonical (no format change);
;; the daemon is a reified-engine FRONT-END over it. Boots by migrating the flat
;; log into the reified store; commits go through the reified coordinator AND
;; append the flat line; external edits (capture/import/set append out-of-band)
;; are absorbed by re-migrating on mtime change. Cardinality comes from
;; fram.kernel/single? (the existing canonical vocab — NO hardcoded list, so
;; one-engine is preserved); ref-ness follows the @-prefix convention. A true
;; reversible drop-in for coord.clj: same log, same protocol, reified underneath.
;; ===========================================================================
(defn- ref-str? [x] (and (string? x) (str/starts-with? x "@")))

(defn migrate-flat->co [flat]
  (let [;; drop torn/partial lines BEFORE folding: the live flat log is appended
        ;; without fsync, so a copy/read caught mid-write can yield an assertion
        ;; missing a field — and fold itself calls single? on :p, so the incomplete
        ;; line must be dropped pre-fold. A torn line is an incomplete write that
        ;; must NOT apply (the writer retries).
        raw (fram.rt/read-log flat)
        ;; max :tx over ALL parsed lines — same set fold/max-tx (doctor's log-v)
        ;; counts, INCLUDING a torn tail (EDN-valid but missing :r). Seeding over
        ;; only the filtered asserts would lag by one when the tail is torn and make
        ;; doctor report STALE; matching fold keeps doctor FRESH.
        flat-max-tx (reduce max 0 (map #(or (:tx %) 0) raw))
        asserts (filter #(and (:l %) (:p %) (:r %)) raw)
        claims (:claims (fold/fold (vec asserts)))
        by-pred (group-by :p claims)
        st (c/new-store)
        tx (c/begin-tx! st "migrate")]
    (s/setup! st tx)
    (doseq [p (keys by-pred) :when (not (schema-preds p))]   ; skip reserved engine preds (defensive)
      (s/def-predicate! st p (if (ck/single? p) "single" "multi")
                            (if (some ref-str? (map :r (get by-pred p))) "ref" "literal") tx))
    (let [memo (atom {})
          ent! (fn [sid] (or (get @memo sid)
                             (let [id (c/entity! st)] (swap! memo assoc sid id) (s/name! st id sid tx) id)))]
      (doseq [cl claims :when (not (schema-preds (:p cl)))]
        (let [su (ent! (:l cl)) p (:p cl) r (:r cl)]
          (if (ref-str? r) (s/link! st su p (ent! r) tx) (s/assert! st su p r tx)))))
    ;; Seed the seq-space to the flat log's max :tx so (a) :version == the flat
    ;; fold's version (doctor reports FRESH, not STALE), (b) base_version stays
    ;; coherent, and (c) projected flat :tx CONTINUE the flat space (no collision;
    ;; coord.clj can still fold the log on rollback).
    (swap! st assoc :next-seq flat-max-tx)
    (swap! st update :txs assoc tx {:seq flat-max-tx :agent "migrate"})
    ;; :log nil — DROP-IN: the flat log is canonical and is written ONLY by the
    ;; daemon's append-flat!; the reified store must NOT dump v2 :k-records into it.
    {:store st :log nil :lock (Object.)}))

(defn boot-flat! [flat]
  (reset! flat-canonical? true)
  (reset! co (migrate-flat->co flat))
  (reset! flat-log flat)
  (reset! flat-mtime (stamp flat))
  (reset! cache {:index nil :version -1})
  (index!) @co)

;; absorb external edits (capture/import/set append to the flat log out-of-band).
(defn maybe-reload! []
  (when (and @flat-canonical? @flat-log (not= (stamp @flat-log) @flat-mtime))
    (reset! co (migrate-flat->co @flat-log))
    (reset! flat-mtime (stamp @flat-log))
    (reset! cache {:index nil :version -1})
    (index!)))

(defn serve-flat-daemon [port flat]
  (boot-flat! flat)
  (println (str "reified coordinator (drop-in over flat log): "
                (count (c/current-claims (:store @co))) " live claims, canonical=" flat))
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
        status (client port {:op :status})
        rp (replay (:log @co))]
    (future-cancel server)
    (println "\n=== reified coordinator concurrency proof (over the socket) ===")
    (println (format "clients=%d attempts=%d -> commits=%d rejects=%d (contention fired: %s)"
                     n-clients attempts total-commits total-rejects (if (pos? total-rejects) "yes" "no")))
    (let [checks [["illegal (part_of-self) writes that slipped through = 0" (zero? illegal-ok)]
                  ["owner on @T is single-valued -> exactly 1 live" (= 1 owner-live)]
                  ["contention actually fired (some rejects)" (pos? total-rejects)]
                  [":status reports live claims" (pos? (:claims status))]
                  ["v2 log replays to the live view (durable)" (= (live-triples (:store @co)) (live-triples rp))]]
          fails (remove second checks)]
      (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
      (if (empty? fails)
        (println "\nStage 7 (daemon): reified coordinator over the socket —" (count checks) "/" (count checks) "PASS")
        (do (println "\nStage 7 (daemon):" (count fails) "FAILED") (System/exit 1))))))

(let [[cmd p log flat] *command-line-args*]
  (case cmd
    ;; v2-log canonical + optional flat projection (design A)
    "serve"      (serve-daemon (Integer/parseInt (or p "7977"))
                               (or log (str (System/getProperty "user.dir") "/data/claims-v2.log"))
                               flat)
    ;; DROP-IN: flat log canonical, reified engine over it (design B) — the safe
    ;; reversible swap for coord.clj: `serve-flat 7977 <claims.log>`
    "serve-flat" (serve-flat-daemon (Integer/parseInt (or p "7977"))
                                    (or log (str (System/getProperty "user.dir") "/data/claims.log")))
    "test"       (run-test (Integer/parseInt (or p "7988")))
    nil))
