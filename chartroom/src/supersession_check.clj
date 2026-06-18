#!/usr/bin/env bb
;; Falsify "superseded (not deleted)": after a rename, show the OLD red claim
;; and its LIVE crimson successor COEXISTING on the same entity, old marked
;; not-live, and the live view excluding it. If this is real, supersession is a
;; claim graph; if not, "superseded" was a euphemism for overwrite.
(require '[clojure.edn :as edn] '[clojure.string :as str] '[fram.cnf :as c])

(def ctx (c/new-store))
(def tx  (c/begin-tx! ctx "author"))
(def SUP (c/value! ctx "supersedes"))
(c/set-supersedes-pred! ctx SUP)
(def local (atom {}))
(defn ent [lid] (or (@local lid) (let [e (c/entity! ctx)] (swap! local assoc lid e) e)))
(doseq [line (str/split-lines (slurp "/tmp/trap.edn")) :when (str/starts-with? line "[")]
  (let [[s p o] (edn/read-string line)]
    (c/claim! ctx (ent s) (c/value! ctx p) (if (integer? o) (ent o) (c/value! ctx o)) tx)))

(def Vp (c/value! ctx "v")) (def KIND (c/value! ctx "kind")) (def SYM (c/value! ctx "symbol"))
(def OLDv (c/value-id ctx "red")) (def NEWv (c/value! ctx "crimson"))
(defn sym? [e] (some #(= SYM (:r (c/claim-of ctx %))) (c/by-lp ctx e KIND)))

;; rename one symbol `red`, remembering the exact claim ids involved
(def evidence (atom nil))
(doseq [cid (vec (c/by-pr ctx Vp OLDv))]
  (let [e (:l (c/claim-of ctx cid))]
    (when (and (sym? e) (nil? @evidence))
      (let [ncid (c/claim! ctx e Vp NEWv tx)
            scid (c/claim! ctx ncid SUP cid tx)]
        (reset! evidence {:e e :old cid :new ncid :sup scid})))))

(let [{:keys [e old new sup]} @evidence]
  (println "entity (the symbol node):" e)
  (println)
  (println "OLD value-claim  cid=" old "  ->" (c/claim-of ctx old)
           "  value=" (pr-str (c/literal ctx (:r (c/claim-of ctx old))))
           "  LIVE?=" (c/live? ctx old))
  (println "NEW value-claim  cid=" new "  ->" (c/claim-of ctx new)
           "  value=" (pr-str (c/literal ctx (:r (c/claim-of ctx new))))
           "  LIVE?=" (c/live? ctx new))
  (println "SUPERSEDES claim cid=" sup "  ->" (c/claim-of ctx sup)
           "  (l=new-claim, p=supersedes, r=old-claim)")
  (println)
  (println "same entity for old & new?   " (= (:l (c/claim-of ctx old)) (:l (c/claim-of ctx new)) e))
  (println "old still retrievable (history preserved)? " (some? (c/claim-of ctx old)))
  (println "live view of entity's v-claims (by-l is live-only):"
           (mapv (fn [cid] (pr-str (c/literal ctx (:r (c/claim-of ctx cid)))))
                 (filter (fn [cid] (= Vp (:p (c/claim-of ctx cid)))) (c/by-l ctx e))))
  (println "=> old red claim EXISTS, marked not-live; new crimson claim is live; same node. Supersession is real:"
           (and (some? (c/claim-of ctx old))
                (not (c/live? ctx old))
                (c/live? ctx new)
                (= e (:l (c/claim-of ctx old)) (:l (c/claim-of ctx new))))))
