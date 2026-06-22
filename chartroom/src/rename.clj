(ns rename
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fram.cnf :as c]))

(def DEFHEADS #{"def" "defn" "defn-" "def-" "defonce" "definline"})

(defn ^String load-edn! [ctx tx file->ents ^String path]
  (let [lines (fram.str/split-lines (fram.rt/slurp path))
   src (let [header (first (filter (fn [l] (str/starts-with? l "@file")) lines))]
  (if (some? header) (subs header 6) path))
   local (atom (let [e {}]
  e))
   ent! (fn [lid] (let [existing (get (deref local) lid)]
  (if (some? existing) (let [e existing]
  e) (let [e (c/entity! ctx)]
  (swap! local (fn [m] (assoc m lid e)))
  (swap! file->ents (fn [m] (assoc m src (conj (get m src []) e))))
  e))))]
  (doseq [line lines
   :when (str/starts-with? line "[")]
  (let [triple (edn/read-string line)
   tv (let [x triple]
  x)
   s (nth tv 0)
   p (let [x (nth tv 1)]
  x)
   o (nth tv 2)
   L (ent! s)
   P (c/value! ctx p)
   R (if (integer? o) (ent! o) (c/value! ctx o))]
  (c/claim! ctx L P R tx)))
  src))

(defn field-child [ctx e ^String fname]
  (let [P (c/value-id ctx fname)]
  (if (some? P) (do
  (let [cids (c/by-lp ctx e (let [p P]
  p))]
  (if (seq cids) (do
  (let [cl (c/claim-of ctx (first cids))
   r (:r cl)]
  (if (integer? r) (do
  (let [ri r]
  ri)))))))))))

(defn ^Boolean symbol-leaf? [ctx KIND SYM e]
  (boolean (some (fn [cid] (= SYM (let [cl (c/claim-of ctx cid)]
  (let [r (:r cl)]
  (if (integer? r) (let [ri r]
  ri) -1))))) (c/by-lp ctx e KIND))))

(defn sym-val [ctx Vp KIND SYM e]
  (if (symbol-leaf? ctx KIND SYM e) (do
  (let [vc (filterv (fn [cid] (let [cl (c/claim-of ctx cid)
   p (:p cl)]
  (= p Vp))) (c/by-l ctx e))]
  (if (seq vc) (do
  (let [cl (c/claim-of ctx (first vc))
   r (:r cl)]
  (c/literal ctx (if (integer? r) (let [ri r]
  ri) 0)))))))))

(defn binding-name [ctx Vp KIND SYM e]
  (let [h (sym-val ctx Vp KIND SYM (let [fc (field-child ctx e "f0")]
  (if (some? fc) fc 0)))]
  (if (and (some? h) (contains? DEFHEADS h)) (do
  (let [f1 (field-child ctx e "f1")]
  (if (some? f1) (do
  (sym-val ctx Vp KIND SYM (let [e2 f1]
  e2)))))))))

(defn module-bindings [ctx Vp KIND SYM ents]
  (set (keep (fn [e] (binding-name ctx Vp KIND SYM e)) ents)))

(defn ^String project-file [ctx file->ents ^String src]
  (let [lines (vec (cons (str "@file " src) (mapcat (fn [e] (keep (fn [cid] (let [cl (c/claim-of ctx cid)
   p (:p cl)
   r (:r cl)
   ps (c/literal ctx (if (integer? p) (let [pi p]
  pi) 0))]
  (if (not= ps "supersedes") (do
  (if (c/value-object? ctx (if (integer? r) (let [ri r]
  ri) 0)) (str "[" e " " (fram.pr-str ps) " " (fram.pr-str (c/literal ctx (if (integer? r) (let [ri r]
  ri) 0))) "]") (str "[" e " " (fram.pr-str ps) " " r "]")))))) (c/by-l ctx e))) (get (deref file->ents) src []))))]
  (str (str/join "\n" lines) "\n")))

(defn rename! [^String old-name ^String new-name ^String target-substr edn-paths]
  (let [ctx (c/new-store)
   tx (c/begin-tx! ctx "author")
   SUP (c/value! ctx "supersedes")
   _ (c/set-supersedes-pred! ctx SUP)
   file->ents (atom (let [e {}]
  e))
   srcs (mapv (fn [p] (load-edn! ctx tx file->ents p)) edn-paths)
   Vp (c/value! ctx "v")
   KIND (c/value! ctx "kind")
   SYM (c/value! ctx "symbol")
   OLDv (c/value-id ctx old-name)
   NEWv (c/value! ctx new-name)
   target-mods (filterv (fn [s] (str/includes? s target-substr)) srcs)
   target-ents (set (mapcat (fn [s] (get (deref file->ents) s [])) target-mods))]
  (doseq [m target-mods]
  (if (contains? (module-bindings ctx Vp KIND SYM (get (deref file->ents) m [])) new-name) (do
  (fram.rt/println-err! (str "REJECTED — `" new-name "` is already a binding in " m "."))
  (fram.rt/println-err! "  A rename onto an existing binding would shadow/collide; the store refuses the write.")
  (fram.rt/println-err! "  (Turtle #5 invariant: rename-doesn't-collide. No claims were mutated.)")
  (fram.rt/exit! 3))))
  (let [renamed (atom 0)]
  (if (some? OLDv) (do
  (doseq [cid (vec (c/by-pr ctx Vp (let [v OLDv]
  v)))]
  (let [cl (c/claim-of ctx cid)
   e (:l cl)
   ei (if (integer? e) (let [ei2 e]
  ei2) 0)]
  (if (and (contains? target-ents ei) (symbol-leaf? ctx KIND SYM ei)) (do
  (let [ncid (c/claim! ctx ei Vp NEWv tx)]
  (c/claim! ctx ncid SUP cid tx))
  (swap! renamed (fn [x] (+ x 1)))))))))
  (let [preserved (if (some? OLDv) (count (filterv (fn [cid] (let [cl (c/claim-of ctx cid)
   e (:l cl)
   ei (if (integer? e) (let [ei2 e]
  ei2) 0)]
  (and (not (contains? target-ents ei)) (symbol-leaf? ctx KIND SYM ei)))) (vec (c/by-pr ctx Vp (let [v OLDv]
  v))))) 0)
   outs (into {} (mapv (fn [src] [src (str "/tmp/mutated-" (-> src (str/split #"/") last) ".edn")]) srcs))]
  (doseq [src srcs]
  (let [out-path (get outs src "")]
  (fram.rt/spit-file out-path (project-file ctx file->ents src))))
  {:renamed (deref renamed) :preserved preserved :old old-name :new new-name :target target-substr :srcs srcs :outs outs :live-claims (count (c/current-claims ctx))}))))
