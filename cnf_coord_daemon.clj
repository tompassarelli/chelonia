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
         '[fram.kernel :as ck]
         '[fram.fold :as fold] '[fram.query :as q] '[fram.rt])
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

;; ---- state: one reified coordinator + a cached read index ------------------
(def co (atom nil))                  ; {:store :log :lock} — reified canonical (v2 log)
(def flat-log (atom nil))            ; the flat log, now a PROJECTION the cold CLI folds
(def cache (atom {:index nil :version -1}))
(def subscribers (atom []))
(def dlock (Object.))                ; serializes reload + writes + reads (drop-in mode)
(def flat-mtime (atom nil))          ; last-seen flat-log stamp (to detect external edits)
(def flat-canonical? (atom false))   ; drop-in mode: flat log is canonical, reload absorbs edits
(def schema-preds #{"name" "cardinality" "value_kind" "cnf-supersedes"})

;; ---- warm scope-correct code-intelligence (refers_to materialized over `co`) -
;; resolve.clj's lexical resolver (loadable as a library) writes refers_to + render-
;; marker claims into a store. We run it OVER the warm `co` store, version-cached.
;; These predicates are DERIVED / in-memory: they must (a) never reach the flat log,
;; (b) never leak into the S1 :query warm cache (which keys on current-seq), and
;; (c) never bump current-seq. claim->triple filters them out of every read projection
;; (so :query/:warm-check/the read view never see them); the materialize step rolls
;; back the seq-space the resolver's tx consumed.
(def resolve-preds #{"refers_to" "keep_spelling" "qualifier" "ctor_prefix" "accessor_field" "supersedes"})
;; #(a) identity: bound_to is DURABLE (persisted to the flat log — an authored identity edge,
;; reference -> binding's stable @mod#int) but for option-1 scope is kept OUT of read projections
;; (:query/datalog/warm-cache/tripwire) — render+resolve read it off the store directly. Filtered
;; HERE (read view) WITHOUT being a resolve-pred (which would strip it from the store + roll back seq).
;; withdrawn_* are the first-class retraction SURFACE (thread H): claims-about-a-cid
;; recording who/when/why a claim was cancelled. Like cnf-supersedes they are bookkeeping
;; ON cid-subjects, not domain data — filter them from the warm read view / :query / index
;; (the resolve layer reads them off the store directly via cnf_coord/withdrawal-of).
(def read-hidden-preds #{"bound_to" "withdrawn_by" "withdrawn_at" "withdrawn_reason"})
(def refers-version (atom -1))       ; the co version refers_to was last materialized at

;; ---- S3.3: scoped re-resolve state -----------------------------------------
;; Whole-corpus re-resolve on every code edit is O(corpus) per commit — the swarm
;; write-ceiling. S3.3 makes re-resolve MODULE-GRANULAR: track which modules an
;; edit touched (dirty), classify by EXPORT-SET delta (not syntactic site), and
;; re-resolve ONLY the affected module set (dirty ∪ export-changed consumers).
;; dirty-modules  : modules with an asserted/retracted AST claim since last materialize.
;; export-snapshot: {module -> #{exported/top-level name}} captured at the last
;;                  materialize — the classifier diffs the live set against it.
;; materialized?  : has refers_to been fully materialized at least once (cold gate —
;;                  the FIRST materialize is necessarily whole-corpus). maybe-reload!
;;                  (external flat rebuild) resets it, forcing a fresh whole pass.
;; last-materialize: instrumentation for the gate — {:mode :scoped|:whole :walked #{mods}
;;                  :stripped <n> :forms-walked <n>}. Read via the :refers-debug op.
(def dirty-modules (atom #{}))
(def export-snapshot (atom {}))
(def materialized? (atom false))
(def last-materialize (atom nil))
;; module of an AST node name "@kernel#127" -> "kernel" (same parse resolve.clj uses).
(defn- module-of-name [nm]
  (when (string? nm)
    (when-let [[_ m] (re-matches #"@([^#]+)#\d+" nm)] m)))
;; record that a commit touched te's module (te is "@mod#int"); skip non-AST te.
(defn- mark-dirty! [te]
  (when-let [m (module-of-name te)] (swap! dirty-modules conj m)))
;; The incremental corpus cache (ensure-corpus-groups! below) — declared here so the reload-reset
;; can invalidate it; the fns that use it are defined after with-resolve-read.
(def corpus-groups (atom nil))    ; {module-src -> [entity-ids]} | nil (cold/invalidated)
;; calls_defn closure cache (thread 019f1010-2705) — declared here so reset-refers-state!
;; can invalidate it; ensure-calls! + the fns that read it are defined after ensure-refers!.
(def calls-version (atom -1))     ; the refers-version calls_defn was last derived at
(def calls-cache   (atom nil))    ; {:edges [[caller callee]] :blast {callee -> #{callers}} :defns {name -> meta}}

;; reset all S3.3 derived state — called when `co` is REBUILT wholesale (boot /
;; external flat reload): the in-memory refers_to + export snapshot belong to the OLD
;; store, so the next materialize must be a fresh cold whole-corpus pass.
(defn- reset-refers-state! []
  (reset! dirty-modules #{})
  (reset! export-snapshot {})
  (reset! materialized? false)
  (reset! refers-version -1)
  (reset! last-materialize nil)
  (reset! corpus-groups nil)
  (reset! calls-version -1)            ; calls_defn closure belongs to the OLD store
  (reset! calls-cache nil))

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

;; flat-log projection: each reified commit also appends the flat {:op :l :p :r}
;; line the CLI's cold fold reads — so "files are pure projections of the reified
;; store" (Stage 7) and existing reads keep working UNCHANGED across the cutover.
;; Refreshes flat-mtime so our OWN write isn't mistaken for an external edit.
;; flat-log batching: a multi-claim commit collects its lines and writes+fsyncs ONCE (1 fsync per
;; commit, NOT per claim — the per-claim fsync was the dominant authoring cost, ~13 fsyncs/def).
;; *flat-batch* (an atom of line-strings) is bound around a commit; nil => write immediately.
(def ^:dynamic *flat-batch* nil)
(defn- flat-line [op te p r seq]
  (str (pr-str {:tx seq :op op :l te :p p :r r :ts (fram.rt/now-ts) :by "coord"}) "\n"))
(defn- write-flat-lines! [lines]
  (when (and @flat-log (seq lines))
    (with-open [os (java.io.FileOutputStream. (str @flat-log) true)]
      (doseq [^String ln lines] (.write os (.getBytes ln "UTF-8")))
      (.flush os)
      ;; DURABILITY (finding #13): ONE fsync flushes the whole commit's appends before we ack {:ok}.
      ;; In drop-in (serve-flat) mode the flat log is the ONLY durable record; .force(true) makes the
      ;; acked write survive a crash. Batching keeps the same guarantee (whole commit fsync'd at once).
      (.force (.getChannel os) true))
    (reset! flat-mtime (stamp @flat-log))))
(defn- append-flat! [op te p r seq]
  (if *flat-batch*
    (swap! *flat-batch* conj (flat-line op te p r seq))    ; defer — flushed once at commit end
    (write-flat-lines! [(flat-line op te p r seq)])))       ; immediate (single-op / non-batched callers)
(defn- flush-flat-batch! []
  (when *flat-batch*
    (let [t0 (System/nanoTime) lines @*flat-batch*]
      (write-flat-lines! lines)
      (reset! *flat-batch* [])
      (when (= "1" (System/getenv "FRAM_PROF"))
        (binding [*out* *err*] (println (format "  flush(fsync) n=%d %.1fms" (count lines) (/ (- (System/nanoTime) t0) 1e6))))))))

;; render one live claim cid into the SAME (l-name p-str r-rendered) shape build-index
;; wants: subject -> name, predicate -> literal, object -> literal (value) | name (ref).
;; Returns nil for a schema-pred OR resolve-pred claim (both excluded from the read view).
;; The single filter point: reified->claims, lp-live-triples, AND the warm cache all
;; funnel through here, so filtering BOTH sets here is what keeps the DERIVED refers_to
;; + render markers (materialized over `co` for :callers) invisible to :query, the
;; :warm-check tripwire, and the read view — the corpus :query sees is exactly the AST
;; claims the flat log ingested, identical whether or not refers_to has been materialized.
(defn- claim->triple [st cid]
  (let [cl (c/claim-of st cid) pstr (c/literal st (:p cl))]
    (when-not (or (schema-preds pstr) (resolve-preds pstr) (read-hidden-preds pstr))
      [(s/name-of st (:l cl)) pstr
       (if (c/value-object? st (:r cl)) (c/literal st (:r cl)) (s/name-of st (:r cl)))])))

;; read-bridge: reified live view -> the flat (l p r) Claim vec build-index wants.
(defn reified->claims [c0]
  (let [st (:store c0)]
    (->> (c/current-claims st)
         (keep (fn [cid] (when-let [t (claim->triple st cid)] (ck/->Claim (nth t 0) (nth t 1) (nth t 2)))))
         vec)))

;; The live (l p r) triples on ONE (te-name, p-str) group, projected exactly as
;; reified->claims would — the authoritative post-commit state of just that group.
;; Empty when te/p don't resolve or p is a schema-pred. Bounded by the group's
;; cardinality (1 for a single-valued pred), so reconciling against it is cheap.
(defn- lp-live-triples [c0 te p]
  (let [st (:store c0) lid (s/resolve-name st te) pid (c/value-id st p)]
    (if (and lid pid (not (schema-preds p)))
      (set (keep #(claim->triple st %) (c/by-lp st lid pid)))
      #{})))

;; ---- index-accelerated read path (warm) ------------------------------------
;; The scan path (fram.query/run) pulls the WHOLE "triple" relation per literal
;; (datalog match-lit). For the common shape — ONE non-recursive rule whose body is
;; "triple" literals with bound predicate+object — we instead probe a by-[p r] index.
;; The index is STRING-KEYED and built from the SAME claims the scan sees, so it is
;; provably a regrouping of the scan's own tuples: NO int<->string translation, hence
;; no silent-mistranslation hazard. q/run stays the untouched ORACLE; anything not of
;; the simple shape (recursion, negation, derived rels, unbound p/r) falls back to it.
;; A by-[l p] index is carried ALONGSIDE by-[p r]: it scopes the delta to the
;; (l,p) group a write touches, so a single-valued assert that SUPERSEDES the prior
;; value can drop the victim without scanning the corpus (see apply-commit-delta!).
(defn- idx-build [claims]
  (reduce (fn [acc c]
            (let [t [(:l c) (:p c) (:r c)]]
              (-> acc (update :triples conj t)
                  (update-in [:by-pr [(:p c) (:r c)]] (fnil conj #{}) t)
                  (update-in [:by-lp [(:l c) (:p c)]] (fnil conj #{}) t))))
          {:triples #{} :by-pr {} :by-lp {}} claims))
;; Drop a key whose bucket emptied (DON'T leave it mapped to #{}) — idx-build never
;; emits an empty-set entry, it just omits the key, so the incremental index must do
;; the same or its REPRESENTATION drifts from a fresh fold (warm-check :by-pr-eq
;; false: equal triple-set, dangling empty bucket — queries stay correct since
;; lit-candidates treats #{} and an absent key identically, but the tripwire fires).
(defn- bucket-update [m k v]
  (let [nb (disj (get m k #{}) v)] (if (empty? nb) (dissoc m k) (assoc m k nb))))
;; O(1) delta maintenance on the triple set + both indexes (sets => add/remove + dedup).
(defn- idx-add [idx t]
  (-> idx (update :triples conj t)
      (update-in [:by-pr [(nth t 1) (nth t 2)]] (fnil conj #{}) t)
      (update-in [:by-lp [(nth t 0) (nth t 1)]] (fnil conj #{}) t)))
(defn- idx-del [idx t]
  (-> idx (update :triples disj t)
      (update :by-pr (fn [m] (bucket-update m [(nth t 1) (nth t 2)] t)))
      (update :by-lp (fn [m] (bucket-update m [(nth t 0) (nth t 1)] t)))))

(defn- var-term? [t] (and (map? t) (contains? t :var)))
(defn- unify1 [arg val s]
  (if (var-term? arg)
    (let [k (:var arg) b (get s k ::none)]
      (if (= b ::none) (assoc s k val) (if (= b val) s nil)))
    (if (= arg val) s nil)))
(defn- unify-tuple [args tup s]
  (if (not= (count args) (count tup)) nil
    (loop [a args t tup acc s]
      (cond (nil? acc) nil (empty? a) acc
            :else (recur (rest a) (rest t) (unify1 (first a) (first t) acc))))))
(defn- resolve-arg [arg s] (if (var-term? arg) (get s (:var arg) ::unbound) arg))
;; candidate tuples for a "triple" literal: by-pr probe when BOTH p,r ground, else scan.
(defn- lit-candidates [idx litt s]
  (let [args (:args litt)
        p (resolve-arg (nth args 1) s) r (resolve-arg (nth args 2) s)]
    (if (and (not= p ::unbound) (not= r ::unbound))
      (get (:by-pr idx) [p r] [])
      (:triples idx))))
(defn- eval-body-idx [idx body]
  (reduce (fn [substs litt]
            (reduce (fn [acc s]
                      (reduce (fn [a tup] (let [s2 (unify-tuple (:args litt) tup s)]
                                            (if s2 (conj a s2) a)))
                              acc (lit-candidates idx litt s)))
                    [] substs))
          [{}] body))
(defn- ground-head [args s] (mapv (fn [t] (if (var-term? t) (get s (:var t)) t)) args))
;; the simple shape the index serves; everything else -> q/run (the oracle).
(defn- simple-query? [q]
  (and (map? q) (not (contains? q :strata)) (vector? (:rules q)) (= 1 (count (:rules q)))
       (let [rule (first (:rules q)) body (:body rule)]
         (and (map? rule) (vector? body) (seq body)
              (not= (:rel (:head rule)) "triple")
              (every? (fn [l] (and (map? l) (= "triple" (:rel l)) (not (:neg l))
                                   (vector? (:args l)) (= 3 (count (:args l))))) body)))))
;; index-accelerated run — SAME validation boundary as q/run, SAME head-tuple set.
(defn- idx-run [idx q]
  (let [errs (q/validate q)]
    (if (seq errs) {:error errs}
      (let [rule (first (:rules q))
            substs (eval-body-idx idx (:body rule))
            tuples (reduce (fn [acc s] (conj acc (ground-head (:args (:head rule)) s))) #{} substs)]
        {:ok (vec tuples)}))))

;; warm read cache kept CONSISTENT with the coordinator under writes (the live write
;; path): whole-rebuild on a cold/divergent version, then O(1) incremental delta-apply
;; on each in-lockstep commit — so a write no longer forces an O(corpus) reprojection
;; (the swarm write-ceiling). :claims is a SET of Claims (O(1) add/remove); :idx is the
;; triple-set + by-[p r]; :index (kernel, for :validate) is lazy/whole, off the hot path.
(defn warm! []
  (let [v (current-seq @co)]
    (when (not= v (:version @cache))
      (let [claims (reified->claims @co)]
        (reset! cache {:claims (set claims) :idx (idx-build claims) :index nil :version v})))
    @cache))
(defn index! []
  (let [c (warm!)]
    (or (:index c) (let [ix (ck/build-index (vec (:claims c)))] (swap! cache assoc :index ix) ix))))
(defn warm-claims [] (vec (:claims (warm!))))
(defn warm-idx [] (:idx (warm!)))
;; apply a just-committed (te p) edit to the warm cache IFF the cache was current as of
;; the pre-commit seq; else invalidate so the next warm! whole-rebuilds (correctness
;; floor — incremental only when provably in lockstep). We reconcile the whole (te,p)
;; GROUP against the store's authoritative post-commit live set rather than applying
;; the wire tuple alone: a single-valued ASSERT also SUPERSEDES the prior value, so
;; applying only the new tuple left the superseded victim live in the cache (warm !=
;; cold — a genuine cache bug the gate's supersede step caught). Group-reconcile drops
;; the victim AND adds the new tuple in one correct step, op-agnostically (assert /
;; retract / supersede / ref all flow through it). Cost is O(group cardinality) — the
;; cache's by-[l p] gives the old group, the store gives the new — not O(corpus).
(defn apply-commit-delta! [pre te p]
  (let [post (current-seq @co)]
    (when (> post pre)
      (swap! cache
        (fn [c]
          (if (= (:version c) pre)
            (let [old (get-in c [:idx :by-lp] {})
                  old-g (get old [te p] #{})                ; cache's tuples on (te,p)
                  new-g (lp-live-triples @co te p)          ; store's live tuples on (te,p)
                  to-del (clojure.set/difference old-g new-g)
                  to-add (clojure.set/difference new-g old-g)
                  idx' (as-> (:idx c) ix
                         (reduce idx-del ix to-del)
                         (reduce idx-add ix to-add))
                  claims' (as-> (:claims c) cs
                            (reduce (fn [s t] (disj s (ck/->Claim (nth t 0) (nth t 1) (nth t 2)))) cs to-del)
                            (reduce (fn [s t] (conj s (ck/->Claim (nth t 0) (nth t 1) (nth t 2)))) cs to-add))]
              {:claims claims' :idx idx' :index nil :version post})
            (assoc c :version -1)))))))

;; Subscriber delivery is BEST-EFFORT and OFF the write path (finding #3): a slow
;; or stuck subscriber (TCP send buffer full) must NOT stall commits, which run
;; under dlock. We hand the event to a single-threaded executor so the committing
;; thread returns immediately; delivery happens later and a wedged subscriber only
;; backs up the (unbounded, but commit-independent) notify queue, never dlock.
;; A subscriber whose .write/.flush throws (or whose socket SO_TIMEOUT trips) is
;; dropped. Single thread = events stay ordered.
(def ^:private notify-exec
  (java.util.concurrent.Executors/newSingleThreadExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r] (doto (Thread. r "cnf-notify") (.setDaemon true))))))

;; P5 — scoped subscribe. A subscriber MAY register a filter so the daemon pushes only
;; relevant commits instead of the firehose (efficiency at scale). Backward-compatible:
;; flt=nil => firehose (every commit), identical to pre-P5. A filter is
;; {:addrs #{..} :watch #{..} :node "@agent:<uuid>"}; an event passes if it's a message
;; to one of my addrs, a commit on a thread I watch, or a change to my own node (so the
;; client re-scopes live on role/watch updates).
(defn- sub-match? [flt {:keys [l p r]}]
  (or (nil? flt)
      ;; a MESSAGE (send -> p"to") OR a peer-COMMAND envelope (send-cmd -> p"target")
      ;; addressed to one of my addrs. "target" lands last in the envelope, so matching
      ;; it is the trigger; the reactor then reads the full @cmd:<id>. (v1 debt 019f1184-e244)
      (and (#{"to" "target"} p) (contains? (:addrs flt) r))
      (contains? (:watch flt) l)
      (= l (:node flt))))

(defn- notify-subs! [event]
  (let [line (str (pr-str event) "\n")]
    (.execute notify-exec
      (fn []
        (reset! subscribers
                (vec (filter (fn [{:keys [^BufferedWriter w flt]}]
                               (if (sub-match? flt event)
                                 (try (.write w line) (.flush w) true   ; push; drop on disconnect
                                      (catch Throwable _ false))
                                 true))                                 ; no match -> keep subscriber, don't push
                             @subscribers)))))))

;; ref-shape? — is a STRING value a node reference (a link), vs a literal that
;; merely starts with '@'? The convention is "@-prefixed => ref", but a bare "@"
;; or "@ " is a legitimate LITERAL (e.g. a comment lexeme discussing the `@id`
;; syntax — tools.bclj/import.bclj/main.bclj all carry one). A real reference —
;; a thread id (@2026-06-15-150040) or a code node-name (@mod#123) — is "@" + at
;; least one char AND contains NO whitespace (ids/node-names are whitespace-free by
;; construction). So: starts with '@', length > 1, no whitespace. This closes a
;; render-from-log fidelity hole where "@" was mis-stored as a link to a phantom
;; node, and the renderer then got an entity-id where it expected the string "@".
(defn- ref-shape? [^String s]
  (and (> (.length s) 1)
       (= \@ (.charAt s 0))
       (not (re-find #"\s" s))))

;; kind from the value: a ref-shaped @-string => ref (link), else literal (assert)
;; — exactly the convention the migration loader uses, so daemon writes stay
;; consistent with the migrated store.
(defn- kind-of [r] (if (and (string? r) (ref-shape? r)) :link :assert))

;; forward ref: the-model §9 cascade is defined after do-retract (it calls both
;; do-retract + do-assert), but do-assert calls it — declare so SCI resolves it.
(declare terminal-cascade!)

;; reserved engine predicates (identity + metadata) — a DOMAIN write to one would
;; collide with the reified schema layer and silently corrupt; reject at the boundary.
(defn- do-assert [te p r base]
  (if (schema-preds p)
    {:reject [(str "reserved predicate '" p "' (engine-internal; use a domain predicate)")] :version (current-seq @co)}
    (let [pre (current-seq @co)
          res (commit! @co "coord" te p (kind-of r) r base)]
      (if (:ok res)
        (do (when-not (:idempotent res)
              (append-flat! "assert" te p r (:ok res))
              (apply-commit-delta! pre te p)
              (mark-dirty! te))                    ; S3.3: this module's refers_to are stale
            (notify-subs! {:event :commit :version (:ok res) :op "assert" :l te :p p :r r})
            ;; the-model §9: a successful, non-idempotent terminal transition
            ;; (outcome|abandoned) cascades in the SAME serialized turn — retract
            ;; any live driver and close any running clock session. Routes through
            ;; do-retract/do-assert so each cascade write inherits append-flat! +
            ;; notify-subs! + OCC. Idempotent on replay (a 2nd terminal assert is
            ;; :idempotent => skipped; a driver-free, clock-free thread no-ops).
            (when (and (not (:idempotent res)) (ck/vec-contains? ck/terminal-preds p))
              (terminal-cascade! te))
            {:ok (:ok res)})
        {:reject (:reject res) :version (:version res)}))))

(defn- do-retract [te p r base]
  (if (schema-preds p)
    {:reject [(str "reserved predicate '" p "'")] :version (current-seq @co)}
    (let [pre (current-seq @co)
          res (retract! @co "coord" te p r base)]
      (if (:ok res)
        (do (append-flat! "retract" te p r (:ok res))
            (apply-commit-delta! pre te p)
            (mark-dirty! te)                        ; S3.3: this module's refers_to are stale
            (notify-subs! {:event :commit :version (:ok res) :op "retract" :l te :p p :r r})
            {:ok (:ok res)})
        {:reject (:reject res) :version (:version res)}))))

;; the-model §9 — atomic terminal-transition cascade. Called by do-assert AFTER a
;; successful, non-idempotent terminal assert (outcome|abandoned). Runs INSIDE the
;; same serialized coordinator turn (do-assert holds no extra lock here; the
;; cascade writes re-acquire the reentrant (:lock co) via do-retract/do-assert):
;;   (1) DRIVER — a thread that has resolved is no longer being driven; retract any
;;       live driver cell on te.
;;   (2) CLOCK — any running session ON te (session_of -> te, with a live start_time
;;       and NO live end_time) is closed by asserting end_time now.
;; Both route through the EXISTING do-retract/do-assert so they inherit append-flat!
;; + notify-subs! + OCC. Idempotent on replay: a 2nd terminal assert is :idempotent
;; (cascade skipped), and a driver-free / clock-free thread no-ops here.
;; a single live LITERAL value on (sid-name, p), or nil — the post-commit state of
;; one single-valued group, read straight off the reified store (no fold).
(defn- one-live-literal [sid p]
  (let [st  (:store @co)
        lid (s/resolve-name st sid)
        pid (c/value-id st p)]
    (when (and lid pid)
      (let [cids (live-cids-lp @co lid pid)]
        (when (seq cids)
          (let [cl (c/claim-of st (elect @co cids))]    ; coexist-elect: the elected member
            (when cl (c/literal st (:r cl)))))))))

(defn- terminal-cascade! [te]
  (let [st (:store @co)]
    ;; (1) driver — a resolved thread is no longer being driven. Read the live
    ;; driver value and pass it as `r`: retract! clears the whole single-valued
    ;; group regardless of r, but a flat-log retract line MUST carry a non-nil r
    ;; (fold drops :r nil as a torn line — see fram.fold), or the clear would not
    ;; survive a cold re-fold.
    (let [eid (s/resolve-name st te)
          did (c/value-id st "driver")
          live-driver (let [cids (when (and eid did) (live-cids-lp @co eid did))]
                        (when (seq cids)
                          (let [cl (c/claim-of st (elect @co cids))]   ; coexist-elect
                            (when cl
                              (if (c/value-object? st (:r cl))
                                (c/literal st (:r cl))
                                (s/name-of st (:r cl)))))))]
      (when (some? live-driver)
        (do-retract te "driver" live-driver (current-seq @co))))
    ;; (2) running clock sessions on te (session_of -> te, live start_time, no end_time).
    (let [te-eid (s/resolve-name st te)
          sof    (c/value-id st "session_of")]
      (when (and te-eid sof)
        (doseq [scid (vec (c/by-pr st sof te-eid))]
          (let [cl (c/claim-of st scid)
                sid (when cl (s/name-of st (:l cl)))]
            (when (and sid
                       (some? (one-live-literal sid "start_time"))
                       (nil? (one-live-literal sid "end_time")))
              (do-assert sid "end_time" (fram.rt/now-iso) (current-seq @co)))))))))

;; §1.2: ready/blocked/leverage are DOMAIN projections — the engine no longer
;; serves them. The CLI/MCP fold the log locally (main/cmd-ready, cmd-json), so
;; these daemon ops were vestigial wire-protocol surface. Dropped along with the
;; fram.projections require → the daemon depends on no domain code. (:validate
;; stays: it's kernel-level structural integrity, not lifecycle.)
(defn- all-violations [idx]
  (->> (ck/thread-ids-i idx)
       (mapcat (fn [te] (map #(str (subs te 1) ": " %) (ck/violations-i idx te))))
       vec))

;; ============================================================================
;; :callers — warm scope-correct callers of a binding, from refers_to over `co`.
;; ============================================================================
;; clean slate: surgically drop EVERY live resolve-pred claim (refers_to + render
;; markers) from the cnf STORE's claims + all five indexes (idx-by-l/p/r/lp/pr) + the
;; superseded set. (Independent of the S1-fix cache's :by-lp index — this is the store,
;; not the warm cache.) The resolver assumes a clean slate, else a re-resolve over an
;; existing edge set doubles the refers_to edges. These claims are derived/in-memory
;; only, so dropping them from the map (rather than appending a supersede) is exactly
;; right — nothing durable references them, and they were never written to the flat log.
;; subj-keep? : an optional predicate on the SUBJECT node-id (:l of the resolve-pred
;; claim). nil => strip every resolve-pred claim (whole-corpus, the S3.2 behavior).
;; A set/fn => strip only those whose subject is in scope (S3.3 scoped strip: clear
;; just the affected modules' derived edges before re-walking them). Correctness:
;; a resolve-pred claim's subject IS the referencing/binding leaf, and every leaf
;; belongs to exactly one module (its @<mod># name), so scoping by subject scopes by
;; module exactly — the untouched modules' edges are left intact.
(defn- strip-resolve-claims!
  ([st] (strip-resolve-claims! st nil))
  ([st subj-keep?]
   (let [m @st
         rp-ids (set (keep (fn [[vid v]] (when (resolve-preds v) vid)) (:values m)))
         victims (set (keep (fn [[cid cl]]
                              (when (and (rp-ids (:p cl))
                                         (or (nil? subj-keep?) (subj-keep? (:l cl))))
                                cid))
                            (:claims m)))]
     (when (seq victims)
       (let [drop-from (fn [idx] (reduce-kv (fn [acc k cids]
                                              (let [kept (vec (remove victims cids))]
                                                (if (seq kept) (assoc acc k kept) acc)))
                                            {} idx))]
         (swap! st (fn [s]
                     (-> s
                         (update :claims #(reduce dissoc % victims))
                         (update :tx-of #(reduce dissoc % victims))
                         (update :objects #(reduce dissoc % victims))
                         (update :superseded #(reduce dissoc % victims))
                         (update :idx-by-l drop-from)
                         (update :idx-by-p drop-from)
                         (update :idx-by-r drop-from)
                         (update :idx-by-lp drop-from)
                         (update :idx-by-pr drop-from))))))
     (count victims))))

;; node-ids whose @<mod># name-prefix is in `mods` — the subject scope for a scoped
;; strip AND the membership test the resolver's module-set walk mirrors. Computed
;; straight off the store's `name` claims (the same source corpus-from-store! groups by).
(defn- module-node-ids [st mods]
  (let [NAME (c/value-id st "name")]
    (when NAME
      (set (keep (fn [cid]
                   (let [cl (c/claim-of st cid)
                         nm (c/literal st (:r cl))]
                     (when (contains? mods (module-of-name nm)) (:l cl))))
                 (c/by-p st NAME))))))

;; restore-seq-space! — the resolver opens a tx (begin-tx! bumps :next-seq + records a
;; :txs entry); that would bump current-seq, make refers-version chase a moving target,
;; AND poison the S1 :query cache key. So we SNAPSHOT the seq-space + supersedes-pred
;; before, and restore them after: the freshly-minted refers_to claims/values/ids are
;; KEPT (next-id stays advanced so a later real commit mints past them), but :txs /
;; :next-seq are rolled back (current-seq unchanged) and :supersedes-pred is restored to
;; the migrate store's cnf-supersedes (the resolver re-points it at "supersedes"; daemon
;; writes need cnf-supersedes).
(defn- restore-seq-space! [st before]
  (swap! st assoc
         :next-seq        (:next-seq before)
         :supersedes-pred (:supersedes-pred before)
         :txs             (:txs before)))

;; Materialize refers_to WHOLE-CORPUS over the warm store: clear ALL stale refers_to,
;; re-walk every module, restore the seq-space. The cold/first-cut path and the
;; macro-touch fallback (a defmacro edit's blast radius isn't bounded by imports).
;; The result is GROUND TRUTH — set-equal to the EDN path (S3.2). Returns the module
;; set walked (every module) for instrumentation.
(defn materialize-refers-whole! []
  (let [st (:store @co)
        before @st
        stripped (strip-resolve-claims! st)
        walk-info (atom nil)]
    (resolve/resolve-warm-store! st             ; side-effect: writes refers_to for EVERY module
      (fn [] (reset! walk-info {:forms-walked @resolve/n-forms-walked
                                :modules-walked @resolve/walked-modules})))
    (restore-seq-space! st before)
    {:stripped stripped
     :forms-walked (:forms-walked @walk-info)
     :modules-walked (:modules-walked @walk-info)}))

;; READ-ONLY binding of resolve.clj's accessors over a store: bind ctx + the marker
;; value-ids (recomputed against THIS store — store-local ids must match their store)
;; and the corpus tables, WITHOUT running run-resolution! (which would write more
;; refers_to and double the edges). refers_to is already materialized over `co`; this
;; just lets ultimate/binding-name/pred-val/name->module read it. corpus-from-store!
;; (re)derives the def-binding tables from the store so def-binding works for the
;; (module name) target lookup — it writes NO claims, only sets dynamic tables.
(defmacro with-resolve-read [store & body]
  `(let [st# ~store]
     (binding [resolve/ctx st#
               resolve/Vp     (c/value-id st# "v")
               resolve/KIND   (c/value-id st# "kind")
               resolve/REFERS (c/value-id st# "refers_to")
               resolve/FIXED  (c/value-id st# "keep_spelling")
               resolve/QUAL   (c/value-id st# "qualifier")
               resolve/CTOR   (c/value-id st# "ctor_prefix")
               resolve/ACC    (c/value-id st# "accessor_field")
               resolve/file->ents (atom {})
               resolve/srcs [] resolve/file-modframe {} resolve/file-typeframe {}
               resolve/file-accessors {} resolve/global-exports {}
               resolve/global-type-exports {} resolve/global-accessor-exports {}]
       (resolve/corpus-from-store!)            ; derive def-binding tables (writes no claims)
       ~@body)))

;; ============================================================================
;; S3.3 — the classifier + SCOPED materialize.
;; ============================================================================
;; classify-affected: given the set of dirty modules, return
;;   {:affected #{modules to re-walk}  :macro? bool  :export-changed #{mods}}
;; The rule (binding-SET delta, NOT syntactic site): for each dirty module M, diff M's
;; live export-set against the snapshot taken at the last materialize. If M's export-set
;; is UNCHANGED, only M re-resolves (an internal body edit can't change how a consumer
;; binds M's name — and re-walking ALL of M covers any internal frame-move/shadowing).
;; If it CHANGED (or M is new / deleted), M PLUS its consumers (modules importing M)
;; re-resolve, because a consumer's :refer/:as of M now binds a different surface.
;; Reads the corpus tables (call under with-resolve-read).
(defn- classify-affected [dirty snapshot]
  (let [ig          (resolve/import-graph)             ; {module -> #{imports}}
        consumers   (fn [m] (->> ig (keep (fn [[s imps]] (when (contains? imps m) s))) set))
        export-now  (fn [m] (resolve/module-export-set m))
        macro?      (boolean (some resolve/module-has-macro? dirty))   ; whole-corpus fallback
        changed     (set (filter (fn [m]
                                   (not= (export-now m)
                                         (get snapshot m ::absent)))   ; new module => ::absent != set
                                 dirty))
        affected    (reduce (fn [acc m] (into acc (consumers m))) (set dirty) changed)]
    {:affected affected :macro? macro? :export-changed changed}))

;; snapshot EVERY module's current export-set — taken right after a materialize so the
;; next edit's classifier diffs against a coherent baseline. (Whole-corpus snapshot is
;; cheap: it's table reads, no walk.)
(defn- snapshot-exports! [st]
  (reset! export-snapshot
          (with-resolve-read st
            (into {} (map (fn [m] [m (resolve/module-export-set m)])
                          (filter some? resolve/srcs))))))

;; materialize-refers-scoped! — re-resolve ONLY the affected module set:
;;   1. classify dirty -> affected (∪ export-changed consumers); macro-touch => whole.
;;   2. strip refers_to for ONLY the affected modules' node subjects.
;;   3. resolve/resolve-modules! walks ONLY those modules (full tables, partial walk).
;;   4. restore the seq-space; re-snapshot export-sets; clear dirty.
;; The untouched modules' refers_to are left exactly as the previous materialize wrote
;; them — sound because nothing they bind changed. Returns instrumentation.
(defn materialize-refers-scoped! []
  (let [st       (:store @co)
        before   @st
        dirty    @dirty-modules
        {:keys [affected macro? export-changed]}
        (with-resolve-read st (classify-affected dirty @export-snapshot))]
    (if macro?
      ;; defmacro touched — blast radius unbounded by imports; whole-corpus (sound).
      (let [{:keys [stripped]} (materialize-refers-whole!)]
        (snapshot-exports! st)
        (reset! dirty-modules #{})
        {:mode :whole-macro-fallback :walked :all :stripped stripped :export-changed export-changed})
      (let [keep-ids (module-node-ids st affected)
            stripped (strip-resolve-claims! st (or keep-ids #{}))
            walk-info (atom nil)]
        (resolve/resolve-modules! st affected
          (fn [] (reset! walk-info {:forms-walked @resolve/n-forms-walked
                                    :modules-walked @resolve/walked-modules})))
        (restore-seq-space! st before)
        (snapshot-exports! st)
        (reset! dirty-modules #{})
        {:mode :scoped :walked affected :stripped stripped :export-changed export-changed
         :forms-walked (:forms-walked @walk-info)
         :modules-walked (:modules-walked @walk-info)}))))

;; ensure-refers! — keep refers_to current for the next :callers read. COLD (never
;; materialized, or an external flat reload reset us): whole-corpus + snapshot. WARM
;; (some module dirty since last materialize): scoped re-resolve of just the affected
;; set. CLEAN (no dirty modules): nothing to do. refers-version still tracks the seq
;; the materialized edges reflect, for the gate's reasoning + status. The version is
;; NOT the trigger any more (scoped tracks dirty modules, which is finer than seq).
(defn ensure-refers! []
  (cond
    (not @materialized?)
    (let [r (materialize-refers-whole!)]
      (snapshot-exports! (:store @co))
      (reset! dirty-modules #{})
      (reset! materialized? true)
      (reset! refers-version (current-seq @co))
      (reset! last-materialize (assoc r :mode :whole-cold :walked :all)))
    (seq @dirty-modules)
    (let [r (materialize-refers-scoped!)]
      (reset! refers-version (current-seq @co))
      (reset! last-materialize r))
    :else nil))

;; ============================================================================
;; :blast / :concern-overlap — the WARM scope-correct call graph (thread 019f1010-2705).
;; ============================================================================
;; calls_defn is a defn->defn edge derived by lifting the materialized refers_to up to
;; the ENCLOSING defn (resolve/call-edges) — scope-correct (same-named fns in different
;; modules are distinct @mod#int nodes, never merged) and rename-stable (keyed on node
;; identity, not spelling). The edge set + its transitive blast closure are VERSION-
;; CACHED on refers-version, so they are re-derived only when CODE actually changed —
;; never on a footprint declare (a @concern->@node claim leaves the call graph alone) nor
;; per overlap query. blast(D) = D's transitive callers (who breaks if D changes), shared
;; with who-calls via resolve/blast-closure. (v1: call-edges re-derives whole-corpus on a
;; code edit; an O(delta) calls_defn — thread D's discipline — is the scale follow-up.)
;; (calls-version / calls-cache atoms are declared up near corpus-groups so reset-refers-state! can invalidate them.)

(defn ensure-calls! []
  (ensure-refers!)                                     ; calls_defn lifts the warm refers_to
  (when (not= @calls-version @refers-version)
    (let [st (:store @co)
          {:keys [defn-meta edges]} (with-resolve-read st (resolve/call-edges))
          nm   (fn [leaf] (s/name-of st leaf))         ; @mod#int identity — footprint joins on these names
          named-edges (mapv (fn [[a b]] [(nm a) (nm b)]) edges)
          {:keys [blast]} (resolve/blast-closure named-edges)
          defns (into {} (map (fn [[leaf m]] [(nm leaf) m]) defn-meta))]
      (reset! calls-cache {:edges named-edges :blast blast :defns defns})
      (reset! calls-version @refers-version)))
  @calls-cache)

;; the @mod#int node-name set in a concern's blast CLOSURE: its footprint nodes plus
;; every node that transitively CALLS one of them. Caller-direction on BOTH sides makes
;; the closure intersection symmetric — A-changes-callee-of-B and B-changes-caller-of-A
;; are both caught (a callee's caller pulls the other concern's node into the overlap).
(defn- closure-of [blast nodes]
  (reduce (fn [acc n] (into (conj acc n) (get blast n))) #{} nodes))

;; {concern-name -> #{footprint node-name}} read LIVE from the warm store — sees a peer's
;; committed-but-unrendered footprint claim with no render and no merge.
(defn- footprint-by-concern [st]
  (when-let [FP (c/value-id st "footprint")]
    (reduce (fn [m cid]
              (let [cl (c/claim-of st cid)
                    c  (s/name-of st (:l cl))
                    r  (:r cl)
                    n  (if (c/value-object? st r) (c/literal st r) (s/name-of st r))]
                (update m c (fnil conj #{}) n)))
            {} (c/by-p st FP))))

;; ============================================================================
;; INCREMENTAL CORPUS CACHE (per-verb O(edited-module), not O(total-app))
;; ============================================================================
;; corpus-from-store! otherwise reduces over EVERY name claim in the whole store on EVERY commit
;; (O(total-app)) — the dominant per-op authoring cost for medium/large apps. But module-defs /
;; forms-of / wrapper-of only read each module's STABLE `beagle-file` wrapper eid (which never
;; changes across commits) plus that wrapper's children FROM THE STORE (always current). So the
;; module->entity-ids map can be seeded ONCE and reused: edits into existing modules need no
;; update (new defs are read off the wrapper's store children), and the verb skips the O(total)
;; reduce. Re-seed only when the target module is absent (a brand-new module) or after a reload.
;; (corpus-groups atom is declared above, near reset-refers-state!, so reload can invalidate it.)
(defn ensure-corpus-groups! [module]
  (when (or (nil? @corpus-groups) (not (contains? @corpus-groups module)))
    (with-resolve-read (:store @co)        ; cold corpus-from-store! (the full reduce) -> file->ents
      (reset! corpus-groups @resolve/file->ents)))
  @corpus-groups)
(defn invalidate-corpus-groups! [] (reset! corpus-groups nil))

;; resolve a target binding spec to its node entity-id in `co`. Accepts a direct node
;; name "@mod#id", OR a (module name) pair resolved via the def-binding tables (value
;; OR type defs) the resolver builds from the warm corpus — the SAME resolution the
;; EDN path uses, so the daemon and EDN agree on which node a binding name denotes.
;;
;; For the :te path we follow `ultimate` so that a node which is itself a REFERENCE
;; (a leaf carrying refers_to, e.g. the `k/->Claim` reference @import#398) resolves to
;; the BINDING it denotes (here the kernel defrecord Claim @kernel#298), chasing
;; re-export/alias chains. This is idempotent for binding nodes — `ultimate` returns a
;; binding unchanged (a binding has no refers_to) — so callers may key :callers on a
;; reference site and have the daemon perform reference->ultimate->reverse-lookup in
;; ONE round-trip, exactly the resolution a text agent must do by hand. A {:module
;; :name} target is already a binding, so it is NOT re-ultimated.
(defn- target-node [req]
  (let [st (:store @co)]
    (cond
      (:te req)                              ; "@mod#id" -> entity-id, then ultimate->binding
      (when-let [n (s/resolve-name st (:te req))]
        (with-resolve-read st (resolve/ultimate n)))
      (and (:module req) (:name req))
      (with-resolve-read st (resolve/def-binding (:module req) (:name req)))
      :else nil)))

;; callers-of(B): the set of [module, rendered-name] for every referencing leaf whose
;; refers_to (followed transitively through re-export chains via `ultimate`) lands on
;; B. Pure over the warm store — refers_to is already materialized; with-resolve-read
;; only binds the read accessors. The rendered-name is computed with resolve.clj's OWN
;; render logic (binding-name + the ctor/qualifier/accessor/keep_spelling markers) so
;; it is byte-identical to what extract-file! emits for that leaf — hence identical to
;; the EDN-path callers-of (which runs this exact code over its EDN-resolved store).
(defn callers-of-in-store [st B]
  (when B
    (with-resolve-read st
      (->> (c/by-p resolve/ctx resolve/REFERS)          ; every live refers_to claim
           (map #(c/claim-of resolve/ctx %))
           (keep (fn [cl]
                   (let [L (:l cl)]
                     (when (= B (resolve/ultimate (:r cl)))   ; ultimate target is B (chains)
                       (let [nm     (resolve/binding-name (resolve/refers-target L))
                             cpfx   (resolve/pred-val L "ctor_prefix")
                             afield (resolve/pred-val L "accessor_field")
                             qual   (resolve/pred-val L "qualifier")
                             fixed? (seq (c/by-lp resolve/ctx L resolve/FIXED))
                             rendered (cond fixed? (resolve/sym-val L)   ; :rename keeps own spelling
                                            cpfx   (str cpfx nm)
                                            afield (str (str/lower-case nm) "-" afield)
                                            qual   (str qual "/" nm)
                                            :else  nm)]
                         [(resolve/name->module (s/name-of resolve/ctx L)) rendered])))))
           set))))

;; ============================================================================
;; S3.3 gate — the STABLE, id-free keyset of materialized refers_to edges.
;; ============================================================================
;; structural-path: a node's slot-path from its module's beagle-file root, using the
;; AST's parent->child slot edges (fN / childN / segN / commentN / tail). It is id-free
;; (slot STRINGS) and stable across re-resolve (re-resolve writes refers_to, never the
;; structural fN edges), so two distinct references with the same rendered name are
;; distinguished by WHERE they sit — the set can't silently undercount a real diff.
(defn- parent-slot-index [st]
  ;; {child-node -> [parent-node "slot"]}. `child` edges duplicate fN; skip them so a
  ;; node has ONE structural parent edge. A node may appear under multiple slots only
  ;; via fN (the canonical), so we key on the fN/segN/commentN/tail slot.
  (let [m @st]
    (reduce (fn [acc [cid cl]]
              (let [pstr (c/literal st (:p cl)) r (:r cl)]
                (if (and (integer? r) (string? pstr)
                         (or (resolve/ord-pos? pstr) (re-matches #"seg\d+" pstr)   ; #36: ord-pos? = old f<int> OR new CRDT key
                             (re-matches #"comment\d+" pstr) (= pstr "tail")))
                  (assoc acc r [(:l cl) pstr])
                  acc)))
            {} (:claims m))))
(defn- node-path [psi node]
  (loop [n node acc []]
    (if-let [[p slot] (get psi n)]
      (recur p (conj acc slot))
      (vec (reverse acc)))))            ; root-down slot path

;; refers-keyset: over a store whose refers_to is materialized, the SET of stable keys
;; — one per live refers_to edge. Each key: [referencing-module ref-rendered-name
;; structural-path target-module target-ultimate-name]. Rendered name + render markers
;; reuse the exact extract-file!/callers-of render logic, so the key tracks the SAME
;; spelling the projection emits. Pure read — no mutation.
(defn refers-keyset [st]
  (with-resolve-read st
    (let [psi (parent-slot-index st)]
      (->> (c/by-p resolve/ctx resolve/REFERS)
           (map #(c/claim-of resolve/ctx %))
           (keep (fn [cl]
                   (let [L (:l cl) D (resolve/ultimate (:r cl))
                         nm     (resolve/binding-name (resolve/refers-target L))
                         cpfx   (resolve/pred-val L "ctor_prefix")
                         afield (resolve/pred-val L "accessor_field")
                         qual   (resolve/pred-val L "qualifier")
                         fixed? (seq (c/by-lp resolve/ctx L resolve/FIXED))
                         rendered (cond fixed? (resolve/sym-val L)
                                        cpfx   (str cpfx nm)
                                        afield (str (str/lower-case nm) "-" afield)
                                        qual   (str qual "/" nm)
                                        :else  nm)]
                     [(resolve/name->module (s/name-of resolve/ctx L))
                      rendered
                      (node-path psi L)
                      (resolve/name->module (s/name-of resolve/ctx D))
                      (resolve/binding-name D)])))
           set))))

;; the gate compares the SCOPED-maintained keyset (read off `co`) against a fresh
;; WHOLE-CORPUS rebuild (over a CLONE so `co`'s scoped state is untouched). A clone is
;; (atom @st): the store value is persistent/immutable, so swap!s on the clone never
;; reach the original. We strip ALL refers_to on the clone, resolve-warm-store! (whole
;; corpus = ground truth), and read its keyset. Symmetric-difference 0 ⇔ scoped==truth.
(defn refers-keyset-resp []
  (let [st     (:store @co)
        scoped (refers-keyset st)
        clone  (atom @st)
        _      (strip-resolve-claims! clone)              ; clear ALL derived edges on the clone
        _      (resolve/resolve-warm-store! clone)        ; whole-corpus GROUND TRUTH
        ground (refers-keyset clone)
        symdiff (clojure.set/union (clojure.set/difference scoped ground)
                                   (clojure.set/difference ground scoped))]
    {:scoped-size (count scoped)
     :ground-size (count ground)
     :symdiff-size (count symdiff)
     :symdiff (vec (take 40 symdiff))
     :version (current-seq @co)}))

;; ============================================================================
;; :edit-min — THE MINIMAL-OP AUTHORING EDIT (Build A: edits as minimal,
;; commutable transactions through the coordinator wire).
;; ============================================================================
;; The whole-module path (bin/fram-commit-code) renders the edited module, emit-edn's
;; it into a FRESH numbering, and commits the WHOLE-module delta vs the coordinator —
;; ~7800 ops for a 1-line set-body, because emit-edn RENUMBERS every node after the
;; edit point. That false-conflicts two agents editing DIFFERENT defns in the SAME
;; module (each rewrites the module), which kills disjoint-edit commutation — the
;; entire thesis.
;;
;; :edit-min instead commits exactly the verb's OWN mint/supersede ops. The authoring
;; verb (resolve.clj verb-set-body!/verb-rename!/verb-upsert-form!) already writes a
;; SMALL claim delta: it mints a new body subtree (kind/v/fN claims on fresh nodes),
;; points the parent's body fN edge at the new root, and SUPERSEDES the old body fN
;; edges. We:
;;   1. CLONE the warm store ((atom @st) — persistent map, O(1), swap!s don't touch
;;      `co`), record `since` = the clone's :next-id.
;;   2. run the verb over the CLONE via resolve/run-verb-warm! (NO text, NO emit-edn,
;;      NO whole-module render). The verb mints/supersedes against LOG-RESIDENT node
;;      identity, exactly as the text path would.
;;   3. HARVEST the delta from the clone:
;;        - NEW entities (objects >= since, not values/claims): the minted AST nodes.
;;          Each needs STABLE coordinator identity — assign @<mod>#<int> at the next
;;          free int for the module (the same @<mod>#<int> shape migrate-flat->co /
;;          s/name! use), memoized clone-eid -> wire-name.
;;        - NEW AST claims (cid >= since, p in the emit-edn vocabulary): translate
;;          (l p r) to wire NAMES (subject + ref object via the name map; literal
;;          object verbatim) -> :assert ops.
;;        - NEW supersede markers (cid >= since, p == supersedes-pred): each victim
;;          (the marker's :r) is an OLD AST claim; translate its (l p r) to names ->
;;          :retract op. (Derived resolve-pred claims the verb's re-resolve! wrote on
;;          the clone are NOT committed — the log carries 0 derived lines.)
;;   4. apply the harvested asserts + retracts to the REAL `co` THROUGH do-assert/
;;      do-retract — the SAME single-(te,p,r) OCC wire every commit uses. So the edit
;;      gets per-(te,p)-group base-version OCC, flat-log persistence + fsync, dirty-
;;      marking (scoped re-resolve), and notify, all for free. Two disjoint same-module
;;      edits touch DISJOINT (te,p) groups => they NEVER conflict (commute by
;;      construction). Order: retract old fN edges FIRST, then assert leaves (kind/v)
;;      before parent fN re-points (so a referenced child exists before its parent
;;      points at it).
;;
;; Returns {:ok true :module M :asserts <n> :retracts <n> :ops <n> :new-nodes <n>}
;; or {:reject ...}. The minimal-op result is BYTE-IDENTICAL (render(log)) to the
;; whole-module path for the same edit — same outcome, minimal mechanism.
;; ============================================================================
;; the emit-edn AST vocabulary — the predicates a code subtree is made of. A code
;; delta NEVER touches a non-AST pred (name/cardinality are schema; refers_to et al.
;; are derived). Mirrors bin/fram-commit-code ast-pred? exactly.
(defn- ast-pred-str? [p]
  (or (#{"kind" "v" "child" "tail" "style" "placement"} p)
      (boolean (and (string? p)
                    (or (re-matches #"f\d+(?:\.\d+)*~(?:\d+|PENDING)" p)   ; #36: new CRDT position key (incl PENDING tie)
                        (re-matches #"(?:f|seg|comment)\d+" p))))))         ; old f<int> + seg/comment (dual)

;; next free @<mod>#<int> for a module, from the store's existing `name` claims. New
;; minted nodes are numbered ABOVE every existing int so they never collide with an
;; ingested node id (or another concurrent edit's nodes, once that edit is committed —
;; each fresh mint reads the current max, and commits serialize under dlock).
(defn- next-module-int [st module]
  (let [NAME (c/value-id st "name")
        pfx  (str "@" module "#")
        mx   (if NAME
               (reduce (fn [acc cid]
                         (let [nm (c/literal st (:r (c/claim-of st cid)))]
                           (if (and (string? nm) (str/starts-with? nm pfx))
                             (if-let [[_ d] (re-matches #"@[^#]+#(\d+)" nm)]
                               (max acc (parse-long d)) acc)
                             acc)))
                       0 (c/by-p st NAME))
               0)]
    (inc mx)))

;; SERIALIZED node-name allocation (Build A). Clone-side next-module-int RACES: two
;; same-base edits read the same local max and mint identical @mod#int names -> collision
;; under true concurrency (proven). This atomic counter, seeded above the GLOBAL max at
;; boot, hands DISJOINT name-int ranges to concurrent edits by construction (swap! atomic),
;; so minting needs no global write-lock — the per-(te,p) OCC carries the rest.
(def node-name-seq (atom 0))
(defn- global-max-name-int [st]
  (let [NAME (c/value-id st "name")]
    (if NAME
      (reduce (fn [acc cid]
                (let [nm (c/literal st (:r (c/claim-of st cid)))]
                  (if-let [[_ d] (and (string? nm) (re-matches #"@[^#]+#(\d+)" nm))]
                    (max acc (parse-long d)) acc)))
              0 (c/by-p st NAME))
      0)))
(defn- seed-name-seq! [st] (reset! node-name-seq (global-max-name-int st)))
(defn- reserve-name-ints! [n]                 ; atomically reserve n consecutive name-ints
  (let [hi (swap! node-name-seq + n)] (vec (range (inc (- hi n)) (inc hi)))))

;; CRDT (commute, #36): the verb computes the ORDER PATH on the lock-free clone
;; (ord-append / ord-between) and mints the position predicate "f<path>~PENDING". Here
;; we set the TIE to the new node's ATOMIC name-int (the :r-node's @mod#<int>, already
;; allocated by reserve-name-ints!) — the positional analog of name allocation. Two
;; concurrent same-gap inserts share the path but get DISTINCT ties -> distinct keys ->
;; BOTH land, ordered by tie (commute). This generalizes D (append) to insert-anywhere.
(defn- allocate-positions [asserts]
  (mapv (fn [[te p r :as op]]
          (if (and (string? p) (str/ends-with? p "~PENDING"))
            (let [tie (or (some-> (re-matches #"@[^#]+#(\d+)" (str r)) second) "0")]
              [te (str (subs p 0 (- (count p) (count "PENDING"))) tie) r])
            op))
        asserts))

;; #(a) O(1) rename precondition: before renaming a def, PERSIST a durable bound_to edge from
;; every reference of it to the def's stable @mod#int. The rename then changes only the binding's
;; display name; references keep their identity edge and a cold re-render follows it to the CURRENT
;; name (instead of re-deriving by spelling and missing the renamed def). Idempotent.
;;
;; Runs under dlock (caller). ensure-refers! first materializes the warm refers_to (spelling-derived
;; on the first rename; identity-preserving on later ones) over `co`; B = the def binding node-id
;; (def-binding via with-resolve-read, the SAME node refers_to points at). For each reference leaf
;; whose refers_to lands on B, do-assert a `bound_to` link (leaf -> B's @mod#int). do-assert appends
;; to the flat log (durable) and commits to the warm store; bound_to is multi-valued + survives
;; strip-resolve-claims! (not a resolve-pred) and is filtered from read projections (read-hidden-preds).
(defn- persist-bound-for-rename! [spec]
  (ensure-refers!)                                   ; materialize warm refers_to (frames + edges)
  (let [st     (:store @co)
        REFp   (c/value-id st "refers_to")
        B      (target-node {:module (:module spec) :name (:old spec)})]
    (when (and B REFp)
      (let [BND     (or (c/value-id st "bound_to") (c/value! st "bound_to"))
            v0      (current-seq @co)
            B-name  (s/name-of st B)
            already (set (map #(:l (c/claim-of st %)) (c/by-p st BND)))
            ref-leaves (->> (c/by-p st REFp)
                            (map #(c/claim-of st %))
                            (filter #(= B (:r %)))
                            (map :l) distinct)]
        (doseq [leaf ref-leaves :when (not (already leaf))]
          (do-assert (s/name-of st leaf) "bound_to" B-name v0))))))

;; do-edit-min: run the verb over a CLONE, harvest its minimal delta as wire ops, and
;; commit those through do-assert/do-retract on the REAL `co`. te-naming for new nodes
;; is assigned here (the verb mints nameless local entities on the clone).
(defn- do-edit-min [spec]
  (let [module (:module spec)]
    (when (str/blank? module) (throw (ex-info "edit-min: :module required" {})))
    ;; (#26) reject UNKNOWN verbs early — before the expensive clone/corpus build and before
    ;; they can fall through to run-verb-warm!'s `(System/exit 2)` default, which would HARD-EXIT
    ;; the daemon on a malformed client request. Known verbs: set-body, upsert-form, rename.
    (when-not (#{"set-body" "upsert-form" "insert-form" "insert-comment" "rename" "delete" "reorder"} (:op spec))
      (throw (ex-info (str "edit-min: unknown verb '" (:op spec) "' (known: set-body, upsert-form, insert-form, insert-comment, rename, delete, reorder)")
                      {:reject :unknown-verb})))
    ;; #(a) GRAPH RENAME IS NOW O(1) — references carry DURABLE identity. verb-rename! rewrites
    ;; the DEF binding's spelling only; references follow `bound_to` (the binding's stable @mod#int),
    ;; persisted HERE before the rename so a cold re-render resolves them by IDENTITY, not by spelling
    ;; (the old failure: cnf_rename_spelling_check.clj — old-spelled refs re-derived to nothing and
    ;; rendered the OLD name). persist-bound-for-rename! is idempotent + appends bound_to to the flat
    ;; log (durable). Content-hashes (increment (b)) are explicitly OUT of scope: identity is the
    ;; existing sequential @mod#int. (The text-path CLI rename still works as a same-process projection.)
    ;; #(a) rename-identity is persisted INSIDE the commit dlock below (ONE acquisition) —
    ;; NOT in a separate dlock here. A separate lock would leave a lock-free window (the verb
    ;; compute) in which a concurrent agent could commit a NEW reference AFTER the snapshot but
    ;; BEFORE the rename commits, landing it with no identity edge (the red-team's snapshot-window
    ;; race). Folded into the commit lock so persist + rename are atomic.
    (let [real   (:store @co)
          clone  (atom @real)                       ; O(1) structural clone; verb writes here only
          since  (:next-id @clone)
          ;; SCOPE the verb's frame build to the edited module(s) for the single-module
          ;; verbs (set-body/upsert-form read ONLY their own module's def-binding/frame).
          ;; rename is cross-module (consumer-collision + capture across all srcs read
          ;; the whole corpus' require/frame tables) — leave it whole (nil scope).
          ;; delete is ALSO whole-corpus: its no-orphaned-refs invariant reads refers_to
          ;; across every module (a surviving cross-module ref to a victim must REFUSE),
          ;; so it must NOT be scoped. reorder is a pure wrapper order-key re-spell (reads
          ;; only its own module's wrapper) — single-module, scope it like the inserts.
          scope? (#{"set-body" "upsert-form" "insert-form" "reorder"} (:op spec))
          scope  (when scope? (fn [s] (str/includes? s module)))
          ;; the supersedes pred the migrate store uses (set by s/setup!); the verb's
          ;; resolve-warm-store! re-points :supersedes-pred at "supersedes", but the
          ;; clone-local marker CLAIMS it writes carry whatever pred-id it used. We
          ;; harvest by re-reading the clone's :supersedes-pred AFTER the run.
          ;; A REJECTED edit must NOT kill the daemon: bind *reject!* to THROW (the CLI
          ;; default exits the process). The throw unwinds the verb + is caught by the
          ;; :edit-min handler arm, returning {:reject ...} to the client.
          _      (let [tv0 (System/nanoTime)
                       res (binding [resolve/*reject!*
                                     (fn [code] (throw (ex-info (str "verb rejected the edit (code " code ")")
                                                                {:reject :verb :code code})))
                                     resolve/*capture-only?* true   ; mint/supersede only — no re-resolve, no EDN projection
                                     resolve/*resolve-walk?* false  ; Build B: skip the whole-corpus walk
                                     resolve/*corpus-scope* scope    ; Build B: scope frames to the edited module
                                     resolve/*corpus-cache* (when scope? (ensure-corpus-groups! module))]  ; skip O(total) name reduce
                             (resolve/run-verb-warm! clone spec))]
                   (when (= "1" (System/getenv "FRAM_PROF"))
                     (binding [*out* *err*] (println (format "PROF run-verb-warm!=%.1fms" (/ (- (System/nanoTime) tv0) 1e6)))))
                   res)
          t-hv   (System/nanoTime)
          m      @clone
          sup-pid (:supersedes-pred m)
          ;; Build B — O(delta), not O(corpus): a clone shares ONE monotonic :next-id
          ;; for entities AND claims, and fresh-id! returns the POST-increment value —
          ;; so `since` (the pre-verb :next-id) is the LAST OLD id, and every object the
          ;; verb minted has id in (since, (:next-id m)] (inclusive of the FINAL mint,
          ;; e.g. set-body's parent body-fN re-point). We iterate that RANGE and `get`
          ;; each from :objects/:claims — O(verb delta), ~130 lookups — instead of
          ;; scanning the whole ~78k-entry :claims map three times (old O(corpus) cost).
          since-ids (range (inc since) (inc (:next-id m)))
          ;; clone-local entity-id -> wire NAME. Existing nodes already carry a `name`
          ;; claim (inherited from the real store); new nodes (eid >= since with no
          ;; name) get a fresh @<mod>#<int>, numbered above every existing int.
          name-of* (fn [eid] (s/name-of clone eid))
          new-eids (->> since-ids
                        (filter (fn [id] (and (contains? (:objects m) id)
                                              (not (contains? (:values m) id))
                                              (not (contains? (:claims m) id))
                                              (nil? (name-of* id)))))
                        vec)
          ;; assign names to the new entities (sequential, above the current max int).
          name-ints (reserve-name-ints! (count new-eids))   ; SERIALIZED atomic alloc — concurrent-safe; replaces clone-side next-module-int (which raced)
          eid->name (into {} (map (fn [eid i] [eid (str "@" module "#" i)]) new-eids name-ints))
          wire-name (fn [eid] (or (eid->name eid) (name-of* eid)))
          ;; render a claim's (l p r) into wire (te p r-spec): subject -> name; object
          ;; -> name if it's an entity (a ref/link), else the literal value verbatim.
          ->wire (fn [cl]
                   (let [l (:l cl) p (c/literal clone (:p cl)) r (:r cl)
                         te (wire-name l)
                         rs (if (c/value-object? clone r) (c/literal clone r) (wire-name r))]
                     (when (and te (some? rs)) [te p rs])))
          ;; the verb's NEW claims = the claim ids in [since, next-id) (O(delta)).
          new-cid-claims (keep (fn [cid] (when-let [cl (get (:claims m) cid)] [cid cl])) since-ids)
          ;; ASSERTS: every NEW AST claim (kind/v/fN/... ; skip derived + schema preds).
          new-claims (->> new-cid-claims
                          (map second)
                          (filter (fn [cl] (let [p (c/literal clone (:p cl))]
                                             (ast-pred-str? p)))))
          asserts (vec (keep ->wire new-claims))
          ;; RETRACTS: every NEW supersede marker's VICTIM, as its (l p r) by name.
          ;; A marker is a claim (cid >= since) whose pred == the supersedes pred; its
          ;; :r is the OLD (superseded) claim id. We retract that old claim's AST edge.
          victim-cids (->> new-cid-claims
                           (filter (fn [[_ cl]] (= (:p cl) sup-pid)))
                           (map (fn [[_ cl]] (:r cl))))
          retracts (vec (keep (fn [vcid]
                                (when-let [vcl (get (:claims m) vcid)]
                                  (when (ast-pred-str? (c/literal clone (:p vcl)))
                                    (->wire vcl))))
                              victim-cids))
          t-cm (System/nanoTime)]
      ;; commit through the REAL coordinator wire. Retract old edges first, then
      ;; assert leaves (kind/v) before parent fN re-points. Each op is OCC-checked at
      ;; the current version of its OWN (te,p) group (commit! base-version), so two
      ;; disjoint same-module edits never false-conflict.
      ;; (B) LOCK BOUNDARY — serialize ONLY the commit. Everything ABOVE (clone, verb,
      ;; harvest, atomic name reservation) ran LOCK-FREE => concurrent. INSIDE this lock:
      ;; the do-retract/do-assert ops AND, reached through them, apply-commit-delta!
      ;; (warm-cache) + append-flat! (flat log) — the two that would corrupt if left
      ;; outside. So: compute concurrent, commit serial, cache+log safe.
      (locking dlock
       ;; #(a) persist rename-identity UNDER THE SAME lock as the commit (no lock-free window).
       ;; ensure-refers! (inside) re-derives fresh, so a reference a concurrent agent committed
       ;; BEFORE this lock is captured into bound_to; one arriving AFTER sees the renamed def
       ;; (old spelling correctly resolves to nothing — a stale ref, not a silent mis-bind).
       ;; v0 is read AFTER, so the rename asserts' OCC base reflects the bound_to commits.
       (when (= "rename" (:op spec)) (persist-bound-for-rename! spec))
       (let [v0 (current-seq @co)
             asserts (allocate-positions asserts)  ; CRDT (#36): set PENDING ties to new nodes' atomic name-ints -> concurrent same-gap inserts get distinct keys, both land (commute)
             rej (atom nil)]
        ;; BATCH the flat-log appends: collect every claim's line, write+fsync ONCE at the end
        ;; (1 fsync per commit, not per claim). do-assert/do-retract still update the warm store
        ;; per op; only the durable-log fsync is batched — same durable-before-ack guarantee.
        (binding [*flat-batch* (atom [])]
         (doseq [[te p r] retracts :while (nil? @rej)]
           (let [res (do-retract te p r v0)] (when (:reject res) (reset! rej {:op :retract :te te :p p :r r :res res}))))
         ;; r is already a wire value: a name STRING (ref, for fN/child/tail/...) or a
         ;; literal STRING (kind/v). do-assert's kind-of picks :link vs :assert by the
         ;; ref-shape of the string — exactly the migrate-flat->co convention.
         (let [leaf? (fn [[_ p _]] (#{"kind" "v"} p))
               ordered (concat (filter leaf? asserts) (remove leaf? asserts))]
           (doseq [[te p r] ordered :while (nil? @rej)]
             (let [res (do-assert te p r v0)]
               (when (:reject res) (reset! rej {:op :assert :te te :p p :r r :res res})))))
         (flush-flat-batch!))                          ; ONE fsync for the whole commit's appends
        (when (= "1" (System/getenv "FRAM_PROF"))
          (binding [*out* *err*] (println (format "PROF harvest=%.1fms commit=%.1fms" (/ (- t-cm t-hv) 1e6) (/ (- (System/nanoTime) t-cm) 1e6)))))
        (if @rej
          {:reject (:res @rej) :failed-op @rej :module module}
          {:ok true :module module
           :asserts (count asserts) :retracts (count retracts)
           :ops (+ (count asserts) (count retracts))
           :new-nodes (count new-eids) :name-ints name-ints
           :version (current-seq @co)}))))))

(declare maybe-reload!)
;; thread 019f100f-7fff: snapshot/tail-fold/as-of/incremental-aggregate surface,
;; defined below migrate-flat->co (so they can call it) but referenced in handle.
(declare write-snapshot! snapshot-reconcile materialize-as-of register-agg! agg-report sweep-snapshots! built-through)

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
      ;; :bump — ATOMIC add to a numeric counter (read-add-write under the lease lock, so
      ;; concurrent charges from N executors can't lose updates). Declares the predicate
      ;; single-valued (else asserts accumulate -> arbitrary reads). The swarm token budget
      ;; (@swarm budget_spent) uses it. -> {:ok seq :value <new>}. (:n may be negative.)
      :bump     (bump-counter! @co (:te req) (:p req) (:n req))
      ;; --- exclusive-lease wire verbs (agents lease @lease:<res> over the socket) ---
      ;; The lease fn enforces mutual exclusion in its OWN (:lock co); the outer dlock just
      ;; serializes with other daemon ops. A bare :assert @lease:<res> is the UNSAFE lost-update
      ;; path the lease arm exists to close — agents MUST use these. No notify-subs! (lease
      ;; changes are not broadcast), matching the fram-lease fork. Impl: cnf_coord.clj (load-file'd).
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
      ;; thread 019f100f-7fff — snapshot/compaction surface:
      ;; :snapshot writes a checkpoint (dump-log! image + @snapshot:<seq> claims);
      ;; :snapshot-reconcile is the gate (live store == from-scratch whole migrate).
      :snapshot           (if @flat-log (write-snapshot! @co @flat-log) {:error "snapshot needs flat-log (drop-in) mode"})
      :snapshot-reconcile (snapshot-reconcile)
      :built-through      {:built-through @built-through :version (current-seq @co)}
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
      ;; :blast — transitive callers of a binding over the WARM calls_defn closure (who
      ;; breaks if it changes). Target is {:te "@mod#id"} OR {:module .. :name ..}; the
      ;; node-id is resolved via ultimate so a reference site resolves to its binding.
      :blast    (let [{:keys [blast]} (ensure-calls!)
                      B (target-node req)
                      bname (when B (s/name-of (:store @co) B))
                      callers (get blast bname #{})]
                  (if bname
                    {:node bname :blast (vec callers) :count (count callers)
                     :version (current-seq @co)}
                    {:error "no such binding" :te (:te req) :module (:module req) :name (:name req)
                     :version (current-seq @co)}))
      ;; :concern-overlap — for {:te "@concern:id"}, the peer concerns whose blast CLOSURE
      ;; intersects mine, derived over the warm store: footprint read LIVE (sees peers'
      ;; committed-but-unrendered @concern->@node claims), closure via the recursive
      ;; calls_defn reaches. Footprint is multi-valued and declaring never blocks — overlap
      ;; is the SIGNAL surfaced, never a conflict. -> {:overlaps [{:concern :shared :footprint}]}.
      :concern-overlap
      (let [{:keys [blast]} (ensure-calls!)
            st (:store @co)
            fp (or (footprint-by-concern st) {})
            me (:te req)
            my-nodes (get fp me #{})
            my-clo (closure-of blast my-nodes)
            overlaps (->> (dissoc fp me)
                          (keep (fn [[c nodes]]
                                  (let [shared (clojure.set/intersection my-clo (closure-of blast nodes))]
                                    (when (seq shared)
                                      {:concern c :shared (vec shared) :footprint (vec nodes)}))))
                          vec)]
        {:concern me :footprint (vec my-nodes) :overlaps overlaps
         :version (current-seq @co)})
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
                      render (fn [cid] (let [r (:r (c/claim-of st cid))]
                                         (if (c/value-object? st r) (c/literal st r) (s/name-of st r))))
                      vals (mapv render live)]
                  ;; :value is the coexist-ELECTED member (not a blind first-live); :members/
                  ;; :ambiguous?/:values still surface the full multiplicity so a contested
                  ;; (te,pred) reads as a CHECKABLE answer, not a silently arbitrary pick.
                  {:value (when (seq live) (render (elect @co live)))
                   :members (count vals) :ambiguous? (> (count vals) 1)
                   :values vals :version (current-seq @co)})
      ;; :as-of — time-travel READ (thread H, Part B). Reconstruct the view as of seq S
      ;; (cnf_coord/live-as-of: born <= S, no supersede/withdraw marker <= S) and either
      ;; run a Datalog :query over that historical EDB (SAME q/run oracle, just fed an
      ;; as-of claims vec — the engine is untouched) or resolve one (:te,:p) group as-of.
      ;; Retraction-as-append makes this exact: a later-withdrawn claim is RE-SEEN at an
      ;; earlier S. Bounded by the snapshot floor (live-as-of folds the in-store tail).
      :as-of (let [s (:seq req) st (:store @co)]
               (cond
                 (nil? s) {:error ":as-of needs :seq"}
                 (:query req)
                 (let [cids   (live-as-of @co s)
                       claims (vec (keep (fn [cid] (when-let [t (claim->triple st cid)]
                                                     (ck/->Claim (nth t 0) (nth t 1) (nth t 2))))
                                         cids))
                       res    (q/run claims (:query req))]
                   (assoc res :as-of s :version (current-seq @co)))
                 (:te req)
                 (let [e   (s/resolve-name st (:te req))
                       pid (c/value-id st (:p req))
                       live (if (and e pid) (live-as-of-lp @co s e pid) [])
                       render (fn [cid] (let [r (:r (c/claim-of st cid))]
                                          (if (c/value-object? st r) (c/literal st r) (s/name-of st r))))
                       vals (mapv render live)]
                   {:value (when (seq live) (render (elect @co (vec live))))
                    :members (count vals) :ambiguous? (> (count vals) 1)
                    :values vals :as-of s :version (current-seq @co)})
                 :else {:error ":as-of needs :query or :te/:p"}))
      {:error "unknown op"}))))

;; ---- socket server (verbatim shape from the proven coord.clj) ---------------
;; Hardened (findings #2/#5/#19/#20): every accepted socket gets a read timeout
;; (no slow-loris), the request line is read with a hard byte cap (no heap
;; balloon), and the WHOLE handler is wrapped to catch Throwable — including
;; StackOverflowError from a deep-nested EDN payload — so a malformed/hostile
;; request returns a clean {:error} line (or just drops the conn) instead of
;; killing the per-connection thread. A reply is best-effort: if writing it also
;; throws (socket already gone), we still hit the finally and close.
;; Response wire format is EDN by DEFAULT (every existing client — byte-identical to
;; before). A request carrying {:fmt :json} (or "json") OPTS IN to cheshire JSON
;; instead (fram.rt/to-json = cheshire/generate-string): the Elixir client decodes
;; JSON ~90x faster than EDN (eden), so an opt-in JSON :query is the first-load/refresh
;; win. Gated purely on :fmt — no :fmt => pr-str, exactly as today.
(defn- serialize-resp [fmt resp]
  (if (or (= fmt :json) (= fmt "json"))
    (fram.rt/to-json resp)
    (pr-str resp)))

(defn- try-reply
  ([^BufferedWriter w resp] (try-reply w resp nil))
  ([^BufferedWriter w resp fmt]
   (try (.write w (serialize-resp fmt resp)) (.newLine w) (.flush w) (catch Throwable _ nil))))

(defn serve-conn [^Socket s]
  (try
    (.setSoTimeout s sock-read-timeout-ms)         ; bound idle/slow-loris reads
    (let [r (BufferedReader. (InputStreamReader. (.getInputStream s)))
          w (BufferedWriter. (OutputStreamWriter. (.getOutputStream s)))]
      (try
        (when-let [line (read-line-bounded r max-line-bytes)]
          (let [req (parse-req line)]
            (if (= (:op req) :subscribe)
              (do (swap! subscribers conj {:w w :flt (:filter req)})   ; P5: opt-in scoped filter (nil = firehose)
                  ;; A subscriber is long-lived: it RECEIVES pushed events and sends
                  ;; nothing, so the request-path read timeout (5s) must NOT apply or
                  ;; it would drop every idle subscriber. Disable it for this socket;
                  ;; the loop now blocks on read purely to detect disconnect (EOF).
                  ;; The 1 MiB line cap still guards against a flooding subscriber.
                  (.setSoTimeout s 0)
                  (.write w (pr-str {:subscribed (current-seq @co)})) (.newLine w) (.flush w)
                  (loop [] (when (read-line-bounded r max-line-bytes) (recur))))
              (let [resp (handle req)] (try-reply w resp (:fmt req))))))
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
;; are absorbed by re-migrating on mtime change. Cardinality comes from
;; fram.kernel/single? (the existing canonical vocab — NO hardcoded list, so
;; one-engine is preserved); ref-ness follows the @-prefix convention. A true
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
    ;; complete the bootstrap SEED (move-B keystone): kernel single-valued preds NOT
    ;; present in the flat log still get their cardinality CLAIM, so a first runtime
    ;; write supersedes (not accumulates) — the replacement for finding #12's per-write
    ;; ensure-single pin, now a one-time seed. ck/single-valued is read ONCE here; at
    ;; runtime commit! consults only the claim. (The loop above already seeded in-log
    ;; preds via ck/single?, so the guard skips them and preserves their ref/literal kind.)
    (doseq [p ck/single-valued :when (and (not (schema-preds p)) (not= "single" (s/cardinality st p)))]
      (s/def-predicate! st p "single" "literal" tx))
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

;; ===========================================================================
;; SNAPSHOT / TAIL-FOLD / AS-OF / INCREMENTAL AGGREGATES (thread 019f100f-7fff)
;; ---------------------------------------------------------------------------
;; The flat fold (fram.fold) is KEYED-LATEST-BY-:tx: a single-valued predicate keys
;; on (l,p) [an LWW cell]; a multi on (l,p,r) [the edge]; per key the MAX-:tx line
;; wins and a `retract` whose :tx dominates drops the key. So for ANY key first
;; touched in a tail T past a snapshot at seq N, every tail line has :tx > N >= every
;; snapshot-era line for that key — the tail's max-:tx line for the key dominates the
;; whole history. THEREFORE: per-key keyed-latest over T, applied onto the snapshot
;; store (whose keys T is silent on are left untouched), reconstructs EXACTLY a full
;; fold of the whole log. That is the whole correctness argument for incremental boot
;; + tail-fold reload + as-of; it is GATED at runtime by snapshot-reconcile (diff the
;; incremental store's live name-triples against a from-scratch whole-migrate).
;; This is the fix for the mislabeled "single-writer" bottleneck: the writer's one
;; job (id allocation) STAYS single + serialized; what was O(history) — boot re-fold
;; and the whole-log re-migrate on EVERY out-of-band append — becomes O(delta).
;; ===========================================================================

;; the highest flat :tx the live store reflects, and the flat-log byte length at that
;; point. flat-bytes is a SEEK HINT for the tail read; correctness is the :tx filter
;; (risk guard: "anchor boot on :tx (monotonic), not byte_offset").
(def built-through (atom 0))
(def flat-bytes    (atom 0))

(defn- snap-dir [flat] (str flat ".snapshots"))
(defn- snap-image [flat seq] (str (snap-dir flat) "/snap-" seq ".v2log"))
(defn- sidecar-path [flat] (str flat ".snap"))      ; latest-snapshot pointer (O(1) boot hint)

(defn- sha256-file [path]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 65536)]
    (with-open [is (java.io.FileInputStream. (str path))]
      (loop [] (let [n (.read is buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn- read-sidecar [flat]
  (let [f (java.io.File. (sidecar-path flat))]
    (when (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ nil)))))
(defn- write-sidecar! [flat m] (spit (sidecar-path flat) (pr-str m)))

(defn- skip-fully! [^java.io.InputStream is ^long n]
  (loop [left n] (when (pos? left) (let [s (.skip is left)] (if (pos? s) (recur (- left s)) nil)))))

;; flat-log lines (raw maps) with :tx > from-tx, read from byte `from-byte` forward.
;; UTF-8 decode (claim values carry unicode — so NOT RandomAccessFile.readLine, which
;; is ISO-8859-1 and corrupts multibyte). from-byte is a seek hint: too-low only costs
;; extra parsing, a torn first line is dropped, and the :tx filter is the real boundary.
(defn- read-log-tail [path from-byte from-tx]
  (let [f (java.io.File. (str path))]
    (if-not (and (.exists f) (pos? (.length f)))
      []
      (let [len (.length f) start (long (max 0 (min (long (or from-byte 0)) len)))]
        (with-open [is (java.io.FileInputStream. f)]
          (skip-fully! is start)
          (let [rdr (java.io.BufferedReader. (java.io.InputStreamReader. is "UTF-8"))]
            (loop [acc (transient [])]
              (let [line (.readLine rdr)]
                (if (nil? line)
                  (persistent! acc)
                  (let [m (try (let [x (edn/read-string line)]
                                 (when (and (:l x) (:p x) (:r x) (int? (:tx x))
                                            (> (long (:tx x)) (long from-tx))) x))
                               (catch Exception _ nil))]
                    (recur (if m (conj! acc m) acc))))))))))))

(defn- ref-str?* [x] (and (string? x) (ref-shape? x)))

;; keyed-latest over flat lines, mirroring fram.fold/key-of (single -> (l,p); multi ->
;; (l,p,r)); the latest by :tx wins and its :op is carried so a dominating retract
;; removes the key. Re-derived here because fram.fold/keyed-latest is private AND drops
;; retracts (we need them to supersede a snapshot-era claim).
(defn- tail-keyed-latest [lines]
  (reduce (fn [m a]
            (let [k (if (ck/single? (:p a)) [(:l a) (:p a)] [(:l a) (:p a) (:r a)])
                  prev (get m k)]
              (if (and prev (>= (long (:tx prev)) (long (:tx a)))) m (assoc m k a))))
          {} lines))

;; apply a flat-log TAIL (lines past from-tx) onto co's store as per-key group-
;; reconciled deltas — the O(delta) materialization step shared by boot / reload /
;; as-of. New predicates are declared (existing cardinality untouched). Then per
;; keyed-latest key: assert/link (single supersedes the cell via s/assert!/s/link!'s
;; replace!; multi adds the edge IFF not already live — idempotent), or retract
;; (mark the matching live claim cnf-superseded). One migrate-style tx; the seq space
;; is advanced to the tail's max :tx so :version stays == the flat fold version.
;; Mutates (:store co) in place — callers that need atomicity vs lock-free readers
;; clone the store first (see maybe-reload!). Returns co.
(defn- apply-tail! [co lines]
  (let [st (:store co)
        valid (filterv #(and (:l %) (:p %) (:r %) (int? (:tx %)) (not (schema-preds (:p %)))) lines)]
    (when (seq valid)
      (let [tx (c/begin-tx! st "tail")
            by-pred (group-by :p valid)
            memo (atom {})
            sub! (fn [sid] (or (get @memo sid)
                               (let [id (or (s/resolve-name st sid)
                                            (let [e (c/entity! st)] (s/name! st e sid tx) e))]
                                 (swap! memo assoc sid id) id)))]
        ;; declare predicates new to the store; keep any existing cardinality claim
        (doseq [p (keys by-pred)]
          (when (empty? (c/by-lp st (c/value! st p) (c/value-id st "cardinality")))
            (s/def-predicate! st p (if (ck/single? p) "single" "multi")
                                  (if (some ref-str?* (map :r (get by-pred p))) "ref" "literal") tx)))
        (doseq [[_ a] (tail-keyed-latest valid)]
          (let [p (:p a) r (:r a) single? (ck/single? p)
                su (sub! (:l a)) pid (c/value! st p)
                live (c/by-lp st su pid)]      ; already live-only
            (if (= "retract" (:op a))
              (let [sup (c/value! st "cnf-supersedes")
                    rid (when (ref-str?* r) (s/resolve-name st r))
                    victims (if single? live
                                (filter #(let [cr (:r (c/claim-of st %))]
                                           (if (ref-str?* r) (= rid cr) (= (c/value-id st r) cr))) live))]
                (doseq [old victims] (c/claim! st old sup old tx)))
              (let [exists? (some #(let [cr (:r (c/claim-of st %))]
                                     (if (ref-str?* r) (= (s/resolve-name st r) cr) (= (c/value-id st r) cr))) live)]
                (when-not (and (not single?) exists?)
                  (if (ref-str?* r) (s/link! st su p (sub! r) tx) (s/assert! st su p r tx)))))))
        (let [tmax (reduce max 0 (map :tx valid))]
          (swap! st assoc :next-seq tmax)
          (swap! st update :txs assoc tx {:seq tmax :agent "tail"}))))
    co))

;; live name-triples (store-independent: names + literals, not entity ids) — the
;; substrate for the reconcile gate, which compares an incrementally-built store to a
;; from-scratch whole-migrate of the same flat log (they MUST be set-equal).
(defn- live-name-triples [co] (set (reified->claims co)))
(defn snapshot-reconcile
  "Gate: does the live (incrementally-materialized) store equal a from-scratch whole
   migrate of the flat log? {:ok bool :inc n :fresh n}. Hot-path-free (test/admin)."
  ([] (snapshot-reconcile @co @flat-log))
  ([co flat]
   (let [fresh (migrate-flat->co flat)]
     {:ok (= (live-name-triples co) (live-name-triples fresh))
      :inc (count (reified->claims co)) :fresh (count (reified->claims fresh))})))

;; replay the nearest snapshot image, then tail-apply the flat lines past it — the
;; incremental boot. nil if no usable snapshot (caller falls back to whole migrate).
(defn- incremental-boot [snap flat]
  (let [img (java.io.File. (str (:image snap)))]
    (when (and (.exists img) (pos? (.length img))
               ;; hash gate: a torn/edited image is rejected (fall back to whole migrate)
               (or (nil? (:hash snap)) (= (:hash snap) (try (sha256-file (:image snap)) (catch Exception _ nil)))))
      (let [base {:store (replay (:image snap)) :log nil :lock (Object.)}
            tail (read-log-tail flat (:byte_offset snap) (:seq snap))]
        (apply-tail! base tail)
        {:co base :through (reduce max (long (:seq snap)) (map :tx tail))}))))

(defn boot-flat! [flat]
  (reset! flat-canonical? true)
  (let [snap (read-sidecar flat)
        ib   (when snap (try (incremental-boot snap flat) (catch Throwable _ nil)))]
    (if ib
      (do (reset! co (:co ib)) (reset! built-through (:through ib)))
      ;; cold path: no snapshot / torn image -> the proven whole-log migrate
      (let [c0 (migrate-flat->co flat)]
        (reset! co c0)
        (reset! built-through (or (:next-seq @(:store c0)) 0)))))
  (seed-name-seq! (:store @co))          ; Build A: seed the serialized name allocator above the global max
  (reset! flat-log flat)
  (reset! flat-mtime (stamp flat))
  (reset! flat-bytes (.length (java.io.File. (str flat))))
  (reset! cache {:index nil :version -1})
  (reset-refers-state!)                  ; S3.3: derived refers_to belong to the OLD store
  (index!) @co)

;; absorb external edits (capture/import append to the flat log out-of-band). The
;; per-request cost is a (stamp ...) stat; on a change we now TAIL-FOLD only the new
;; lines (:tx > built-through, read from the last byte length) onto a STRUCTURAL-SHARE
;; CLONE of the live store — O(delta), not the old O(history) whole re-migrate. The
;; clone (atom over the immutable store value) is O(1) and keeps the swap atomic, so a
;; lock-free reader sees the old OR the new store, never a half-applied tail. If the
;; mtime moved but no new :tx appeared (a compaction/rewrite that renumbered or shrank
;; the log), we fall back to the whole migrate — correctness floor.
(defn maybe-reload! []
  (when (and @flat-canonical? @flat-log)
    (let [st (stamp @flat-log)]
      (when (not= st @flat-mtime)
        (let [tail (read-log-tail @flat-log @flat-bytes @built-through)]
          (if (seq tail)
            (let [clone {:store (atom @(:store @co)) :log nil :lock (Object.)}]
              (apply-tail! clone tail)
              (reset! co clone)
              (reset! built-through (reduce max (long @built-through) (map :tx tail))))
            ;; mtime moved, no new tx from the live offset. A legit compaction keeps the
            ;; head (log max-tx >= built-through); a REGRESSION (revert/truncation — e.g.
            ;; a `git checkout` of the tracked claims.log) drops it BELOW our live state.
            ;; NEVER silently adopt a log that lost our claims.
            (let [logmax (reduce max -1 (map :tx (read-log-tail @flat-log 0 -1)))]
              (if (< logmax (long @built-through))
                (binding [*out* *err*]
                  (println (str "[fram] REFUSED reload: claims.log regressed (max-tx "
                                logmax " < live built-through " @built-through ") — a"
                                " revert/truncation, NOT an append. Kept the in-memory"
                                " state; the log file is STALE — restore before restart.")))
                (let [c0 (migrate-flat->co @flat-log)]
                  (reset! co c0)
                  (reset! built-through (or (:next-seq @(:store c0)) 0)))))))
        (reset! flat-mtime st)
        (reset! flat-bytes (.length (java.io.File. (str @flat-log))))
        (reset! cache {:index nil :version -1})
        (reset-refers-state!)
        (index!)))))

;; ---- snapshot WRITER: a thin wrapper over dump-log! + @snapshot:<seq> claims ------
;; dump-log! writes the live store as a v2 image the EXISTING replay consumes (reuse,
;; not new fold code). covers_through = the live seq at dump time; byte_offset = the
;; flat-log length at dump time (= tail start). The metadata becomes CLAIMS so "latest
;; snapshot" / "which covers seq N" / "GC candidates" are queries; a tiny sidecar
;; mirrors the latest pointer for O(1) boot discovery (claims are the source of truth).
;; Snapshot is view-relative: of_view @view:main. The @snapshot:<seq> claims land in
;; the tail (tx > covers_through) and are harmlessly re-applied on the next boot.
(defn write-snapshot! [co flat]
  (locking dlock
    (let [st (:store co)
          sq (current-seq co)
          _ (.mkdirs (java.io.File. (snap-dir flat)))
          image (snap-image flat sq)
          byteoff (.length (java.io.File. (str flat)))
          _ (dump-log! st image)
          h (sha256-file image)
          ccount (count (c/current-claims st))
          subj (str "@snapshot:" sq)]
      (doseq [[p v] [["covers_through" (str sq)] ["byte_offset" (str byteoff)]
                     ["claim_count" (str ccount)] ["image_path" image]
                     ["snapshot_hash" h] ["of_view" "@view:main"]]]
        (do-assert subj p v nil))
      (write-sidecar! flat {:seq sq :image image :byte_offset byteoff :claim_count ccount :hash h})
      ;; the snapshot's own @snapshot:<seq> claims were appended inline (do-assert),
      ;; so the LIVE store already reflects them: advance built-through past them and
      ;; re-stamp, so a following reload tail-reads only genuinely-new appends.
      (reset! built-through (current-seq co))
      (reset! flat-mtime (stamp flat))
      (reset! flat-bytes (.length (java.io.File. (str flat))))
      {:ok sq :image image :byte_offset byteoff :claim_count ccount :hash h})))

;; ---- as-of: read the graph AS IT STOOD at flat seq N ----------------------------
;; nearest snapshot with covers_through <= N, then tail-apply the lines in
;; (covers_through, N] — exactly a full fold truncated at N (same keyed-latest-by-:tx
;; domination argument, with an UPPER :tx bound). Bounded below by the snapshot floor:
;; an N below the oldest retained snapshot still works via the whole-migrate fallback
;; as long as the flat lines survive (compaction must not drop a line below a live
;; as-of horizon — see the retention sweeper). Admin/debug read, off the hot path.
(defn- fresh-co []
  (let [s (c/new-store) tx (c/begin-tx! s "asof-setup")] (s/setup! s tx) {:store s :log nil :lock (Object.)}))

;; @snapshot:<seq> metadata, read off the live store (post-boot the claims ARE the
;; registry — "which snapshot covers seq N" is a query, decision: snapshots are claims).
(defn- snapshot-entries [co]
  (let [st (:store co) cp (c/value-id st "covers_through")]
    (if-not cp []
      (vec (for [cid (c/by-p st cp)
                 :let [subj (:l (c/claim-of st cid))
                       g (fn [p] (let [v (s/lookup st subj p)] v))]]
             {:subject (s/name-of st subj)
              :covers_through (try (parse-long (str (g "covers_through"))) (catch Exception _ nil))
              :byte_offset    (try (parse-long (str (g "byte_offset"))) (catch Exception _ nil))
              :image          (g "image_path")
              :hash           (g "snapshot_hash")})))))

(defn materialize-as-of [flat n]
  (let [n (long n)
        cand (->> (snapshot-entries @co)
                  (filter #(and (:covers_through %) (<= (long (:covers_through %)) n)
                                (:image %) (.exists (java.io.File. (str (:image %))))))
                  (sort-by :covers_through))
        best (last cand)
        usable? (and best (or (nil? (:hash best))
                              (= (:hash best) (try (sha256-file (:image best)) (catch Exception _ nil)))))]
    (if usable?
      (let [base {:store (replay (:image best)) :log nil :lock (Object.)}
            tail (filterv #(<= (long (:tx %)) n)
                          (read-log-tail flat (:byte_offset best) (:covers_through best)))]
        (apply-tail! base tail))
      ;; no usable snapshot at/below N -> fold the flat log truncated at :tx <= N
      (apply-tail! (fresh-co) (filterv #(<= (long (:tx %)) n) (read-log-tail flat 0 -1))))))

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
