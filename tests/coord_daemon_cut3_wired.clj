;; cut-3 wired-shim regression: the daemon now runs on the Beagle fram.coord-daemon
;; (cuts 1+2) via re-export aliases; this confirms the WIRED socket paths. Self-contained
;; (no code corpus). Heavier paths (edit-min, render) are covered by cnf_edit_min_correctness
;; + the daemon run-test. NEVER 7977. Run: bb -cp out tests/coord_daemon_cut3_wired.clj
(require '[clojure.java.io :as io])
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def results (atom []))
(defn chk [l ok] (swap! results conj [l ok]) (println (if ok "  [PASS]" "  [FAIL]") l))

;; the shim's bare names must be IDENTICAL objects to the Beagle module's (shared state)
(chk "co atom shared with fram.coord-daemon" (identical? @(resolve 'co) @(resolve 'fram.coord-daemon/co)))
(chk "do-assert bridges to cd/do-assert!" (identical? @(resolve 'do-assert) @(resolve 'fram.coord-daemon/do-assert!)))

(def port 8155)
(spit "/tmp/cut3-wired.log" "")
(boot! "/tmp/cut3-wired.log")
(register-pred! @co "owner" "single" "literal")
(def srv (future (serve port)))
(Thread/sleep 500)
(client port {:op :assert :te "@A" :p "owner" :r "alice" :base 0})
(client port {:op :assert :te "@B" :p "owner" :r "bob" :base 0})
(client port {:op :assert :te "@A" :p "owner" :r "carol" :base (:version (client port {:op :version}))}) ; supersede
(def q {:find "o" :rules [{:head {:rel "o" :args [{:var "t"} {:var "v"}]}
                           :body [{:rel "triple" :args [{:var "t"} "owner" {:var "v"}]}]}]})
(def qi (client port {:op :query :query q}))
(def qs (client port {:op :query :query q :scan true}))
(def wc (client port {:op :warm-check}))
(def stt (client port {:op :status}))
(future-cancel srv)
(chk "wired :query idx-run ≡ q/run (over socket)" (= (set (:ok qi)) (set (:ok qs))))
(chk "engines reported (index vs scan)" (and (= "index" (:engine qi)) (= "scan" (:engine qs))))
(chk "supersede live-view: @A owner=carol only" (= #{["@A" "carol"] ["@B" "bob"]} (set (:ok qi))))
(chk "wired :warm-check consistent (incremental==fresh)" (:consistent wc))
(chk "wired :status reports live claims" (pos? (:claims stt)))

(let [fails (remove second @results)]
  (println (format "\n=== cut3 wired: %d/%d PASS ===" (- (count @results) (count fails)) (count @results)))
  (System/exit (if (seq fails) 1 0)))
