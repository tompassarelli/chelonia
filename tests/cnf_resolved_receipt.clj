;; ============================================================================
;; cnf_resolved_receipt.clj — interface investigation #3: resolution multiplicity
;;   bb -cp out cnf_resolved_receipt.clj
;;
;; P-of / select-main-1 return (first live) — a SELECTION, not a uniqueness proof (#19):
;; a contested field reads as a silently-arbitrary pick with no signal. The new daemon
;; :resolved op surfaces {:value :members :ambiguous?} so the agent gets a CHECKABLE
;; answer. Rep-stable (pure read over the (l,p) live group, no fN).
;;
;; SAFE: fresh in-process coordinator on a /tmp scratch log; no socket, no port 7977,
;; never the canonical tern log.
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s] '[clojure.string :as str])
(load-file "cnf_coord_daemon.clj")

(def log (str "/tmp/cnf-resolved-" (System/nanoTime) ".log"))
(spit log "")
(boot! log)
;; "owner"/"title" are in the kernel single-valued list (ck/single?), so a 2nd write
;; supersedes -> only 1 live. To exhibit a CONTESTED group we use a genuinely-multi
;; predicate (the view-relative case #19 warns about: a group MAY hold >1 live).
(register-pred! @co "title" "single" "literal")
(register-pred! @co "fram_contested" "multi" "literal")

(commit! @co "a" "@T" "title" :assert "Target" 0)            ; uncontested: 1 live
(commit! @co "a" "@T" "fram_contested" :assert "alice" 0)    ; contested: 2 distinct live values
(commit! @co "b" "@T" "fram_contested" :assert "bob"   0)

(def r-title (handle {:op :resolved :te "@T" :p "title"}))
(def r-cont  (handle {:op :resolved :te "@T" :p "fram_contested"}))

(println "=== interface #3 — resolution multiplicity signal ===")
(println "title          (1 live):" (select-keys r-title [:value :members :ambiguous?]))
(println "fram_contested (2 live):" (select-keys r-cont [:value :members :ambiguous? :values]))

(println "\n=== VERDICT ===")
(if (and (= 1 (:members r-title)) (false? (:ambiguous? r-title))
         (= 2 (:members r-cont)) (true? (:ambiguous? r-cont)))
  (println "PASS — :resolved surfaces multiplicity: uncontested ambiguous?=false (1 live), contested"
           "ambiguous?=true (2 live:" (:values r-cont) "). The agent gets a CHECKABLE answer instead of a"
           "silent first-live pick — converting the #19 hidden selection into an explicit signal. (A genuinely"
           "single field that ever DIVERGES to >1 live is caught the same way — the op counts live members.)")
  (println "FAIL — see above."))
