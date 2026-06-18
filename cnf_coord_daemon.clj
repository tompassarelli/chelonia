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
(def refers-version (atom -1))       ; the co version refers_to was last materialized at

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
(defn- append-flat! [op te p r seq]
  (when @flat-log
    (with-open [os (java.io.FileOutputStream. (str @flat-log) true)]
      (.write os (.getBytes (str (pr-str {:tx seq :op op :l te :p p :r r :ts (fram.rt/now-ts) :by "coord"}) "\n") "UTF-8"))
      (.flush os)
      ;; DURABILITY (finding #13): fsync the append before we acknowledge {:ok}.
      ;; In drop-in (serve-flat) mode the v2-log append-tx!/fsync path is dead
      ;; (:log nil), so the flat log is the ONLY durable record — without .force,
      ;; a crash after .flush but before the OS writeback loses an already-acked
      ;; commit. .force(true) flushes data to disk so the acked write survives.
      (.force (.getChannel os) true))
    (reset! flat-mtime (stamp @flat-log))))

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
    (when-not (or (schema-preds pstr) (resolve-preds pstr))
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

;; kind from the value: @-prefixed => ref (link), else literal (assert) — exactly
;; the convention the migration loader used, so daemon writes stay consistent with
;; the migrated store.
(defn- kind-of [r] (if (and r (str/starts-with? (str r) "@")) :link :assert))

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
              (apply-commit-delta! pre te p))
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
(defn- strip-resolve-claims! [st]
  (let [m @st
        rp-ids (set (keep (fn [[vid v]] (when (resolve-preds v) vid)) (:values m)))
        victims (set (keep (fn [[cid cl]] (when (rp-ids (:p cl)) cid)) (:claims m)))]
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
    (count victims)))

;; Materialize refers_to over the warm store, cached by version. Whole-corpus
;; per version (the correct FIRST CUT — scoped re-resolve is a later step). Under
;; dlock (called from handle): clear stale refers_to, run the resolver, set version.
;; The resolver opens a tx (begin-tx! bumps :next-seq + records a :txs entry) — that
;; would bump current-seq and so make refers-version chase a moving target AND poison
;; the S1 :query cache key. So we SNAPSHOT the seq-space + supersedes-pred before, and
;; restore them after: the freshly-minted refers_to claims/values/ids are KEPT (next-id
;; stays advanced so a later real commit mints past them), but :txs / :next-seq are rolled
;; back (current-seq unchanged) and :supersedes-pred is restored to the migrate store's
;; cnf-supersedes (the resolver re-points it at "supersedes"; daemon writes need cnf-supersedes).
(defn materialize-refers! []
  (let [st (:store @co)
        before @st]
    (strip-resolve-claims! st)
    (resolve/resolve-warm-store! st)         ; side-effect: writes refers_to into st
    (swap! st assoc
           :next-seq        (:next-seq before)
           :supersedes-pred (:supersedes-pred before)
           :txs             (:txs before))
    (reset! refers-version (current-seq @co))))

(defn ensure-refers! []
  (when (not= @refers-version (current-seq @co))
    (materialize-refers!)))

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

;; resolve a target binding spec to its node entity-id in `co`. Accepts a direct node
;; name "@mod#id", OR a (module name) pair resolved via the def-binding tables (value
;; OR type defs) the resolver builds from the warm corpus — the SAME resolution the
;; EDN path uses, so the daemon and EDN agree on which node a binding name denotes.
(defn- target-node [req]
  (let [st (:store @co)]
    (cond
      (:te req)                              ; "@mod#id" -> entity-id
      (s/resolve-name st (:te req))
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

(declare maybe-reload!)

(defn handle [req]
  (locking dlock                       ; serialize reload + writes + reads (drop-in mode)
    (maybe-reload!)                     ; absorb external flat edits (no-op in v2-log mode)
    (case (:op req)
      :version  {:version (current-seq @co)}
      :assert   (do-assert (:te req) (:p req) (:r req) (:base req))
      :retract  (do-retract (:te req) (:p req) (:r req) (:base req))
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
      :callers  (do (ensure-refers!)
                    (let [B (target-node req)]
                      (if B
                        {:callers (vec (callers-of-in-store (:store @co) B))
                         :target  (s/name-of (:store @co) B)
                         :version (current-seq @co)}
                        {:error "no such binding" :te (:te req) :module (:module req) :name (:name req)
                         :version (current-seq @co)})))
      {:error "unknown op"})))

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
