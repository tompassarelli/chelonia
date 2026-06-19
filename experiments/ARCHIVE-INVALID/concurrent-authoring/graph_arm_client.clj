;; graph_arm_client.clj — a thin, reliable socket client for the harness, using
;; the daemon's OWN (client port {:op ...}) fn (the same one cnf_flip_test uses
;; over real sockets) instead of a flaky bash /dev/tcp pipe. Subcommands:
;;
;;   version <port>                         -> prints the current :version int
;;   status  <port>                         -> prints the full :status map (1 line)
;;   assert  <port> <te> <p> <r> <base>     -> prints :ok <v> | :reject <why> <v>
;;   commit  <port> <te> <p> <r>            -> read version, assert :base v, RETRY
;;                                             on :conflict (<=5x); prints ok|reject
;;   race    <port> <te> <p> <K>            -> K concurrent asserts on ONE
;;                                             (te,p) at a single stale base; prints
;;                                             "wins=<n> conflicts=<n>"
;;   callers <port> <module> <name>         -> prints the :callers vector
;;
;; HONESTY DISCIPLINE (tier-2): every subcommand that depends on the daemon being
;; reachable FAILS HARD (non-zero exit + stderr) when the daemon is unreachable or
;; the version comes back nil. A 0/0 "race" or an empty version must NEVER be
;; reported as a verified result — it is a harness fault, surfaced as such, so the
;; caller aborts instead of printing misleading "serialization holds" commentary.
;;
;; load-file the daemon ONLY to get `client` (its -main is guarded; our argv head
;; is not "serve"/"serve-flat", so load-file defines fns and runs nothing).
(require '[clojure.edn :as edn])
(load-file "cnf_coord_daemon.clj")

(defn- die! [code & msg]
  (binding [*out* *err*] (apply println msg))
  (System/exit code))

;; read the daemon's :version; HARD-FAIL if unreachable / nil (never return nil).
(defn- vint! [port]
  (let [v (try (:version (client port {:op :version}))
               (catch Throwable t
                 (die! 7 "FATAL: daemon unreachable on port" port "-" (.getMessage t))))]
    (when (nil? v)
      (die! 7 "FATAL: daemon returned nil :version on port" port "(unreachable / not ready)"))
    v))

(let [[cmd ports & more] *command-line-args*
      port (Integer/parseInt ports)]
  (case cmd
    "version" (println (vint! port))
    "status"  (println (pr-str (try (client port {:op :status})
                                     (catch Throwable t
                                       (die! 7 "FATAL: daemon unreachable on port" port "-" (.getMessage t))))))
    "assert"  (let [[te p r base] more
                    res (client port {:op :assert :te te :p p :r r :base (Integer/parseInt base)})]
                (println (pr-str res)))
    "commit"  (let [[te p r] more]
                (loop [tries 0]
                  (let [v (vint! port)
                        res (client port {:op :assert :te te :p p :r r :base v})]
                    (cond
                      (:ok res)                 (println "ok" (:ok res))
                      (and (= :conflict (:reject res)) (< tries 5)) (recur (inc tries))
                      :else                     (println "reject" (pr-str res))))))
    "race"    (let [[te p ks] more
                    K (Integer/parseInt ks)
                    v0 (vint! port)                       ; HARD-FAIL if daemon not answering
                    results (mapv deref
                                  (mapv (fn [i]
                                          (future (client port {:op :assert :te te :p p
                                                                :r (str "r" i) :base v0})))
                                        (range K)))
                    wins (count (filter :ok results))
                    conf (count (filter #(= :conflict (:reject %)) results))]
                ;; the all-errored signature (no win, no conflict) means the daemon
                ;; was unreachable mid-race: that is NOT "serialization", it is a
                ;; harness fault — surface it as a non-zero exit, never a 0/0 "result".
                (when (and (zero? wins) (zero? conf))
                  (binding [*out* *err*]
                    (println "FATAL: race produced wins=0 conflicts=0 (all asserts errored — daemon unreachable mid-race)"))
                  (println (str "wins=" wins " conflicts=" conf))
                  (System/exit 8))
                (println (str "wins=" wins " conflicts=" conf)))
    "callers" (let [[m nm] more
                    res (client port {:op :callers :module m :name nm})]
                (println (pr-str res)))
    (do (binding [*out* *err*] (println "unknown cmd" cmd)) (System/exit 2))))
