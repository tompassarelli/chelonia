;; ============================================================================
;; cnf_coord_11b.clj — #11b COUPLED-COHERENCE TRADE RECEIPT.  *** GIT WINS. ***
;; ----------------------------------------------------------------------------
;; The NEGATIVE half of the #11 scoreboard — the regime where Fram's view-relative
;; decoupling (the same decoupling that wins #11 R2 landing-latency + R3 failure
;; isolation) becomes Fram's LIABILITY and git's pre-land gate wins outright.
;;
;; THE COUPLED SCENARIO (each edit alone is coherent; only the COMBINATION breaks):
;;   base:   bar is defined; foo does NOT yet reference bar.
;;   Edit A: foo starts referencing bar.        (alone: coherent — bar exists)
;;   Edit B: bar is removed.                     (alone: coherent — nothing refs bar)
;;   A+B:    foo references a removed bar.        (COMBINED: reference-incoherent)
;; A and B touch DISJOINT sites — different (te,p) in Fram, different text lines in
;; git — so neither the OCC surface nor the 3-way text merge sees a conflict. Only a
;; COHERENCE VALIDATION of the combined state catches it.
;;
;; THE SINGLE SHARED VALIDATOR V := "every reference resolves to a live definition."
;; The two arms run the SAME V; git's green-main guarantee comes from TWO things together
;; (NOT one tunable "when" — naming both so the gap isn't made to sound trivially closable):
;;   (a) TIMING — git runs V pre-land (coupled to landing); Fram has no commit-time gate.
;;   (b) TOPOLOGY — git STAGES each edit on its own branch and validates a discardable MERGE
;;       CANDIDATE on an integ branch; on failure main never advances, so the bad combination
;;       NEVER touches main. Fram writes both edits DIRECTLY to the one shared store/main.
;;   GIT  (a)+(b): the speculative queue validates the MERGED CANDIDATE *before* it lands;
;;        the combined state fails V -> REJECT -> the discardable candidate is thrown away,
;;        main stays green.
;;   FRAM neither: NO commit-time coherence gate (confirmed: commit! gates only on
;;        base_version OCC + depends_on/part_of acyclicity, cnf_coord.clj:118-153) AND no
;;        stage-and-discard — both disjoint edits commit straight to the shared store, so
;;        main is view-locally INCOHERENT until V runs at publish/validate/render time.
;;
;; WHAT IS REAL HERE: both arms run live. Fram = the real commit!/retract! OCC wire
;; (the same path every commit takes). Git = a real repo, real branches, real 3-way
;; merge, real name-resolution gate. The coupled structure {bar; foo->bar} is modeled
;; identically in graph (Fram) and text (git). The full-schema-AST version of the same
;; Fram incoherence is the §7 live receipt (docs/VIEWS_AND_BRANCHES.md §7, 7622022):
;; a raw :retract of a referenced binding commits and leaves main view-locally
;; incoherent BY DESIGN (intended under §2/§4/§5, not a bug). #11b puts that behavior
;; beside git's gate in one coupled scenario with the two-row verdict.
;;
;; NO fix is proposed. gate-v2 is NOT reopened. identity-refs are NOT implemented.
;; Fram is NOT made to win. This receipt's whole job is to show git winning a regime.
;;   bb cnf_coord_11b.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.java.io :as io] '[babashka.process :as proc])
(load-file "cnf_coord.clj")

;; ============================================================================
;; FRAM ARM — real commit!/retract! OCC wire on an ISOLATED temp coordinator.
;; (NEVER the canonical tern log — a fresh new-coord on a /tmp path.)
;; ============================================================================
(defn fresh-coord []
  (let [log (str "/tmp/cnf-11b-" (System/nanoTime) ".log")
        co (new-coord log)]
    (register-pred! co "depends_on" "multi" "ref")     ; ref edge, acyclicity-gated
    (register-pred! co "title" "single" "literal")      ; a thread is "defined" iff it has a title
    [co log]))

;; the SHARED validator V over the graph: every live depends_on edge must point at a
;; DEFINED thread (one that still has a live title). A dangling edge = incoherent.
(defn fram-V [co]
  (let [st (store co)
        dep (c/value-id st "depends_on") title (c/value-id st "title")
        foo (s/resolve-name st "foo")
        edges (when (and foo dep) (map #(:r (c/claim-of st %)) (live-cids-lp co foo dep)))
        defined? (fn [node] (boolean (seq (live-cids-lp co node title))))
        dangling (vec (remove defined? edges))]
    {:coherent? (empty? dangling) :foo-edges (count edges) :dangling-targets (count dangling)}))

(defn fram-setup! [co]
  (commit! co "init" "foo" "title" :assert "Foo" 0)        ; foo defined
  (commit! co "init" "bar" "title" :assert "Bar" 0))       ; bar defined

(defn fram-edit-A! [co] (commit! co "agentA" "foo" "depends_on" :link "bar" 0))  ; foo -> bar
(defn fram-edit-B! [co]                                                          ; remove bar (drop its title)
  (let [bar (s/resolve-name (store co) "bar")
        base (base-version co bar (c/value-id (store co) "title"))]
    (retract! co "agentB" "bar" "title" "Bar" base)))

;; --- A alone (coherent), B alone (coherent), then A+B combined ---
(def A-only (let [[co _] (fresh-coord)] (fram-setup! co) (fram-edit-A! co) (fram-V co)))
(def B-only (let [[co _] (fresh-coord)] (fram-setup! co) (fram-edit-B! co) (fram-V co)))

(let [[co _] (fresh-coord)]
  (fram-setup! co)
  (def rA (fram-edit-A! co))                ; Edit A commits
  (def rB (fram-edit-B! co))               ; Edit B commits, disjoint (te=bar,p=title) vs (te=foo,p=depends_on)
  (def fram-occ (count (filter #(= :conflict (:reject %)) [rA rB])))
  (def fram-after (fram-V co)))

;; ============================================================================
;; GIT ARM — real repo, real branches, real 3-way merge, real name-resolution gate.
;; Same coupled structure as text. base: bar defined, foo does NOT reference bar.
;; ============================================================================
(def gitenv {"GIT_AUTHOR_NAME" "x" "GIT_AUTHOR_EMAIL" "x@x" "GIT_COMMITTER_NAME" "x" "GIT_COMMITTER_EMAIL" "x@x"})
(defn git [dir & args] (apply proc/sh {:dir dir :extra-env gitenv :out :string :err :string} "git" args))

;; bar and foo sit in separate sections with stable context lines between them, so
;; A's edit (foo) and B's edit (delete bar) land in non-adjacent hunks that 3-way
;; auto-merge cleanly — the merged file is the TRUE combined state the gate validates.
(def base-src ";; module m\n(def bar 1)\n;; ----\n;; ----\n;; ----\n(def foo 0)\n;; eof\n")
(def editA #(str/replace % "(def foo 0)" "(def foo (+ bar 1))"))   ; A: foo now references bar
(def editB #(str/replace % "(def bar 1)\n" ""))                     ; B: remove bar

;; the SHARED validator V over text: every base-def name referenced inside another
;; def's body must still have a (def ...) — same predicate as fram-V, on text.
(defn git-V [src]
  (let [defined (set (map second (re-seq #"\(def (\w+)" src)))
        base-defs #{"foo" "bar"}
        refs (set (for [[_ nm body] (re-seq #"(?s)\(def (\w+) (.*?)\)" src)
                        tok (re-seq #"\w+" body)
                        :when (and (base-defs tok) (not= tok nm))] tok))
        dangling (vec (remove defined refs))]
    {:coherent? (empty? dangling) :dangling dangling}))

(defn git-arm []
  (let [dir (str (System/getProperty "java.io.tmpdir") "/coord11b-" (System/nanoTime))]
    (.mkdirs (io/file dir))
    (git dir "init" "-q") (git dir "config" "commit.gpgsign" "false")
    (spit (str dir "/m.clj") base-src)
    (git dir "add" "-A") (git dir "commit" "-qm" "base")
    (let [base (str/trim (:out (git dir "rev-parse" "HEAD")))]
      ;; agent A branch
      (git dir "checkout" "-q" "-b" "agentA" base)
      (spit (str dir "/m.clj") (editA (slurp (str dir "/m.clj"))))
      (git dir "commit" "-aqm" "A: foo refs bar")
      ;; agent B branch
      (git dir "checkout" "-q" "-b" "agentB" base)
      (spit (str dir "/m.clj") (editB (slurp (str dir "/m.clj"))))
      (git dir "commit" "-aqm" "B: remove bar")
      ;; speculative integration: merge both onto base, in a candidate branch
      (git dir "checkout" "-q" "-b" "integ" base)
      (let [mA (git dir "merge" "-q" "--no-edit" "agentA")
            mB (git dir "merge" "-q" "--no-edit" "agentB")
            conflicts (count (filter #(not (zero? (:exit %))) [mA mB]))
            merged (slurp (str dir "/m.clj"))
            ;; the gate validates EACH candidate the queue would consider:
            vA (git-V (editA base-src))
            vB (git-V (editB base-src))
            vMerged (git-V merged)]
        (proc/sh "rm" "-rf" dir)
        {:merge-conflicts conflicts :A-alone vA :B-alone vB :merged vMerged
         ;; the gate LANDS a candidate only if it passes V; the combined candidate fails -> reject.
         :landed-combined? (:coherent? vMerged)}))))

(def git-res (git-arm))

;; ============================================================================
;; TWO-ROW VERDICT
;; ============================================================================
(println "=== #11b — COUPLED-COHERENCE TRADE RECEIPT (GIT WINS) ===\n")
(println "Coupled scenario: base{bar defined, foo !ref bar}; A{foo refs bar}; B{remove bar}.")
(println "Each edit ALONE is coherent; only the A+B COMBINATION references a removed bar.")
(println "Shared validator V := \"every reference resolves to a live definition.\"\n")

(println "ROW 1 — FRAM (view-relative, no commit-time coherence gate):")
(println (format "  Edit A (foo depends_on bar) -> %s" (if (:ok rA) (str "committed v" (:ok rA)) rA)))
(println (format "  Edit B (retract bar's title) -> %s" (if (:ok rB) (str "committed v" (:ok rB)) rB)))
(println (format "  OCC conflicts: %d   (disjoint (te,p): A=(foo,depends_on)  B=(bar,title))" fram-occ))
(println (format "  each edit ALONE leaves main coherent:  A-alone=%s  B-alone=%s"
                 (if (:coherent? A-only) "COHERENT" "INCOHERENT") (if (:coherent? B-only) "COHERENT" "INCOHERENT")))
(println (format "  BOTH landed; V on resulting main view: %s  (foo->bar edges=%d, dangling=%d)"
                 (if (:coherent? fram-after) "COHERENT" "INCOHERENT") (:foo-edges fram-after) (:dangling-targets fram-after)))
(println "  >>> verdict: cheap landing, coherence DEFERRED to publish — main view-locally incoherent in between.\n")

(println "ROW 2 — GIT (speculative-batching, gate validates merged candidate BEFORE landing):")
(println (format "  3-way merge of agentA + agentB onto base: %d conflicts (disjoint lines auto-merge)" (:merge-conflicts git-res)))
(println (format "  gate V pre-land:  A-alone=%s (would land)  B-alone=%s (would land)  A+B merged=%s"
                 (if (:coherent? (:A-alone git-res)) "COHERENT" "INCOHERENT")
                 (if (:coherent? (:B-alone git-res)) "COHERENT" "INCOHERENT")
                 (if (:coherent? (:merged git-res)) "COHERENT" (str "INCOHERENT (dangling " (:dangling (:merged git-res)) ")"))))
(println (format "  gate %s the combined candidate -> main stays at base."
                 (if (:landed-combined? git-res) "LANDS" "REJECTS")))
(println "  >>> verdict: green-main guarantee WINS.\n")

(def git-wins (and (:coherent? A-only) (:coherent? B-only)             ; each edit alone coherent (both arms)
                   (not (:coherent? fram-after))                        ; Fram lands the incoherent combination
                   (zero? fram-occ)                                     ; ...with no OCC conflict (disjoint)
                   (:coherent? (:A-alone git-res)) (:coherent? (:B-alone git-res))
                   (not (:coherent? (:merged git-res)))                 ; git's gate sees the incoherent merge
                   (not (:landed-combined? git-res))))                  ; ...and rejects it

(println "=== #11b VERDICT ===")
(println "GIT WINS this regime. Same validator V; git's green-main comes from TWO things together (not one")
(println "tunable 'when'): (a) TIMING — git runs V pre-land; and (b) TOPOLOGY — git stages each edit on a")
(println "branch and validates a DISCARDABLE merge candidate, so the combined bad state never touches main.")
(println "Fram has neither: no commit-time coherence gate AND no stage-and-discard — both disjoint edits commit")
(println "straight to the shared store, so main is view-locally incoherent until V runs at publish.")
(println "This is the COST of view-relative coherence — the same decoupling that wins #11 R2 (landing latency)")
(println "and R3 (failure isolation). The scoreboard now has both halves: Fram wins R2/R3; git wins #11b.\n")

(println "WHAT THIS PROVES: in a coupled reference-breaking workload where each edit is individually valid,")
(println "  git's speculative queue keeps main continuously green by validating the merged candidate before")
(println "  landing; Fram's current model lands both disjoint edits and defers coherence, so main is")
(println "  view-locally incoherent until publish/validate. Git wins the green-main guarantee, measured here.")
(println "WHAT THIS DOES NOT PROVE: it does NOT show Fram CAN'T gate coherence (a view-publish gate is")
(println "  future design, not built/measured here); it does NOT reopen gate-v2 or identity refs; it does")
(println "  NOT generalize the magnitude to all workloads. It is one concrete coupled case, both arms live.")
(System/exit (if git-wins 0 1))
