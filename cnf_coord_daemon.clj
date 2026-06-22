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
(require '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.set]
         '[fram.cnf :as c] '[fram.schema :as s]
         '[fram.fold :as fold] '[fram.query :as q] '[fram.rt]
         '[fram.migrate-cardinality :as mc])
(import '[java.net ServerSocket Socket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader OutputStreamWriter BufferedWriter FileInputStream]
        '[javax.net.ssl SSLContext KeyManagerFactory TrustManagerFactory]
        '[java.security KeyStore])
(load-file "cnf_coord.clj")          ; the reified coordinator library
;; resolve.clj — the store-parameterized lexical resolver (S3.1/S3.2), loaded as a
;; LIBRARY: its -main is guarded behind a recognized MODES arg, and the daemon's
;; *command-line-args* ("serve-flat" ...) is not one, so load-file runs NOTHING — it
;; only defines `resolve/resolve-warm-store!` + the read accessors. Loaded here, at
;; daemon-namespace-load time, so the `resolve/...`-qualified symbols in callers-of /
;; with-resolve-read resolve at compile time (load-file is run-once by nature).
(load-file (str (System/getProperty "user.dir") "/chartroom/src/resolve.clj"))

;; ---- self-hosted core: the pure-logic (cut 1) + atom-orchestration (cut 2) now live
;; in the Beagle module fram.coord-daemon. This shim RE-EXPORTS them as bare names so the
;; resolver-woven handlers + handle + socket + boot floor below resolve UNCHANGED and SHARE
;; the cut-2 state atoms ((def x cd/x) on an atom aliases the same object). do-assert/
;; do-retract bridge the Beagle !-suffix. *flat-batch* is a ^:dynamic var (binding can't
;; see an alias) so do-edit-min binds cd/*flat-batch* directly; subscriber delivery moved to
;; fram.rt (do-assert! fires fram.rt/notify-subs!; :subscribe registers via fram.rt/subscribe!).
(require '[fram.coord-daemon :as cd])
(require '[fram.coord-resolver :as cr])  ; resolver-woven layer ported to Beagle (cut-3 aliases below)
;; state atoms (shared object identity)
(def co cd/co) (def flat-log cd/flat-log) (def cache cd/cache) (def flat-mtime cd/flat-mtime)
(def flat-canonical? cd/flat-canonical?) (def refers-version cd/refers-version)
(def dirty-modules cd/dirty-modules) (def export-snapshot cd/export-snapshot)
(def materialized? cd/materialized?) (def last-materialize cd/last-materialize)
(def corpus-groups cd/corpus-groups) (def node-name-seq cd/node-name-seq)
;; const sets
(def schema-preds cd/schema-preds) (def resolve-preds cd/resolve-preds) (def read-hidden-preds cd/read-hidden-preds)
;; read-bridge / index / datalog / warm / writes / helpers
(def module-of-name cd/module-of-name) (def mark-dirty! cd/mark-dirty!) (def reset-refers-state! cd/reset-refers-state!)
(def flat-line cd/flat-line) (def append-flat! cd/append-flat!) (def flush-flat-batch! cd/flush-flat-batch!)
(def claim->triple cd/claim->triple) (def reified->claims cd/reified->claims) (def lp-live-triples cd/lp-live-triples)
(def idx-build cd/idx-build) (def bucket-update cd/bucket-update) (def idx-add cd/idx-add) (def idx-del cd/idx-del)
(def var-term? cd/var-term?) (def unify1 cd/unify1) (def unify-tuple cd/unify-tuple) (def resolve-arg cd/resolve-arg)
(def lit-candidates cd/lit-candidates) (def eval-body-idx cd/eval-body-idx) (def ground-head cd/ground-head)
(def simple-query? cd/simple-query?) (def idx-run cd/idx-run)
(def warm! cd/warm!) (def index! cd/index!) (def warm-claims cd/warm-claims) (def warm-idx cd/warm-idx)
(def apply-commit-delta! cd/apply-commit-delta!) (def ref-shape? cd/ref-shape?) (def kind-of cd/kind-of)
(def all-violations cd/all-violations) (def module-node-ids cd/module-node-ids) (def node-path cd/node-path)
(def ast-pred-str? cd/ast-pred-str?) (def next-module-int cd/next-module-int) (def global-max-name-int cd/global-max-name-int)
(def seed-name-seq! cd/seed-name-seq!) (def reserve-name-ints! cd/reserve-name-ints!) (def allocate-positions cd/allocate-positions)
;; Beagle !-suffix bridge (consumers call do-assert/do-retract)
(def do-assert cd/do-assert!) (def do-retract cd/do-retract!)


(def dlock cd/dlock)                 ; shared monitor (lives in cd) so do-edit-min in fram.coord-resolver locks the SAME object as the shim's outer handle

;; ---- DoS hardening knobs (findings #2/#5/#19/#20) --------------------------
;; Read timeout on every accepted socket — mirrors the CLIENT side (fram.rt
;; coord-socket, 2000ms), but a touch longer so a legitimately-slow gateway
;; round-trip isn't cut off. A slow-loris / never-sends-newline client now
;; trips SocketTimeoutException instead of pinning a thread+socket forever.
(def ^:const sock-read-timeout-ms 5000)
;; Per-connection line cap — the wire protocol is one EDN line per request, so a
;; line larger than this is malicious/buggy. Bounds BufferedReader memory growth
;; (a no-newline client can't balloon the heap) and caps edn/read-string input.
(def ^:const max-line-bytes (* 1024 1024))        ; 1 MiB
;; EDN nesting bound — clojure.edn/read-string is recursive-descent and throws
;; StackOverflowError on deep nesting (overflows ~16k deep). Reject obviously
;; over-nested input cheaply (a count of opening delimiters) BEFORE handing it to
;; the reader, so a deep-nest payload returns a clean {:error} instead of an Error.
(def ^:const max-edn-depth 200)

(defn- stamp [f] (let [fi (java.io.File. (str f))] (str (.lastModified fi) ":" (.length fi))))

;; bounded readLine: read at most `cap` chars, stopping at newline. Returns the
;; line (sans newline), nil at clean EOF, or throws ex-info {:type :line-too-long}
;; if the cap is hit with no newline — so a client streaming bytes forever can
;; neither pin the thread (setSoTimeout already bounds idle) nor exhaust the heap.
(defn- read-line-bounded ^String [^BufferedReader r ^long cap]
  (let [sb (StringBuilder.)]
    (loop []
      (let [ch (.read r)]
        (cond
          (= ch -1)  (when (pos? (.length sb)) (.toString sb))   ; EOF: partial -> return, empty -> nil
          (= ch 10)  (.toString sb)                              ; \n terminates the line
          (= ch 13)  (recur)                                     ; ignore \r (CRLF)
          :else      (do (when (>= (.length sb) cap)
                           (throw (ex-info "line too long" {:type :line-too-long})))
                         (.append sb (char ch))
                         (recur)))))))

;; cheap pre-parse depth guard: the deepest run of unmatched opening delimiters.
;; Rejecting here avoids the JVM recursive reader StackOverflowError (#5).
(defn- edn-too-deep? [^String s]
  (loop [i 0 depth 0 mx 0 in-str false esc false]
    (if (>= i (.length s))
      (> mx max-edn-depth)
      (let [c (.charAt s i)]
        (cond
          esc          (recur (inc i) depth mx in-str false)
          (and in-str (= c \\)) (recur (inc i) depth mx in-str true)
          in-str       (recur (inc i) depth mx (not (= c \")) false)
          (= c \")     (recur (inc i) depth mx true false)
          (or (= c \() (= c \[) (= c \{))
                       (let [d (inc depth)] (recur (inc i) d (max mx d) in-str false))
          (or (= c \)) (= c \]) (= c \}))
                       (recur (inc i) (max 0 (dec depth)) mx in-str false)
          :else        (recur (inc i) depth mx in-str false))))))

;; parse a request line defensively: bound depth, keep clojure.edn (never
;; read-string — no #= eval), and surface a parse failure as data, not a throw.
(defn- parse-req [^String line]
  (when (edn-too-deep? line)
    (throw (ex-info "edn too deep" {:type :edn-too-deep})))
  (edn/read-string line))


;; ============================================================================
;; resolver-woven layer (strip / materialize-refers / callers / refers-keyset /
;; target-node / do-edit-min) — PORTED to Beagle as fram.coord-resolver. This shim
;; re-exports them as bare-name aliases (cut-3) so the kept floor (handle/dispatch)
;; resolves them unchanged. Daemon state (co/dirty-modules/corpus-groups/...) is
;; shared by object identity (cd/* atoms). Internal helpers (with-resolve-read*,
;; render-ref-name, parent-slot-index, persist-bound-for-rename!, drop-victims) stay
;; private inside fram.coord-resolver. NOTE bang renames: do-edit-min -> do-edit-min!,
;; refers-keyset-resp -> refers-keyset-resp! (Beagle effectful-fn naming).
;; ============================================================================
(def strip-resolve-claims!      cr/strip-resolve-claims!)
(def restore-seq-space!         cr/restore-seq-space!)
(def materialize-refers-whole!  cr/materialize-refers-whole!)
(def materialize-refers-scoped! cr/materialize-refers-scoped!)
(def classify-affected          cr/classify-affected)
(def snapshot-exports!          cr/snapshot-exports!)
(def ensure-refers!             cr/ensure-refers!)
(def ensure-corpus-groups!      cr/ensure-corpus-groups!)
(def invalidate-corpus-groups!  cr/invalidate-corpus-groups!)
(def target-node                cr/target-node)
(def callers-of-in-store        cr/callers-of-in-store)
(def refers-keyset              cr/refers-keyset)
(def refers-keyset-resp         cr/refers-keyset-resp!)
(def do-edit-min                cr/do-edit-min!)

(declare maybe-reload!)

(defn handle [req]
  ;; (#14 socket EXPOSURE) :edit-min runs OUTSIDE the outer dlock. do-edit-min's compute
  ;; (clone/verb/harvest) is lock-free and its COMMIT phase already takes dlock itself (the (B)
  ;; boundary), so wrapping the whole op in the outer dlock re-serializes the lock-free compute
  ;; and HIDES the concurrency the logic layer + the 150-pair commute already proved. maybe-reload!
  ;; is a no-op in v2-log mode (the code daemon, where :edit-min lives), so skipping it here is
  ;; safe; the commit still serializes under dlock and is OCC-checked per (te,p) at commit time.
  (cond
    ;; LOCK-FREE read: deref the @co immutable snapshot, NO dlock. Reads don't need the
    ;; writer lock (the atom swap on commit is atomic), so a reader never serializes behind
    ;; concurrent writers. Used to measure true propagation (commit -> reader sees) without
    ;; the read-coupled-to-writer-lock artifact the dlock-wrapped :version/:status have.
    (= :version-free (:op req)) {:version (current-seq @co)}
    ;; LOCK-FREE CONTENT check: is value string (:v req) interned in the warm @co snapshot
    ;; yet? Names are unique per writer, so interned <=> that writer's def reached the store.
    ;; This is the propagation visibility signal (commit -> reader sees THIS def), off the dlock.
    (= :seen (:op req)) {:seen (boolean (c/value-id (:store @co) (:v req)))}
    (= :edit-min (:op req))
    (try (do-edit-min (:spec req))
         (catch Throwable t {:reject [(str "edit-min: " (.getMessage t))]
                             :version (current-seq @co)}))
  :else
  (locking dlock                       ; serialize reload + writes + reads (drop-in mode)
    (maybe-reload!)                     ; absorb external flat edits (no-op in v2-log mode)
    (case (:op req)
      :version  {:version (current-seq @co)}
      :assert   (do-assert (:te req) (:p req) (:r req) (:base req))
      :retract  (do-retract (:te req) (:p req) (:r req) (:base req))
      ;; --- exclusive-lease WIRE VERBS (Lodestar shadow: agents lease @lease:<res> over the socket) ---
      ;; Route to the in-process lease arm (acquire/release/fence — cnf_lease_test 10/10). The
      ;; lease fn reads holder liveness IN its own coord lock (the real mutual-exclusion seam);
      ;; the outer dlock just serializes with other daemon ops. A bare `:assert @lease:<res>` is
      ;; the UNSAFE (lost-update) path the lease arm exists to close — agents MUST use these.
      :acquire-lease (acquire-lease! @co (:holder req) (:res req) (:ttl-ms req))
      :release-lease (release-lease! @co (:holder req) (:res req))
      :fence-ok      {:fence-ok (fence-ok? @co (:res req) (:holder req) (:epoch req))}
      ;; :edit-min is handled ABOVE, outside the outer dlock (socket exposure) — see top of handle.
      :validate {:violations (all-violations (index!))}
      ;; AST/Datalog query over the WARM in-memory graph — the read surface the cold
      ;; CLI/MCP path lacked. Runs fram.query/run (validate + fixpoint) against the
      ;; version-cached claims vec, so a callers-of/blast-radius/bridge query never
      ;; pays the ~3.8s log fold. Result is q/run's {:ok tuples} | {:error msgs}
      ;; envelope, stamped with the snapshot version the answer reflects.
      :query    (let [qy (:query req)
                      use-idx (and (not (:scan req)) (simple-query? qy))
                      res (if use-idx (idx-run (warm-idx) qy) (q/run (warm-claims) qy))]
                  (assoc res :version (current-seq @co) :engine (if use-idx "index" "scan")))
      ;; gate: is the incrementally-maintained warm cache == a fresh whole rebuild?
      :warm-check (let [inc (warm!) fresh (reified->claims @co) fidx (idx-build fresh)]
                    {:consistent (and (= (:triples (:idx inc)) (:triples fidx))
                                      (= (:by-pr (:idx inc)) (:by-pr fidx))
                                      (= (:by-lp (:idx inc)) (:by-lp fidx))
                                      (= (:claims inc) (set fresh)))
                     :inc-triples (count (:triples (:idx inc))) :fresh-triples (count (:triples fidx))
                     :version (current-seq @co)})
      :status   {:version (current-seq @co) :claims (count (c/current-claims (:store @co))) :log (or @flat-log (:log @co))}
      ;; warm scope-correct callers of a binding, served from refers_to materialized
      ;; over `co` (version-cached). ensure-refers! whole-corpus re-resolves only when
      ;; the code version moved (the correct first cut); the reverse lookup is then a
      ;; by-pr/ultimate scan returning the set of [module, rendered-name] referencing
      ;; leaves. Target is {:te "@mod#id"} OR {:module .. :name ..}.
      ;; :render — WARM render (TRACK B opt). The cold CLI path (fram-render-code) pays
      ;; migrate-flat->co (log fold) + resolve-warm-store! (whole-corpus refers_to walk)
      ;; per invocation (~1.8s). Served off `co` it skips BOTH: ensure-refers! keeps
      ;; refers_to current (scoped), then project the module via extract-file! over the
      ;; warm store and return the resolved EDN. The client racket --renders the EDN.
      :render   (do (ensure-refers!)
                    (let [st (:store @co) module (:module req)
                          tmp (str (System/getProperty "java.io.tmpdir") "/fram-warmrender-" (System/nanoTime))
                          _   (.mkdirs (java.io.File. ^String tmp))
                          edn-out (str tmp "/resolved-" module ".bclj.edn")]
                      (binding [resolve/ctx st
                                resolve/tx (c/begin-tx! st "warm-render-read")
                                resolve/Vp     (c/value-id st "v")
                                resolve/KIND   (c/value-id st "kind")
                                resolve/REFERS (c/value-id st "refers_to")
                                resolve/FIXED  (c/value-id st "keep_spelling")
                                resolve/QUAL   (c/value-id st "qualifier")
                                resolve/CTOR   (c/value-id st "ctor_prefix")
                                resolve/ACC    (c/value-id st "accessor_field")
                                resolve/file->ents (atom {})
                                resolve/srcs [] resolve/file-modframe {} resolve/file-typeframe {}
                                resolve/file-accessors {} resolve/global-exports {}
                                resolve/global-type-exports {} resolve/global-accessor-exports {}]
                        (resolve/corpus-from-store!)
                        (let [the-src (some #(when (= module %) %) resolve/srcs)]
                          (if the-src
                            (do (resolve/extract-file! the-src edn-out)
                                {:edn (slurp edn-out) :module module :version (current-seq @co)})
                            {:error "no such module in warm corpus" :module module
                             :srcs (vec resolve/srcs) :version (current-seq @co)})))))
      :callers  (do (ensure-refers!)
                    (let [B (target-node req)]
                      (if B
                        {:callers (vec (callers-of-in-store (:store @co) B))
                         :target  (s/name-of (:store @co) B)
                         :version (current-seq @co)}
                        {:error "no such binding" :te (:te req) :module (:module req) :name (:name req)
                         :version (current-seq @co)})))
      ;; ---- S3.3 gate surface (test-only reads; no mutation) ------------------
      ;; :refers-ensure — force the maintenance step (scoped or cold) for the current
      ;; dirty set, then report what it did: mode, modules walked, edges stripped, and
      ;; the export-changed set. This is the (d) skipped-work evidence — a scoped run
      ;; reports :walked = exactly the affected modules, never the corpus.
      :refers-ensure (do (ensure-refers!)
                         {:last-materialize @last-materialize
                          :dirty (vec @dirty-modules)
                          :version (current-seq @co)})
      ;; :refers-keyset — the materialized refers_to edge set as STABLE, id-free keys
      ;; (referencing module + rendered name + structural fN-path + target module +
      ;; target ultimate name). The gate compares this against a fresh whole-corpus
      ;; rebuild's keyset: equality (sym-diff 0) is the scoped==whole-corpus proof.
      ;; ensure-refers! first so the scoped-maintained set is current. :scoped key
      ;; reads off `co`; :ground recomputes whole-corpus over a CLONE (never disturbs
      ;; `co`'s scoped state), so both keysets come from the same store snapshot.
      :refers-keyset (do (ensure-refers!) (refers-keyset-resp))
      ;; :resolved — surface the MULTIPLICITY of a (te,pred) group instead of hiding it
      ;; behind first-live. P-of/select-main-1 return (first) — a SELECTION, not a
      ;; uniqueness proof (#19) — so a contested single-valued field reads as a silently
      ;; arbitrary pick. This returns {:value <first> :members <n> :ambiguous? (> n 1)
      ;; :values [...]} so an agent gets a CHECKABLE answer. Pure read over the live
      ;; (l,p) group — rep-stable (no fN). (interface investigation #3)
      :resolved (let [st (:store @co)
                      e (s/resolve-name st (:te req))
                      pid (c/value-id st (:p req))
                      live (if (and e pid) (live-cids-lp @co e pid) [])
                      vals (mapv (fn [cid] (let [r (:r (c/claim-of st cid))]
                                             (if (c/value-object? st r) (c/literal st r) (s/name-of st r))))
                                 live)]
                  {:value (first vals) :members (count vals) :ambiguous? (> (count vals) 1)
                   :values vals :version (current-seq @co)})
      {:error "unknown op"}))))

;; ---- socket server (verbatim shape from the proven coord.clj) ---------------
;; Hardened (findings #2/#5/#19/#20): every accepted socket gets a read timeout
;; (no slow-loris), the request line is read with a hard byte cap (no heap
;; balloon), and the WHOLE handler is wrapped to catch Throwable — including
;; StackOverflowError from a deep-nested EDN payload — so a malformed/hostile
;; request returns a clean {:error} line (or just drops the conn) instead of
;; killing the per-connection thread. A reply is best-effort: if writing it also
;; throws (socket already gone), we still hit the finally and close.
(defn- try-reply [^BufferedWriter w resp]
  (try (.write w (pr-str resp)) (.newLine w) (.flush w) (catch Throwable _ nil)))

(defn serve-conn [^Socket s]
  (try
    (.setSoTimeout s sock-read-timeout-ms)         ; bound idle/slow-loris reads
    (let [r (BufferedReader. (InputStreamReader. (.getInputStream s)))
          w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))]
      (try
        (when-let [line (read-line-bounded r max-line-bytes)]
          (let [req (parse-req line)]
            (if (= (:op req) :subscribe)
              (do (fram.rt/subscribe! w (:filter req))   ; scoped-subscribe: nil filter => firehose (back-compat)
                  ;; A subscriber is long-lived: it RECEIVES pushed events and sends
                  ;; nothing, so the request-path read timeout (5s) must NOT apply or
                  ;; it would drop every idle subscriber. Disable it for this socket;
                  ;; the loop now blocks on read purely to detect disconnect (EOF).
                  ;; The 1 MiB line cap still guards against a flooding subscriber.
                  (.setSoTimeout s 0)
                  (.write w (pr-str {:subscribed (current-seq @co)})) (.newLine w) (.flush w)
                  (loop [] (when (read-line-bounded r max-line-bytes) (recur))))
              (let [resp (handle req)] (try-reply w resp)))))
        ;; StackOverflowError is an Error (not Exception); catching Throwable here
        ;; keeps a deep-nest / malformed line from taking down the conn thread.
        (catch java.net.SocketTimeoutException _ nil)   ; slow client: just close
        (catch Throwable t
          (try-reply w {:error (str "bad request: "
                                    (or (:type (ex-data t)) (.. t getClass getSimpleName)))}))))
    (catch Throwable _ nil)
    (finally (try (.close s) (catch Throwable _ nil)))))

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
;; password from FRAM_TLS_PASS, or read from FRAM_TLS_PASS_FILE (Docker/k8s secrets
;; mount as files — keeps the secret out of the process environ for multi-tenant hosts).
(defn- tls-pass []
  (or (System/getenv "FRAM_TLS_PASS")
      (when-let [f (System/getenv "FRAM_TLS_PASS_FILE")] (str/trim (slurp f)))))

(defn- tls-cfg []
  (let [ks (System/getenv "FRAM_TLS_KEYSTORE")
        ts (System/getenv "FRAM_TLS_TRUSTSTORE")
        pass (tls-pass)]
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
   (reset-refers-state!)                 ; S3.3: fresh store -> next materialize is cold
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
;; are absorbed by re-migrating on mtime change. Cardinality is GRAPH-SOURCED (#2):
;; read from the flat log's own (P "cardinality" V) claims (self-describing log),
;; NO hardcoded list; ref-ness follows the @-prefix convention. A true
;; reversible drop-in for coord.clj: same log, same protocol, reified underneath.
;; ===========================================================================
;; ref-str? — a value is a node LINK iff it's a ref-shaped @-string (see ref-shape?:
;; "@" + ≥1 char + no whitespace). A bare "@" / "@ " literal (a comment lexeme about
;; the `@id` syntax) is an ASSERT, NOT a link — else migration mints a phantom "@"
;; node and render-from-log breaks (string-append on an entity-id). Mirrors kind-of.
(defn- ref-str? [x] (and (string? x) (ref-shape? x)))

(defn migrate-flat->co [flat]
  (let [;; drop torn/partial lines BEFORE folding: the live flat log is appended
        ;; without fsync, so a copy/read caught mid-write can yield an assertion
        ;; missing a field — and fold reads :p's cardinality, so the incomplete
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
        ;; Cardinality is GRAPH-SOURCED (#2): read each predicate's cardinality from
        ;; the flat log's own (P "cardinality" V) claims — the migrated, self-describing
        ;; log — NOT a hardcoded kernel list. Absent => "multi" (default). def-predicate!
        ;; installs it on the pred's value-id where s/cardinality reads it; the raw
        ;; (P "cardinality" V) claims are consumed here, not migrated as entity claims.
        ;; pure derivation lives in Beagle (mc/cardinality-of-claims); the seam only orchestrates.
        card-of (mc/cardinality-of-claims claims)
        st (c/new-store)
        tx (c/begin-tx! st "migrate")]
    (s/setup! st tx)
    (doseq [p (keys by-pred) :when (and (not (schema-preds p)) (not= p "cardinality"))]
      (s/def-predicate! st p (get card-of p "multi")
                            (if (some ref-str? (map :r (get by-pred p))) "ref" "literal") tx))
    (let [memo (atom {})
          ent! (fn [sid] (or (get @memo sid)
                             (let [id (c/entity! st)] (swap! memo assoc sid id) (s/name! st id sid tx) id)))]
      (doseq [cl claims :when (and (not (schema-preds (:p cl))) (not= (:p cl) "cardinality"))]
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
  (seed-name-seq! (:store @co))          ; Build A: seed the serialized name allocator above the global max
  (reset! flat-log flat)
  (reset! flat-mtime (stamp flat))
  (reset! cache {:index nil :version -1})
  (reset-refers-state!)                  ; S3.3: derived refers_to belong to the OLD store
  (index!) @co)

;; absorb external edits (capture/import/set append to the flat log out-of-band).
;; HOT-PATH cost (finding #4): the per-request work is ONLY a cheap (stamp ...)
;; (one File.lastModified + File.length stat) compared against the last-seen
;; stamp. The O(n) migrate-flat->co rebuild runs ONLY when that stamp actually
;; changed — never on an unchanged log — so a stream of pure reads/writes with no
;; external append pays a stat, not a rebuild. We stat once and reuse it to set
;; flat-mtime, so a fresh stat is not taken twice per reload. (Reload stays under
;; dlock by necessity: swapping `co`/`cache` atomically against concurrent
;; writers is what keeps the live view and the OCC base versions coherent.)
(defn maybe-reload! []
  (when (and @flat-canonical? @flat-log)
    (let [st (stamp @flat-log)]
      (when (not= st @flat-mtime)
        (reset! co (migrate-flat->co @flat-log))
        (reset! flat-mtime st)
        (reset! cache {:index nil :version -1})
        (reset-refers-state!)            ; S3.3: external rebuild discards in-memory refers_to
        (index!)))))

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
        (do (println "\nStage 7 (daemon): reified coordinator over the socket —" (count checks) "/" (count checks) "PASS")
            (System/exit 0))   ; exit cleanly so the test frees the listener port (don't leak it)
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
