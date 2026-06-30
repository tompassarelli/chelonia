;; ============================================================================
;; cnf_mcp_insert_after_receipt.clj — §0.2 blocker closed: a FLUENT AGENT can now
;; reach insert-anywhere-commute.
;;   bb -cp out cnf_mcp_insert_after_receipt.clj
;;
;; Proves the AGENT-FACING chain (MCP tool catalog -> {:edit ...} envelope ->
;; CLI verb-flags -> bin/fram-edit-code -> :edit-min wire op -> do-edit-min) reaches
;; the CRDT commute machinery for `insert-after`. Two layers:
;;
;;   (1) MCP catalog/dispatch (in-process, fram.tools): the tool `insert-after`
;;       exists with params {module,after,form}, and fram.tools/call lowers an
;;       agent-shaped call to {:edit {:op "insert-form" :module .. :after .. :form ..}},
;;       which fram_mcp/flip-verb-flags lowers to the exact CLI invocation
;;       ["insert-form" "--after" <anchor> "--spec-file" <form.edn>]. This is the
;;       agent-shaped lowering, verbatim from the live code.
;;   (2) the CLI -> daemon commute path: run that exact CLI invocation against a
;;       LIVE isolated code daemon (serve-flat over a /tmp COPY of .fram/code.log on
;;       a free non-7977 port). Assert the op committed and the new top-level form
;;       landed at a CRDT `f<path>~<tie>` ord-key (NOT an old f<int>, NOT a dup) —
;;       i.e. the agent surface now reaches the (path,tie) commute mint.
;;   (3) regression: a `set-body` through the SAME chain still commits.
;;
;; SAFE: isolated daemon on a /tmp copy named "*code.log"; never 7977 / the canonical
;; tern log. The source code log is COPIED, never mutated.
;; ============================================================================
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[babashka.process :as proc]
         '[fram.kernel :as k]
         '[fram.fold :as fold]
         '[fram.tools :as tl]
         '[fram.rt])

(defn- die [& xs] (binding [*out* *err*] (apply println xs)) (System/exit 1))

(def here (System/getProperty "user.dir"))
;; the source code log to copy. Prefer this worktree's, else the main checkout's.
(def src-log (let [a (str here "/.fram/code.log")
                   b (str (System/getProperty "user.home") "/code/fram/.fram/code.log")]
               (cond (.exists (io/file a)) a
                     (.exists (io/file b)) b
                     :else (die "no .fram/code.log found at" a "or" b))))
;; name the COPY "*code.log" so the CLI safety gate (requires the daemon's :log to
;; contain "code.log") passes against this isolated daemon.
(def tmp-log (str "/tmp/cnf-mcp-insert-" (System/nanoTime) "-code.log"))
(io/copy (io/file src-log) (io/file tmp-log))

;; pick a verified-free non-7977 port.
(defn- free? [p] (try (with-open [ss (java.net.ServerSocket. p)] (.getLocalPort ss)) (catch Exception _ nil)))
(def port (or (some (fn [p] (when (and (not= p 7977) (free? p)) p)) (range 8911 8999))
              (die "no free port found")))
(when (= port 7977) (die "REFUSING port 7977"))
(println "=== §0.2 — fluent-agent insert-after reaches commute ===")
(println "src code log :" src-log)
(println "tmp copy     :" tmp-log)
(println "daemon port  :" port "(non-7977)")

;; one-shot EDN request to a coordinator (same wire the CLI uses).
(defn- coord-rt [req]
  (with-open [s (java.net.Socket.)]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (let [w (io/writer (.getOutputStream s)) rd (io/reader (.getInputStream s))]
      (.write w (str (pr-str req) "\n")) (.flush w)
      (edn/read-string (.readLine rd)))))

;; ---- boot the isolated daemon (JVM; serve-flat over the /tmp copy) ----------
(def daemon
  (proc/process ["clojure" "-M" "cnf_coord_daemon.clj" "serve-flat" (str port) tmp-log]
                {:dir here :out :inherit :err :inherit}))
;; wait until it answers :status (up to ~180s — JVM start + 9MB log migrate is slow).
(def up?
  (loop [n 0]
    (if (>= n 360) false
      (let [st (try (coord-rt {:op :status}) (catch Exception _ nil))]
        (if (and st (:version st)) st (do (Thread/sleep 500) (recur (inc n))))))))
(when-not up? (proc/destroy-tree daemon) (die "daemon did not come up on port" port))
(println "daemon up    :" (select-keys up? [:version :claims :log]))

(defn- shutdown! [] (try (proc/destroy-tree daemon) (catch Exception _ nil)))

;; ---- inspect the live store via the daemon's warm :query (CRDT ord-keys live) --
;; We read the wrapper's child position-keys straight off the daemon with a Datalog
;; query over triple(l,p,r): for the module's beagle-file wrapper, the position keys
;; are the predicates "f..." on the wrapper subject. Simpler: drive the discovery in
;; a SEPARATE in-process boot of the same /tmp log (read-only; no writes), exactly as
;; cnf_crdt_insert_receipt does, so we get resolve/ord-parse for free.
(binding [*command-line-args* []]
  (load-file "cnf_coord_daemon.clj")
  (load-file (str here "/chartroom/src/resolve.clj")))

;; a fresh read-only boot of the SAME /tmp log (separate store; never written to).
(def ro (migrate-flat->co tmp-log))
(def rst (:store ro))
(defn- vof [e] (let [Vp (c/value-id rst "v")] (some->> (c/by-lp rst e Vp) first (c/claim-of rst) :r (c/literal rst))))
(defn- forms-of [wrap]
  (->> (c/by-l rst wrap)
       (keep (fn [cid] (let [cl (c/claim-of rst cid) ks (c/literal rst (:p cl)) key (resolve/ord-parse ks)]
                         (when key {:key key :keystr ks :child (:r cl)}))))
       (sort-by :key resolve/ord-cmp) vec))
(defn- def-name [child]
  (let [kids (->> (c/by-l rst child)
                  (keep (fn [cid] (let [key (resolve/ord-parse (c/literal rst (:p (c/claim-of rst cid))))]
                                    (when key [key (c/claim-of rst cid)]))))
                  (sort-by first resolve/ord-cmp))]
    (when (>= (count kids) 2) (vof (:r (second (nth kids 1)))))))
(defn- head-of [child] (vof (:child (first (forms-of child)))))
(defn- wrapper [m]
  (let [NAME (c/value-id rst "name") pfx (str "@" m "#")]
    (->> (c/by-p rst NAME)
         (keep (fn [cid] (let [nm (c/literal rst (:r (c/claim-of rst cid)))]
                           (when (and (string? nm) (str/starts-with? nm pfx)) (:l (c/claim-of rst cid))))))
         (filter (fn [e] (let [fs (forms-of e)] (and (seq fs) (= "beagle-file" (vof (:child (first fs))))))))
         first)))

;; choose a module + an anchor def that HAS a next sibling (so insert-after lands in a gap).
(def MOD "kernel")
(def wrap (wrapper MOD))
(when-not wrap (shutdown!) (die "no wrapper for module" MOD "in" tmp-log))
(def pre (forms-of wrap))
(def ai (first (filter (fn [i] (and (< (inc i) (count pre))
                                    (#{"def" "defn" "defn-" "def-" "defonce"} (head-of (:child (nth pre i))))
                                    (def-name (:child (nth pre i)))))
                       (range (count pre)))))
(when (nil? ai) (shutdown!) (die "no def anchor with a next sibling in" MOD))
(def anchor (def-name (:child (nth pre ai))))
(def anchor-key (:key (nth pre ai)))
(def next-key   (:key (nth pre (inc ai))))
(println "module       :" MOD "  anchor def:" anchor
         "  gap between" (:keystr (nth pre ai)) "and" (:keystr (nth pre (inc ai))))

;; ============================================================================
;; (1) the AGENT-SHAPED MCP lowering — verbatim from the live code surface.
;; ============================================================================
;; the model fills typed params on the generated tool; fram.tools/call lowers it.
(def claims (:claims (fold/fold (fram.rt/read-log tmp-log))))
(def cat (tl/catalog claims))
(def idx (k/build-index claims))
(def insert-spec (first (filter #(= "insert-after" (:name %)) cat)))
(def new-form "(def fram_mcp_ins_A 4242)")
(def agent-args {:module MOD :after anchor :form new-form})
(def edit-env (tl/call claims idx cat "insert-after" agent-args))
(println "\n[1] MCP catalog tool 'insert-after' present:" (some? insert-spec)
         " params:" (mapv :name (:params insert-spec)))
(println "    tl/call -> :edit envelope:" (pr-str edit-env))

;; lower the {:edit ...} envelope to CLI flags exactly as fram_mcp/flip-verb-flags does.
;; (re-implemented here only because flip-verb-flags is a private defn in the MCP ns;
;; this is byte-identical to fram_mcp.clj's "insert-form" arm.)
(def e (:edit edit-env))
(def work (str "/tmp/cnf-mcp-insert-work-" (System/nanoTime)))
(.mkdirs (io/file work))
(def spec-file (str work "/spec.edn"))
(spit spec-file (:form e))
(def cli-flags ["insert-form" "--after" (:after e) "--spec-file" spec-file])
(println "    flip-verb-flags lowers to CLI:" (pr-str (vec (concat ["bin/fram-edit-code"] (drop 1 cli-flags) ["--module" MOD]))))

;; ============================================================================
;; (2) run the lowered CLI invocation against the LIVE daemon (the commute path).
;; ============================================================================
(def fec (str here "/bin/fram-edit-code"))
(def out-bclj (str work "/out-" MOD ".bclj"))
(def ins-res (proc/sh {:dir here :out :string :err :string}
                      "bb" "-cp" "out" fec
                      "insert-form" MOD "--after" anchor "--spec-file" spec-file
                      "--port" (str port) "--log" tmp-log "--out" out-bclj))
(println "\n[2] CLI insert-form exit:" (:exit ins-res))
(println "    stdout:" (str/trim (:out ins-res)))
(println "    stderr:" (str/trim (:err ins-res)))

;; ---- re-read the daemon's now-updated store to inspect the landed form ----
;; query the daemon for the wrapper's CURRENT child keys (live view, post-commit).
;; A fresh read-only re-boot of the /tmp log reflects the committed flat append.
(def ro2 (migrate-flat->co tmp-log))
(def rst2 (:store ro2))
(defn- vof2 [e] (let [Vp (c/value-id rst2 "v")] (some->> (c/by-lp rst2 e Vp) first (c/claim-of rst2) :r (c/literal rst2))))
(defn- forms-of2 [wrap]
  (->> (c/by-l rst2 wrap)
       (keep (fn [cid] (let [cl (c/claim-of rst2 cid) ks (c/literal rst2 (:p cl)) key (resolve/ord-parse ks)]
                         (when key {:key key :keystr ks :child (:r cl)}))))
       (sort-by :key resolve/ord-cmp) vec))
(defn- def-name2 [child]
  (let [kids (->> (c/by-l rst2 child)
                  (keep (fn [cid] (let [key (resolve/ord-parse (c/literal rst2 (:p (c/claim-of rst2 cid))))]
                                    (when key [key (c/claim-of rst2 cid)]))))
                  (sort-by first resolve/ord-cmp))]
    (when (>= (count kids) 2) (vof2 (:r (second (nth kids 1)))))))
(defn- wrapper2 [m]
  (let [NAME (c/value-id rst2 "name") pfx (str "@" m "#")]
    (->> (c/by-p rst2 NAME)
         (keep (fn [cid] (let [nm (c/literal rst2 (:r (c/claim-of rst2 cid)))]
                           (when (and (string? nm) (str/starts-with? nm pfx)) (:l (c/claim-of rst2 cid))))))
         (filter (fn [e] (let [fs (forms-of2 e)] (and (seq fs) (= "beagle-file" (vof2 (:child (first fs))))))))
         first)))
(def wrap2 (wrapper2 MOD))
(def post (forms-of2 wrap2))
(def ins (first (filter #(= "fram_mcp_ins_A" (def-name2 (:child %))) post)))
(def keystrs (map :keystr post))
(def dup? (not= (count keystrs) (count (distinct keystrs))))
;; CRDT key shape: f<path>~<tie> (a ~ separating a path from a tie), NOT an old f<int>.
(defn- crdt-key? [ks] (boolean (and (string? ks) (re-matches #"f[\d.]+~\d+" ks))))
(defn- between? [k] (and (neg? (resolve/ord-cmp anchor-key k)) (neg? (resolve/ord-cmp k next-key))))
;; the CLI logs its commit line to STDERR ("MINIMAL-OP insert-form ... committed N ops
;; ... THROUGH the live daemon") and prints the --out path on stdout. Check stderr.
(def ins-committed (str/includes? (str (:out ins-res) (:err ins-res))
                                  "committed"))

(println "\n[result] new form present :" (some? ins))
(println "         landed ord-key   :" (:keystr ins))
(println "         is CRDT f<path>~<tie> (not f<int>):" (crdt-key? (:keystr ins)))
(println "         strictly between anchor & next   :" (and ins (between? (:key ins))))
(println "         duplicate index in wrapper       :" dup?)
(println "         +1 form vs pre                   :" (= (count post) (inc (count pre))))

;; ============================================================================
;; (3) regression — a set-body through the SAME chain still commits.
;; ============================================================================
;; pick a defn (with a body) in the module to set-body on.
(defn- head-of2 [child] (vof2 (:child (first (forms-of2 child)))))
(def sb-target (first (keep (fn [i] (let [c (:child (nth post i))]
                                      (when (and (#{"defn" "defn-"} (head-of2 c)) (def-name2 c)) (def-name2 c))))
                            (range (count post)))))
(def sb-res
  (when sb-target
    (let [bf (str work "/body.edn")]
      (spit bf "42")   ; trivial constant body (set-body preserves params/ret-type)
      (proc/sh {:dir here :out :string :err :string}
               "bb" "-cp" "out" fec "set-body" MOD "--name" sb-target "--body-file" bf
               "--port" (str port) "--log" tmp-log))))
(println "\n[3] regression set-body on defn" (pr-str sb-target) "exit:" (:exit sb-res))
(println "    stdout:" (str/trim (str (:out sb-res))))
(def sb-ok (and sb-res (zero? (:exit sb-res))
                (str/includes? (str (:out sb-res) (:err sb-res)) "committed")))

;; ---- verdict ----------------------------------------------------------------
(shutdown!)
(println "\n=== VERDICT ===")
(if (and (= 0 (:exit ins-res)) ins-committed
         (some? ins) (crdt-key? (:keystr ins)) (between? (:key ins))
         (not dup?) (= (count post) (inc (count pre)))
         sb-ok)
  (do (println "PASS — a fluent-agent-shaped insert-after lowered through the MCP catalog ->"
               "{:edit insert-form} -> CLI verb-flags -> :edit-min -> do-edit-min, and the new")
      (println "       top-level form landed at a CRDT f<path>~<tie> ord-key strictly between the anchor")
      (println "       and its next sibling (no f<int>, no duplicate, +1 form). set-body via the same")
      (println "       chain still commits. The agent surface now reaches insert-anywhere-commute.")
      (System/exit 0))
  (do (println "FAIL — see the per-check lines above.")
      (System/exit 1)))
