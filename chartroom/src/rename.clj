#!/usr/bin/env bb
;; ============================================================================
;; Turtle #4 — graph-native authoring: a SCOPE-CORRECT rename as a claim edit.
;; ============================================================================
;; Loads two modules' reader-claims into ONE Fram store, then renames a symbol
;; in ONE module by SUPERSEDING its `v` claims (claim-native: nothing is
;; overwritten — the old claims stay, marked not-live, fully recoverable). The
;; other module's identically-named symbol is untouched. Re-projects each file's
;; (mutated) source. A text `sed` rename would corrupt both files; the graph
;; edit is correct by construction because scope is structural, not lexical.
;;
;;   bb -cp ~/code/fram/out src/rename.clj <old> <new> <target-substr> <edn>...
(ns rename
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fram.cnf :as c]))

(def argv *command-line-args*)
(def old-name (nth argv 0))
(def new-name (nth argv 1))
(def target-substr (nth argv 2))
(def edn-files (drop 3 argv))

(def ctx (c/new-store))
(def tx  (c/begin-tx! ctx "author"))
(def SUP (c/value! ctx "supersedes"))
(c/set-supersedes-pred! ctx SUP)

(def file->ents (atom {}))           ; src-path -> [entities]

(defn load-edn [path]
  (let [lines (str/split-lines (slurp path))
        src   (-> (first (filter #(str/starts-with? % "@file") lines)) (subs 6))
        local (atom {})
        ent   (fn [lid] (or (@local lid)
                            (let [e (c/entity! ctx)]
                              (swap! local assoc lid e)
                              (swap! file->ents update src (fnil conj []) e)
                              e)))]
    (doseq [line lines :when (str/starts-with? line "[")]
      (let [[s p o] (edn/read-string line)
            L (ent s) P (c/value! ctx p)
            R (if (integer? o) (ent o) (c/value! ctx o))]
        (c/claim! ctx L P R tx)))
    src))

(def srcs (mapv load-edn edn-files))

;; --- the edit: rename `old` -> `new` for symbol leaves in the target file ----
(def Vp   (c/value! ctx "v"))
(def KIND (c/value! ctx "kind"))
(def SYM  (c/value! ctx "symbol"))
(def OLDv (c/value-id ctx old-name))
(def NEWv (c/value! ctx new-name))

(defn symbol-leaf? [e]
  (some #(= SYM (:r (c/claim-of ctx %))) (c/by-lp ctx e KIND)))

(def target-modules (filter #(str/includes? % target-substr) (keys @file->ents)))
(def target-ents (set (mapcat @file->ents target-modules)))

;; --- INVARIANT (Turtle #5): a rename must not collide with an existing binding.
;; "Well-formed datum" is the weak guarantee; "renamed symbol doesn't already name
;; a binding in this module" is the one that makes this a refactoring engine, not a
;; fancy round-trip. (Scope here is module-local — exact scoping awaits id-references.)
(def DEFHEADS #{"def" "defn" "defn-" "def-" "defonce" "definline"})
(defn field-child [e fname]                         ; entity that is e's fname child
  (let [P (c/value-id ctx fname)]
    (when P (let [cids (c/by-lp ctx e P)] (when (seq cids) (:r (c/claim-of ctx (first cids))))))))
(defn sym-val [e]                                   ; if e is a symbol leaf, its name string
  (when (and e (symbol-leaf? e))
    (let [vc (filter #(= Vp (:p (c/claim-of ctx %))) (c/by-l ctx e))]
      (when (seq vc) (c/literal ctx (:r (c/claim-of ctx (first vc))))))))
(defn binding-name [e]                              ; if e is a (def|defn ...) list node, the bound name
  (let [h (sym-val (field-child e "f0"))]
    (when (and h (DEFHEADS h)) (sym-val (field-child e "f1")))))
(defn module-bindings [src] (set (keep binding-name (@file->ents src))))

(doseq [m target-modules]
  (when (contains? (module-bindings m) new-name)
    (binding [*out* *err*]
      (println (str "REJECTED — `" new-name "` is already a binding in " m ".\n"
                    "  A rename onto an existing binding would shadow/collide; the store refuses the write.\n"
                    "  (Turtle #5 invariant: rename-doesn't-collide. No claims were mutated.)")))
    (System/exit 3)))

(def renamed (atom 0))
(when OLDv
  (doseq [cid (vec (c/by-pr ctx Vp OLDv))]          ; every [e v old] claim
    (let [e (:l (c/claim-of ctx cid))]
      (when (and (target-ents e) (symbol-leaf? e))
        (let [ncid (c/claim! ctx e Vp NEWv tx)]     ; assert new value
          (c/claim! ctx ncid SUP cid tx))           ; supersede the old value-claim
        (swap! renamed inc)))))

;; occurrences of `old` left untouched in OTHER files (proof of scope-correctness)
(def preserved
  (if OLDv
    (count (filter (fn [cid] (let [e (:l (c/claim-of ctx cid))]
                               (and (not (target-ents e)) (symbol-leaf? e))))
                   (c/by-pr ctx Vp OLDv)))
    0))

;; --- re-project each file's source FROM the mutated store -------------------
(defn extract-file! [src out-path]
  (with-open [w (clojure.java.io/writer out-path)]
    (binding [*out* w]
      (println (str "@file " src))
      (doseq [e (@file->ents src)
              cid (c/by-l ctx e)]                    ; LIVE claims only (superseded excluded)
        (let [cl (c/claim-of ctx cid) p (:p cl) r (:r cl) ps (c/literal ctx p)]
          (when (not= ps "supersedes")
            (if (c/value-object? ctx r)
              (println (str "[" e " " (pr-str ps) " " (pr-str (c/literal ctx r)) "]"))
              (println (str "[" e " " (pr-str ps) " " r "]")))))))))

(def outs (into {} (for [src srcs]
                     [src (str "/tmp/mutated-" (-> src (str/split #"/") last) ".edn")])))
(doseq [src srcs] (extract-file! src (outs src)))

(binding [*out* *err*]
  (println "================ TURTLE #4 — graph-native rename ================")
  (println (str "edit: rename symbol `" old-name "` -> `" new-name "` in files matching \"" target-substr "\""))
  (println (str "renamed (target file): " @renamed " symbol occurrences"))
  (println (str "preserved (other files, same name, untouched): " preserved " occurrences"))
  (println (str "superseded claims (recoverable, nothing deleted): " @renamed))
  (println (str "live claims in store: " (count (c/current-claims ctx))))
  (doseq [src srcs] (println (str "projected -> " (outs src) "   <- " src))))
