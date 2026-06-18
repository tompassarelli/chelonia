#!/usr/bin/env bb
;; ============================================================================
;; callgraph — the SCOPE-CORRECT call-graph engine (Layer 2, on Fram).
;; ============================================================================
;; Derives the scope-correct call graph of a beagle source tree from its CNF
;; claim projection, with the transitive blast radius computed by Fram Datalog.
;; A call binds the defn in its OWN module (module-local lexical scope; else a
;; unique global; else ambiguous/external -> dropped), so same-named functions
;; across modules never collide — the failure mode of bare-symbol text matching.
;;
;; This is the CNF-projection call-graph core, used by chartroom.clj's gjoa BENCHMARK.
;; LIMITATION: the CNF projection (beagle-claims) does not emit require/:as info, so a
;; QUALIFIED cross-module call (a/f, fully-qualified m/f) cannot be resolved here and is
;; dropped (under-counting cross-module blast). The PRODUCTION call graph (beagle-callgraph
;; / cascade) does NOT use this — it derives the graph from resolve.clj's converged
;; `refers_to` (the `callgraph` mode), which resolves qualified cross-module calls correctly.
;; This module remains for the gjoa CNF benchmark (mostly unqualified) where its scope-
;; correct-vs-bare-symbol point still holds; reprojecting gjoa to the AST is a follow-up.
;; Requirable as a library; runnable as a CLI:
;;
;;   bb -cp <fram/out>:<chartroom/src> -m callgraph <claims-file>   ; -> JSON on stdout
(ns callgraph
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [fram.cnf :as c]
            [fram.datalog :as d]))

;; ---- parse the @file-delimited claim stream (from beagle-claims) -----------
;; NOTE: Racket ~s emits some escapes (\e, \a, ...) Clojure's EDN reader rejects.
;; Those only appear in LEAF literal objects (never the call-graph predicates
;; name/calls/child/form-kind), so we skip-and-count them — the call graph is
;; unaffected (the source-of-truth roundtrip needs EDN-safe leaves; this doesn't).
(defn parse-corpus [path]
  (let [skips (volatile! 0)
        blocks (loop [ls (str/split-lines (slurp path)), cur nil, out []]
                 (if (empty? ls)
                   (if cur (conj out cur) out)
                   (let [l (first ls)]
                     (cond
                       (str/starts-with? l "@file ")
                       (recur (rest ls) {:file (subs l 6) :triples []} (if cur (conj out cur) out))
                       (str/starts-with? l "[")
                       (let [t (try (edn/read-string l) (catch Exception _ (vswap! skips inc) nil))]
                         (recur (rest ls) (if t (update cur :triples conj t) cur) out))
                       :else (recur (rest ls) cur out)))))]
    (when (pos? @skips)
      (binding [*out* *err*] (println "  (skipped" @skips "EDN-unparseable leaf literals)")))
    blocks))

(defn index-by [pred triples]
  (reduce (fn [m [s p o]] (if (= p pred) (assoc m s o) m)) {} triples))

(defn module-of [file]
  (-> file (str/split #"/") last (str/replace #"\.[^.]+$" "")))

;; ---- per-file: attribute every call to its NEAREST enclosing defn ----------
;; (iterative DFS over `child` edges; mention = [caller-defn-key callname])
(defn derive-block [block]
  (let [ts   (:triples block)
        file (:file block)
        fk    (index-by "form-kind" ts)
        names (index-by "name" ts)
        calls (index-by "calls" ts)
        kids  (reduce (fn [m [s p o]] (if (= p "child") (update m s (fnil conj []) o) m)) {} ts)
        childset (reduce (fn [s [_ p o]] (if (= p "child") (conj s o) s)) #{} ts)
        roots (remove childset (keys fk))
        defns (vec (for [[s k] fk :when (= k "defn")]
                     {:key [file s] :name (names s) :file file :module (module-of file)}))
        mentions (volatile! [])]
    (loop [stack (mapv (fn [r] [r nil]) roots)]
      (when (seq stack)
        (let [[node cd] (peek stack)
              st  (pop stack)
              cd2 (if (= (fk node) "defn") node cd)]
          (when (and (= (fk node) "call") cd2 (calls node))
            (vswap! mentions conj [[file cd2] (calls node)]))
          (recur (into st (mapv (fn [k] [k cd2]) (get kids node [])))))))
    {:file file :defns defns :mentions @mentions}))

;; ---- global resolution: callname -> the defn it actually binds -------------
;; same-file local definition wins (module-local lexical scope); else a unique
;; global defn; else ambiguous/external -> dropped. THIS is the scope-correctness
;; the bare-symbol regex incumbent skips. Returns :by-name too (chartroom's
;; benchmark uses it; the CLI ignores it).
(defn build-graph [blocks]
  (let [derived  (mapv derive-block blocks)
        defns    (vec (mapcat :defns derived))
        by-name  (group-by :name defns)
        mentions (mapcat :mentions derived)
        resolve-call
        (fn [caller-key callname]
          (let [cands (get by-name callname)
                cfile (first caller-key)
                same  (filter #(= (first (:key %)) cfile) cands)]
            (cond
              (seq same)          (:key (first same))
              (= 1 (count cands)) (:key (first (vec cands)))
              :else               nil)))
        edges (->> mentions
                   (keep (fn [[ck nm]] (when-let [callee (resolve-call ck nm)]
                                         (when (not= ck callee) [ck callee]))))
                   distinct vec)]
    {:defns defns :by-name by-name :edges edges}))

;; ---- transitive blast radius via Fram Datalog (the persistent-store seam) ---
;; blast(D) = {x | x transitively calls D} = D's transitive callers (who breaks if D changes).
(defn blast-radius [edges]
  (let [ctx   (c/new-store)
        tx    (c/begin-tx! ctx "code")
        EDGE  (c/value! ctx "calls-defn")
        k->id (volatile! {})
        ent   (fn [k] (or (get @k->id k)
                          (let [e (c/entity! ctx)] (vswap! k->id assoc k e) e)))
        _     (doseq [[a b] edges] (c/claim! ctx (ent a) EDGE (ent b) tx))
        id->k (into {} (map (fn [[k v]] [v k]) @k->id))
        db    (d/run-rules ctx
                [(d/rule "reaches" [(d/v :x) (d/v :y)]
                         [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])])
                 (d/rule "reaches" [(d/v :x) (d/v :z)]
                         [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])
                          (d/lit "reaches" [(d/v :y) (d/v :z)])])])
        reaches (set (d/facts db "reaches"))
        blast (reduce (fn [m [xid yid]]
                        (update m (id->k yid) (fnil conj #{}) (id->k xid)))
                      {} reaches)]
    {:blast blast :reaches reaches}))

;; ---- CLI: claims-file -> JSON {defns, edges, blast} ------------------------
(defn -main [& args]
  (let [claims-path (first args)
        blocks (parse-corpus claims-path)
        {:keys [defns edges]} (build-graph blocks)
        {:keys [blast reaches]} (blast-radius edges)
        key->str (fn [k] (str (first k) "#" (second k)))
        defns-out (mapv (fn [dd] {:key (key->str (:key dd)) :file (:file dd)
                                  :module (:module dd) :name (:name dd)}) defns)
        edges-out (mapv (fn [[a b]] [(key->str a) (key->str b)]) edges)
        blast-out (into {} (map (fn [[k vs]] [(key->str k) (mapv key->str vs)]) blast))]
    (binding [*out* *err*]
      (println (format "callgraph: %d defns, %d scope-correct edges, %d transitive reaches-pairs (Fram Datalog closure)"
                       (count defns) (count edges) (count reaches))))
    (println (json/generate-string {:defns defns-out :edges edges-out :blast blast-out}))))

;; runnable directly (bb src/callgraph.clj <claims>) without -m, but NOT on require
(when (and (System/getProperty "babashka.file")
           (= (System/getProperty "babashka.file") *file*))
  (apply -main *command-line-args*))
