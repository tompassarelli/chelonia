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
(def read-hidden-preds #{"bound_to"})
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

;; reset all S3.3 derived state — called when `co` is REBUILT wholesale (boot /
;; external flat reload): the in-memory refers_to + export snapshot belong to the OLD
;; store, so the next materialize must be a fresh cold whole-corpus pass.
(defn- reset-refers-state! []
  (reset! dirty-modules #{})
  (reset! export-snapshot {})
  (reset! materialized? false)
  (reset! refers-version -1)
  (reset! last-materialize nil)
  (reset! corpus-groups nil))

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

(defn- notify-subs! [event]
  (let [line (str (pr-str event) "\n")]
    (.execute notify-exec
      (fn []
        (reset! subscribers
                (vec (filter (fn [w]
                               (try (.write ^BufferedWriter w line) (.flush ^BufferedWriter w) true
                                    (catch Throwable _ false)))
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
    (when-not (#{"set-body" "upsert-form" "insert-form" "insert-comment" "rename"} (:op spec))
      (throw (ex-info (str "edit-min: unknown verb '" (:op spec) "' (known: set-body, upsert-form, insert-form, insert-comment, rename)")
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
          scope? (#{"set-body" "upsert-form" "insert-form"} (:op spec))
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
              (do (swap! subscribers conj w)
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
