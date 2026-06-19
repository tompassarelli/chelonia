#!/usr/bin/env bash
# ============================================================================
# ground_truth.sh — compute the verified-correct answer for EVERY task in
# tasks.edn, ONCE, the same for both arms. Writes ground_truth.json.
# ============================================================================
# The oracle is the verified-correct resolver (chartroom/src/resolve.clj) +, for
# the daemon-served live answers (a/b), the daemon :callers reverse lookup. This
# CLI is the ORACLE — it is NEVER a graph-arm retrieval (owned-resolution
# guarantee ii). It is run ONCE; both arms are scored against its output.
#
# Inputs (env):
#   RC_EDN_DIR   : dir of per-module emit-edn dumps (<module>.edn)
#   RC_PORT      : the live daemon port (for a/b live :callers answers)
#   RC_OUT       : where to write ground_truth.json
#   BEAGLE / BEAGLE_HOME : ~/code/beagle
# Usage: RC_EDN_DIR=.. RC_PORT=.. RC_OUT=.. bash ground_truth.sh
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
EDN_DIR="${RC_EDN_DIR:?set RC_EDN_DIR}"
OUT="${RC_OUT:?set RC_OUT}"
PORT="${RC_PORT:-}"
SRC="$REPO/src/fram"
LOWERED="$REPO/out/fram"

cd "$REPO"

# ---- the callgraph oracle (scope-correct edges + transitive closure) --------
CG="$(mktemp /tmp/rc-cg.XXXXXX.json)"
trap 'rm -f "$CG"' EXIT
bb -cp out chartroom/src/resolve.clj callgraph "$EDN_DIR"/*.edn > "$CG" 2>/dev/null

# ---- derive every ground-truth answer from the callgraph + source -----------
# (a) direct callers of thread-ids-i ; (c) transitive closure ; module sets.
# (b)/(d) spelling + type-site counts come straight off the source (text GT).
bb << BBEOF
(require '[cheshire.core :as j] '[clojure.string :as str])

(def cg (j/parse-string (slurp "$CG") true))
(def defns (:defns cg))
(def edges (:edges cg))
(def kmeta (into {} (map (fn [d] [(:key d) {:module (str/replace (or (:module d) "") #"^fram\." "")
                                            :name (:name d)
                                            :mn (str (str/replace (or (:module d) "") #"^fram\." "") "/" (:name d))}]) defns)))

(def tid (some (fn [d] (when (= "thread-ids-i" (:name d)) d)) defns))
(def tkey (:key tid))

;; (a) DIRECT callers = edges INTO thread-ids-i (scope-correct, via refers_to+ultimate)
(def direct (->> edges (filter (fn [[a b]] (= b tkey))) (map (fn [[a b]] (get kmeta a)))))
(def direct-modules (vec (sort (distinct (map :module direct)))))

;; (c) TRANSITIVE blast closure (= {x | x transitively reaches thread-ids-i})
(def callers-of (reduce (fn [m [a b]] (update m b (fnil conj #{}) a)) {} edges))
(def closure (loop [frontier #{tkey} seen #{}]
               (if (empty? frontier) seen
                 (let [nw (apply clojure.set/union (map #(get callers-of % #{}) frontier))
                       seen' (clojure.set/union seen frontier)]
                   (recur (clojure.set/difference nw seen') seen')))))
(def closure-mn (vec (sort (map #(:mn (get kmeta %)) (disj closure tkey)))))

(defn sh [& args] (str/trim (:out (apply clojure.java.shell/sh args))))
(require '[clojure.java.shell])

;; text-corpus slurp helpers (byte-faithful to the methodology's `grep -hoE` counts)
(defn slurp-tree [path ext]
  (apply str (map slurp (filter #(str/ends-with? (str %) ext)
                                (map str (.listFiles (java.io.File. path)))))))
(defn count-matches [re path ext] (count (re-seq re (slurp-tree path ext))))
(def src "$SRC")
(def src-txt (slurp-tree src ".bclj"))

;; (b) the three live spellings of Claim — GREEDY ALTERNATION, same semantics as
;; the methodology's `grep -hoE '(k/)?(->)?Claim\b'`: each token counted once under
;; its FULLEST spelling, then bucketed. (A negative-lookbehind split miscounts.)
(def spell-freq    (frequencies (re-seq #"(?:k/)?(?:->)?Claim\b" src-txt)))
(def spell-claim   (get spell-freq "Claim" 0))
(def spell-kclaim  (get spell-freq "k/Claim" 0))
(def spell-kctor   (get spell-freq "k/->Claim" 0))
(def spell-total   (reduce + (vals spell-freq)))

;; (d) (Vec Claim)/(Vec k/Claim) annotation SITES in source ; erased to 0 in lowered
(def vec-claim     (count-matches #"\(Vec Claim\)" src ".bclj"))
(def vec-kclaim    (count-matches #"\(Vec k/Claim\)" src ".bclj"))
(def vec-total     (+ vec-claim vec-kclaim))
(def lowered "$LOWERED")
(def lowered-vec-claim (count-matches #"Vec Claim" lowered ".clj"))      ; should be 0
;; ^Claim survives lowering as SINGLE-param hints. Report SITES (lines) and TOKENS:
;; kernel.clj:38 carries TWO (^Claim a ^Claim b) -> 4 sites / 5 tokens.
(def lowered-caret-tokens (count-matches #"\^Claim" lowered ".clj"))
(def lowered-caret-sites  (count (distinct (->> (str/split-lines (slurp-tree lowered ".clj"))
                                                (filter #(str/includes? % "^Claim"))))))

(def result
  {:corpus "$SRC"
   :modules 11
   :tasks
   {:cal  {:module "datalog" :name "var?" :site "datalog.bclj:44"
           :answer "(and (map? t) (contains? t :var))"
           :via "direct read of datalog.bclj:44"}
    :a    {:target {:module "kernel" :name "thread-ids-i"}
           :direct-callers (vec (sort (map :mn direct)))
           :module-set direct-modules
           :n-direct (count direct)
           :via "callgraph :edges with callee=kernel#thread-ids-i (scope-correct, refers_to+ultimate)"}
    :b    {:reference "k/->Claim @ import.bclj (the defrecord Claim constructor)"
           :ultimate-binding "kernel defrecord Claim node (resolves via def-binding/file-typeframe)"
           :spellings {:Claim spell-claim :k/Claim spell-kclaim :k/->Claim spell-kctor}
           :total-tokens spell-total
           :answer-shape "ONE binding behind all three spellings + the reference set"
           :via "ultimate(refers_to(k/->Claim @ import.bclj)) = kernel Claim; then callers-of that node"}
    :c    {:target {:module "kernel" :name "thread-ids-i"}
           :closure-size (count closure-mn)
           :closure-members closure-mn
           :via "callgraph :blast transitive reaches-closure (Fram Datalog fixpoint over calls-defn)"}
    :d    {:type "(Vec Claim) / (Vec k/Claim) parameter annotations resolving to kernel Claim"
           :source-sites {:vec-Claim vec-claim :vec-k/Claim vec-kclaim :total vec-total}
           :lowered-vec-claim lowered-vec-claim
           :lowered-surviving-caret-sites lowered-caret-sites
           :lowered-surviving-caret-tokens lowered-caret-tokens
           :via "resolve.clj walk-type / resolve-types-in-bracket! — each annotation's refers_to to kernel Claim"}
    :e    {:target {:module "kernel" :name "Claim"}
           :graph-edit "resolve.clj rename Claim Datum kernel -> CLAIMS EDITED: 1 (one binding-name claim superseded)"
           :oracle "beagle-build-all -> exactly 0 errors (11 built, 0 error(s))"
           :via "the Beagle compiler is the oracle; rename is correct iff 0 errors"}}})

(spit "$OUT" (with-out-str (j/generate-stream result *out* {:pretty true})))
(binding [*out* *err*]
  (println "ground truth written to $OUT")
  (println "  (a) direct callers:" (count direct) "->" direct-modules)
  (println "  (b) spellings: Claim" spell-claim "k/Claim" spell-kclaim "k/->Claim" spell-kctor "= total" spell-total)
  (println "  (c) closure size:" (count closure-mn))
  (println "  (d) source sites:" vec-total "(Vec Claim" vec-claim "+ Vec k/Claim" vec-kclaim ") ; lowered Vec Claim:" lowered-vec-claim "; ^Claim sites/tokens:" lowered-caret-sites "/" lowered-caret-tokens))
BBEOF
