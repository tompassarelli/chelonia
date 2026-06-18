#!/usr/bin/env bb
;; ============================================================================
;; Turtle #5 — the identity steal: references store IDENTITY, not spelling.
;; ============================================================================
;; A real LEXICAL resolver adds `refers_to <binding-node-id>` to each reference,
;; pointing it at the *correct* binding under shadowing (nearest enclosing scope),
;; not merely a binding with the right spelling. Binding occurrence = a symbol
;; leaf: a top-level def name, a fn/defn param, or a let/loop binding.
;;
;; With references carrying identity:
;;   - rename is O(1): edit ONE binding's name; references render via refers_to.
;;   - rename is EXACT under shadowing: a shadowed inner `red` is a different node
;;     than the outer def, so renaming one leaves the other alone — which text
;;     (sed) structurally cannot do.
;;   - "orphaned reference after delete" is a query (refers_to → a dead node).
;;
;;   bb -cp ~/code/fram/out src/resolve.clj resolve <edn>...
;;   bb -cp ~/code/fram/out src/resolve.clj rename <old> <new> <target> <edn>...
;;   bb -cp ~/code/fram/out src/resolve.clj delete <name> <target> <edn>...
;; ============================================================================
(ns resolve
  (:require [clojure.edn :as edn] [clojure.string :as str] [fram.cnf :as c]
            [fram.datalog :as d] [cheshire.core :as json]))   ; datalog+json: the `callgraph` mode

(def mode (first *command-line-args*))
(def ctx (c/new-store))
(def tx  (c/begin-tx! ctx "resolve"))
(def SUP (c/value! ctx "supersedes")) (c/set-supersedes-pred! ctx SUP)
(def file->ents (atom {}))

(defn load-edn [path]
  (let [lines (str/split-lines (slurp path))
        src   (-> (first (filter #(str/starts-with? % "@file") lines)) (subs 6))
        local (atom {})
        ent   (fn [lid] (or (@local lid)
                            (let [e (c/entity! ctx)] (swap! local assoc lid e)
                                 (swap! file->ents update src (fnil conj []) e) e)))]
    (doseq [line lines :when (str/starts-with? line "[")]
      (let [[s p o] (edn/read-string line)]
        (c/claim! ctx (ent s) (c/value! ctx p) (if (integer? o) (ent o) (c/value! ctx o)) tx)))
    src))

;; --- claim-graph accessors --------------------------------------------------
(def Vp (c/value! ctx "v")) (def KIND (c/value! ctx "kind")) (def REFERS (c/value! ctx "refers_to"))
(def FIXED (c/value! ctx "keep_spelling")) (def QUAL (c/value! ctx "qualifier"))   ; render-mode markers
(def CTOR (c/value! ctx "ctor_prefix"))    ; a `->Name` auto-constructor ref: render `->` + the type's name
(def ACC  (c/value! ctx "accessor_field")) ; a synth field accessor `<lower(Name)>-<field>`: stores the field
(defn pred-val [e pname]
  (let [P (c/value-id ctx pname)]
    (when P (let [cs (c/by-lp ctx e P)] (when (seq cs) (c/literal ctx (:r (c/claim-of ctx (first cs)))))))))
(defn kind-of [e] (pred-val e "kind"))
(defn sym-val [e] (when (= "symbol" (kind-of e)) (pred-val e "v")))
(defn ordered-children [e]
  (->> (c/by-l ctx e) (map #(c/claim-of ctx %))
       (keep (fn [cl] (let [p (c/literal ctx (:p cl))]
                        (when (and (string? p) (re-matches #"f\d+" p)) [(parse-long (subs p 1)) (:r cl)]))))
       (sort-by first) (mapv second)))
(defn ordered-segs [e]                         ; Turtle #6: a comment node's segN children, in order
  (->> (c/by-l ctx e) (map #(c/claim-of ctx %))
       (keep (fn [cl] (let [p (c/literal ctx (:p cl))]
                        (when (and (string? p) (re-matches #"seg\d+" p)) [(parse-long (subs p 3)) (:r cl)]))))
       (sort-by first) (mapv second)))
(defn head-sym [e] (when (= "list" (kind-of e)) (sym-val (first (ordered-children e)))))
(defn refers-target [L] (let [cs (c/by-lp ctx L REFERS)] (when (seq cs) (:r (c/claim-of ctx (first cs))))))
(defn live-node? [e] (seq (c/by-lp ctx e KIND)))

;; --- binding extraction -----------------------------------------------------
(def PARAM-FORMS #{"defn" "defn-" "fn" "defmacro" "fn*"})   ; have a [param] vector
(def DEF-FORMS   #{"def" "def-" "defonce"})    ; module value binding: (def name :- T val)
(def VALUE-DEFS  (into PARAM-FORMS DEF-FORMS)) ; everything that binds a value at module scope
(def TYPE-DEFS   #{"defrecord" "deftype" "defprotocol" "definterface" "defunion"})
(def TYPE-COLON  #{":-" ":"})  ; inline type-annotation markers (`:` is legal in field/param position)
(def LET-FORMS   #{"let" "loop" "when-let" "if-let" "when-some" "if-some" "binding"
                   "with-open" "with-local-vars" "dotimes" "with-redefs" "if-let*" "when-let*"})
(def FOR-FORMS   #{"doseq" "for"})             ; binding vector carries :when/:while/:let modifiers
(def MATCH-FORMS #{"match"})                    ; (match expr [pattern body] ...) — patterns bind + ref ctors
(defn brackets? [e] (= "#%brackets" (head-sym e)))
(defn map-node? [e] (= "#%map" (head-sym e)))
(defn collect-bind-syms [node]                 ; symbol leaves bound by a (destructuring) pattern
  (cond
    (sym-val node) (let [v (sym-val node)] (if (#{"&" "_"} v) [] [node]))
    (brackets? node) (mapcat collect-bind-syms (rest (ordered-children node)))      ; [a b & rest]
    (map-node? node)                                                                ; {:keys [a]} / {x :k} / :as
    (loop [ks (rest (ordered-children node)) acc []]
      (if (empty? ks) acc
        (let [k (first ks) kv (sym-val k) v (second ks)]
          (cond
            (#{":keys" ":strs" ":syms"} kv) (recur (drop 2 ks) (into acc (when (and v (brackets? v))
                                                                           (filter sym-val (rest (ordered-children v))))))
            (= ":as" kv)  (recur (drop 2 ks) (into acc (collect-bind-syms v)))
            (= ":or" kv)  (recur (drop 2 ks) acc)                                   ; defaults; keys bound elsewhere
            (sym-val k)   (recur (drop 2 ks) (conj acc k))                          ; {sym :keyword} -> sym binds
            :else         (recur (drop 2 ks) (into acc (collect-bind-syms k)))))))  ; nested destructuring key
    ;; typed PAREN binding `(name :- T)` / `(name : T)` — a legal param/field surface.
    ;; The bound name(s) are the symbols BEFORE the type marker; without this they fall
    ;; through to [] and the body use of `name` wrongly resolves to a same-named outer
    ;; def (capture). Stop at `:-` OR `:` so the type (e.g. Int) is NOT mis-collected.
    (= "list" (kind-of node))
    (mapcat collect-bind-syms (take-while #(not (TYPE-COLON (sym-val %))) (ordered-children node)))
    :else []))
(defn collect-or-vals [node]                   ; :or DEFAULT value-exprs inside a destructuring pattern
  (cond                                        ; (live refs — evaluated when the key is absent)
    (map-node? node)
    (loop [ks (rest (ordered-children node)) acc []]
      (if (empty? ks) acc
        (let [k (first ks) kv (sym-val k) v (second ks)]
          (cond
            (= ":or" kv) (recur (drop 2 ks) (into acc (when (and v (map-node? v))   ; {sym default ...}
                                                        (keep-indexed (fn [i c] (when (odd? i) c))
                                                                      (rest (ordered-children v))))))
            (#{":keys" ":strs" ":syms" ":as"} kv) (recur (drop 2 ks) acc)
            (sym-val k)  (recur (drop 2 ks) acc)
            :else        (recur (drop 2 ks) (into acc (collect-or-vals k)))))))      ; nested destructuring
    (brackets? node) (mapcat collect-or-vals (rest (ordered-children node)))
    :else []))
(defn param-binds [bracket]                    ; param names from [x :- T y], skipping types
  (loop [ks (rest (ordered-children bracket)) binds [] skip false]
    (if (empty? ks) binds
      (let [k (first ks) v (sym-val k)]
        (cond skip            (recur (rest ks) binds false)
              (TYPE-COLON v)  (recur (rest ks) binds true)
              :else           (recur (rest ks) (into binds (collect-bind-syms k)) false))))))
;; let/loop bindings are SEQUENTIAL — binding i's value (and :or defaults) see bindings
;; 0..i-1. Return ORDERED entries [bind-syms value-node or-default-vals] so walk/capture
;; can build the frame incrementally (a flat outer-scope walk misses sibling shadowing —
;; a real capture / mis-resolve bug).
(defn let-bind-pairs [bracket]                 ; -> [ [syms value-node or-vals] ... ] in source order
  (loop [ks (rest (ordered-children bracket)) acc []]
    (if (empty? ks) acc
      (let [pat (first ks)
            after (if (TYPE-COLON (sym-val (second ks))) (drop 3 ks) (rest ks))   ; skip a `:- T` annotation
            val (first after)]
        (recur (rest after) (conj acc [(collect-bind-syms pat) val (collect-or-vals pat)]))))))
(defn for-bind-pairs [bracket]                 ; for/doseq, ordered: [:bind syms vnode orvals] | [:expr node]
  (loop [ks (rest (ordered-children bracket)) acc []]
    (if (empty? ks) acc
      (let [k (first ks) kv (sym-val k) v (second ks)]
        (cond
          (#{":when" ":while"} kv) (recur (drop 2 ks) (conj acc [:expr v]))        ; modifier expr (sees prior binds)
          (= ":let" kv)            (recur (drop 2 ks) (into acc (when (and v (brackets? v))
                                                                  (map (fn [[s vn ov]] [:bind s vn ov])
                                                                       (let-bind-pairs v)))))
          (TYPE-COLON (sym-val v)) (recur (drop 4 ks) (conj acc [:bind (collect-bind-syms k) (nth ks 3 nil) (collect-or-vals k)]))  ; [x :- T coll]
          :else                    (recur (drop 2 ks) (conj acc [:bind (collect-bind-syms k) v (collect-or-vals k)])))))))
(defn frame-of [bsyms] (into {} (map (fn [b] [(sym-val b) b]) bsyms)))
(defn match-pat-binds [pat]                     ; symbols a match pattern binds: the NON-head leaves of a
  (cond                                         ; (Ctor a b) pattern, recursively (the head is a type ref)
    (sym-val pat) (let [v (sym-val pat)] (if (#{"_"} v) [] [pat]))
    (= "list" (kind-of pat)) (mapcat match-pat-binds (rest (ordered-children pat)))   ; skip the ctor head
    (brackets? pat) (mapcat match-pat-binds (rest (ordered-children pat)))            ; [a b] seq pattern
    :else []))                                                                        ; literals bind nothing

;; --- the lexical walk: resolve each reference to its nearest binding ---------
(def n-resolved (atom 0)) (def n-unresolved (atom 0)) (def n-xmod (atom 0)) (def n-type (atom 0))
(def n-comment (atom 0))                         ; Turtle #6: comment identifier mentions resolved
(def ^:dynamic *xresolve* (fn [_] nil))          ; cross-module value resolver: name -> {:node :mode :alias}
(def ^:dynamic *tresolve* (fn [_] nil))          ; type-name -> type-def node (module-local)
(def ^:dynamic *aresolve* (fn [_] nil))          ; accessor-name `point-x` -> [type-def-leaf field-string]
(defn bind! [L target] (c/claim! ctx L REFERS target tx) (swap! n-resolved inc))
(defn bind-xmod! [node x]   ; x = {:target :mode :alias :accessor} from *xresolve*; refers_to + render markers
  (when (and x (:target x))
    (bind! node (:target x))
    (case (:mode x)
      :fixed (c/claim! ctx node FIXED (c/value! ctx "1") tx)   ; :rename — keep own spelling
      :qual  (c/claim! ctx node QUAL (c/value! ctx (:alias x)) tx) ; x/name — show alias/newname
      nil)                                                      ; :tracking — render def's current name
    (when (:accessor x)                                         ; cross-module synth accessor: render <lower(name)>-field
      (c/claim! ctx node ACC (c/value! ctx (:accessor x)) tx))
    (swap! n-xmod inc)
    true))
(declare walk walk-quasi walk-quasi-seq walk-fn-arity walk-pat-heads)
(defn walk-type [node]                           ; resolve a TYPE position to its type definition
  (cond
    (sym-val node) (let [nm (sym-val node)]      ; module-local type, else cross-module (:refer/:as) type
                     (or (when-let [b (*tresolve* nm)] (bind! node b) (swap! n-type inc) true)
                         (bind-xmod! node (*xresolve* nm))))
    (= "list" (kind-of node)) (doseq [c (ordered-children node)] (walk-type c))   ; compound type (Vec Int)
    (brackets? node) (doseq [c (rest (ordered-children node))] (walk-type c))
    :else nil))
(defn resolve-type-after-colon! [nodes]          ; in a flat seq, walk-type the node after `:-`/`:`
  (loop [xs nodes]
    (when (seq xs)
      (if (TYPE-COLON (sym-val (first xs)))
        (when (second xs) (walk-type (second xs)))
        (recur (rest xs))))))
(defn resolve-types-in-bracket! [bracket]        ; resolve every `:- T`/`: T` in a param/field vector
  (loop [ks (rest (ordered-children bracket))]
    (when (seq ks)
      (let [k (first ks)]
        (cond
          (TYPE-COLON (sym-val k)) (do (when (second ks) (walk-type (second ks))) (recur (drop 2 ks)))
          (= "list" (kind-of k)) (do (resolve-type-after-colon! (ordered-children k)) ; paren `(x :- T)`
                                     (recur (rest ks)))
          :else (recur (rest ks)))))))
(defn walk-all [nodes scope] (doseq [n nodes] (walk n scope)))
(defn walk-fn-arity [forms scope]                ; one fn arity: (param-bracket (:- Ret)? body...)
  (let [pv (first (filter brackets? forms))
        binds (if pv (param-binds pv) [])
        _ (when pv (resolve-types-in-bracket! pv))            ; resolve PARAM types -> type defs
        or-vals (when pv (mapcat collect-or-vals (rest (ordered-children pv))))  ; :or defaults: live refs
        frame (frame-of binds)
        body (loop [xs (rest (drop-while #(not (brackets? %)) forms))]   ; drop (:- T)/(:raises T) pairs
               (if (#{":-" ":" ":raises"} (sym-val (first xs)))
                 (do (when (second xs) (walk-type (second xs))) (recur (drop 2 xs)))
                 xs))]
    (walk-all or-vals scope)                     ; :or defaults evaluate in the OUTER scope (before params bind)
    (walk-all body (cons frame scope))))         ; param names bind in body; types resolved to type defs
(defn walk-pat-heads [pat scope]                 ; resolve constructor heads in a (nested) match pattern as type refs
  (when (= "list" (kind-of pat))
    (walk (first (ordered-children pat)) scope)  ; ctor head -> type ref (walk's *tresolve* fallback handles it)
    (doseq [c (rest (ordered-children pat))] (walk-pat-heads c scope))))   ; recurse nested sub-patterns
(defn walk [node scope]
  (case (kind-of node)
    "symbol"
    (let [nm (sym-val node)
          local (some #(get % nm) scope)]       ; nearest frame binding nm
      (cond
        local (bind! node local)
        ;; free symbol: cross-module value/type import (:refer/:rename/:as), else a
        ;; module-local TYPE used in value position (a constructor `(Point ...)` /
        ;; defunion variant `(Circle ...)` — its name leaf IS the type def), else native.
        (bind-xmod! node (*xresolve* nm)) nil
        (when-let [b (*tresolve* nm)] (bind! node b) (swap! n-type inc) true) nil
        ;; auto-constructor factory — `->Name` (positional) or `map->Name` (map), bare OR alias-qualified
        ;; (`a/->Name`). Strip the prefix, resolve type Name (module-local then cross-module), store the
        ;; prefix so render re-applies it — a rename of the type carries every factory with it.
        (when-let [pfx (cond (or (str/starts-with? (or nm "") "map->") (str/includes? (or nm "") "/map->")) "map->"
                             (or (str/starts-with? (or nm "") "->") (str/includes? (or nm "") "/->")) "->"
                             :else nil)]
          (let [stripped (str/replace nm pfx "")]    ; a/map->Point -> a/Point ; ->Point -> Point
            (or (when-let [b (*tresolve* stripped)]
                  (bind! node b) (c/claim! ctx node CTOR (c/value! ctx pfx) tx) (swap! n-type inc) true)
                (when (bind-xmod! node (*xresolve* stripped))
                  (c/claim! ctx node CTOR (c/value! ctx pfx) tx) true)))) nil
        ;; synthesized field accessor `<lower(Record)>-<field>` — bind to the record, store the field so
        ;; render reconstructs `<lower(newName)>-<field>` when the record is renamed.
        (when-let [a (*aresolve* nm)]
          (bind! node (first a)) (c/claim! ctx node ACC (c/value! ctx (second a)) tx) (swap! n-type inc) true) nil
        :else (swap! n-unresolved inc)))         ; builtin/native — correctly NO refers_to
    "list"
    (let [kids (ordered-children node) h (head-sym node)]
      (cond
        (#{"quote"} h) nil                       ; quoted data are not references
        (#{"quasiquote"} h) (walk-quasi node scope false)   ; template: bare module refs live, quotes data, unquotes escape
        (TYPE-DEFS h)                            ; skip the type name; resolve field/variant/method-param types
        (doseq [c (drop 2 kids)]
          (cond
            (brackets? c) (resolve-types-in-bracket! c)          ; defrecord/deftype direct field vector
            (= "list" (kind-of c))                               ; defunion variant / defprotocol method sig
            (do (doseq [b (filter brackets? (ordered-children c))] (resolve-types-in-bracket! b))
                ;; a method sig (m [params] :- Ret) also carries a RETURN type after the bracket
                (resolve-type-after-colon! (rest (drop-while #(not (brackets? %)) (ordered-children c)))))
            ;; a BARE union member (defunion Result Ok Err) that names an existing type is a REFERENCE to
            ;; it — bind so a rename of the record cascades; a true nullary variant resolves to itself (skip).
            (sym-val c) (let [b (*tresolve* (sym-val c))] (when (and b (not= b c)) (bind! c b) (swap! n-type inc)))
            :else nil))
        (DEF-FORMS h)                            ; (def name :- T val) — skip name, resolve T, walk val
        (let [after-name (drop 2 kids)]
          (if (= ":-" (sym-val (first after-name)))
            (do (when (second after-name) (walk-type (second after-name)))
                (walk-all (drop 2 after-name) scope))
            (walk-all after-name scope)))
        (PARAM-FORMS h)                          ; single-arity (top-level [params]) OR multi-arity (([p]..)([p]..))
        (let [after-name (if (#{"defn" "defn-" "defmacro"} h) (drop 2 kids) (rest kids))]
          (if (some brackets? after-name)
            (walk-fn-arity after-name scope)     ; single arity
            (doseq [a after-name :when (and (= "list" (kind-of a)) (brackets? (first (ordered-children a))))]
              (walk-fn-arity (ordered-children a) scope))))   ; each ([params] :- Ret body...) arity
        (LET-FORMS h)
        (let [bracket (second kids)
              _ (when (and bracket (brackets? bracket)) (resolve-types-in-bracket! bracket))  ; `:- T` annotations
              pairs (if (and bracket (brackets? bracket)) (let-bind-pairs bracket) [])
              ;; SEQUENTIAL: binding i's value + :or defaults see bindings 0..i-1
              final (reduce (fn [sc [bsyms vnode orvals]]
                              (walk-all orvals sc) (when vnode (walk vnode sc))
                              (cons (frame-of bsyms) sc))
                            scope pairs)]
          (walk-all (drop 2 kids) final))        ; body sees all bindings
        (FOR-FORMS h)
        (let [bracket (second kids)
              _ (when (and bracket (brackets? bracket)) (resolve-types-in-bracket! bracket))  ; `:- T` annotations
              entries (if (and bracket (brackets? bracket)) (for-bind-pairs bracket) [])
              final (reduce (fn [sc e]
                              (if (= :expr (first e))
                                (do (walk (second e) sc) sc)         ; :when/:while expr sees prior binds
                                (let [[_ bsyms vnode orvals] e]
                                  (walk-all orvals sc) (when vnode (walk vnode sc))
                                  (cons (frame-of bsyms) sc))))
                            scope entries)]
          (walk-all (drop 2 kids) final))        ; body sees all for bindings
        (MATCH-FORMS h)                          ; (match expr [pattern body] ...) — patterns bind + ref ctors
        (let [kids (ordered-children node)]
          (walk (second kids) scope)             ; the matched expression
          (doseq [clause (drop 2 kids) :when (brackets? clause)]
            (let [cc (rest (ordered-children clause)) pat (first cc) body (rest cc)]
              (walk-pat-heads pat scope)         ; constructor heads are TYPE references
              (walk-all body (cons (frame-of (match-pat-binds pat)) scope)))))  ; body sees pattern binds
        (= h "letfn")                            ; (letfn [(name [params] :- Ret body...) ...] body...)
        (let [bracket (second kids)              ; fn NAMES are mutually-recursive bindings
              fnlists (when (and bracket (brackets? bracket)) (filter #(= "list" (kind-of %)) (rest (ordered-children bracket))))
              frame (frame-of (keep #(first (ordered-children %)) fnlists))
              bodyscope (cons frame scope)]
          (doseq [fl fnlists] (walk-fn-arity (rest (ordered-children fl)) bodyscope))  ; each fn body sees all names + own params
          (walk-all (drop 2 kids) bodyscope))    ; letfn body sees all fn names
        (#{"extend-type" "extend-protocol"} h)   ; (extend-type T Proto (method [params] body...) ...)
        (doseq [c (rest kids)]
          (cond
            (sym-val c) (walk c scope)           ; Type / Protocol — type references
            (= "list" (kind-of c)) (let [ic (ordered-children c)]
                                     (walk (first ic) scope)           ; method name — protocol-method ref
                                     (walk-fn-arity (rest ic) scope))  ; impl params bind in the impl body
            :else nil))
        (= h "as->")                             ; (as-> init name step...) — `name` binds the accumulator
        (let [init (nth kids 1 nil) name (nth kids 2 nil)
              frame (frame-of (when (sym-val name) [name]))]
          (when init (walk init scope))          ; init evaluated in the OUTER scope (before name binds)
          (walk-all (drop 3 kids) (cons frame scope)))   ; each step sees the accumulator name
        :else (walk-all kids scope)))            ; ordinary call: head + args are all references
    nil))

;; quasiquote: most of a template is quoted DATA. What an `,x` (unquote) / `,@x`
;; (unquote-splicing) escapes is evaluated code (real references). AND — a bare
;; template symbol that names a MODULE DEF or IMPORT is itself a live reference:
;; Clojure `` ` `` namespace-qualifies it and beagle hygiene-aliases it (`base__hyg`),
;; so renaming the def must follow it. A symbol bound by a real LOCAL (param/let —
;; an inner frame) or a free gensym is hygiene/quote data and must be left alone.
;; `quoted?` tracks being inside a `(quote ..)` WITHIN the template: there a bare symbol
;; is inert DATA (not hygiene-aliased), so it must NOT resolve — BUT an `(unquote ..)`
;; still escapes to evaluated code even inside a quote (Clojure: `(quote ~(inc 1)) => (quote 2)),
;; so unquotes are always live regardless of quote nesting.
(defn walk-quasi [node scope quoted?]
  (cond
    (sym-val node)
    (when-not quoted?                                    ; bare symbol in (quote ..) = data; outside = live ref
      (let [nm (sym-val node)]
        (cond
          (some #(get % nm) (butlast scope)) nil          ; a real local/gensym binding — leave it
          (get (last scope) nm) (bind! node (get (last scope) nm))  ; module def — live qualified ref
          (bind-xmod! node (*xresolve* nm)) nil           ; cross-module import — live ref
          :else nil)))                                    ; truly free symbol — quoted data, leave it
    (= "list" (kind-of node))
    (let [h (head-sym node)]
      (cond
        (#{"unquote" "unquote-splicing"} h) (walk-all (rest (ordered-children node)) scope)  ; explicit (unquote ..)
        (#{"quote"} h) (walk-quasi-seq (ordered-children node) scope true)   ; inside a quote: bare syms = data
        :else (walk-quasi-seq (ordered-children node) scope quoted?)))       ; still template; scan siblings
    :else nil))
;; scan template siblings: a bare `~`/`,` (or `~@`/`,@`) TOKEN — beagle's reader form for unquote (it does
;; NOT emit an (unquote ..) wrapper) — escapes its FOLLOWING sibling back to live code, even inside a quote.
(defn walk-quasi-seq [children scope quoted?]
  (loop [cs children]
    (when (seq cs)
      (if (#{"~" "," "~@" ",@"} (sym-val (first cs)))
        (do (when (second cs) (walk (second cs) scope)) (recur (drop 2 cs)))   ; ~EXPR — live escape
        (do (walk-quasi (first cs) scope quoted?) (recur (rest cs)))))))

;; module frame = all top-level defs (so forward references resolve) ----------
(defn unwrap-def [form] (if (= "js/export" (head-sym form)) (second (ordered-children form)) form))
(defn module-defs [src]
  (let [wrapper (some (fn [e] (when (= "beagle-file" (head-sym e)) e)) (@file->ents src))
        forms (rest (ordered-children wrapper))]
    (into {} (mapcat (fn [f] (let [d (unwrap-def f)]
                               (cond
                                 (VALUE-DEFS (head-sym d))   ; def/defn — value binding
                                 (let [nl (second (ordered-children d))] (when (sym-val nl) [[(sym-val nl) nl]]))
                                 ;; defprotocol/definterface methods are public callable VARS — each method
                                 ;; sig (name [params] :- Ret) defines `name`; collect so it renames + resolves.
                                 (#{"defprotocol" "definterface"} (head-sym d))
                                 (keep (fn [m] (when (= "list" (kind-of m))
                                                 (let [nl (first (ordered-children m))]
                                                   (when (sym-val nl) [(sym-val nl) nl]))))
                                       (drop 2 (ordered-children d)))
                                 :else nil)))
                     forms))))
;; --- cross-module: parse ns/:require (imports) and js/export (exports) -------
(defn forms-of [src]
  (rest (ordered-children (some (fn [e] (when (= "beagle-file" (head-sym e)) e)) (@file->ents src)))))
(defn ns-form [src] (some (fn [f] (when (= "ns" (head-sym f)) f)) (forms-of src)))
(defn module-name [src] (when-let [nf (ns-form src)] (sym-val (second (ordered-children nf)))))
(defn merge-import-opts [acc modn kids]   ; kids = tokens after the module name; fold :refer/:as/:rename
  (let [idx (fn [kw] (first (keep-indexed (fn [i k] (when (= kw (sym-val k)) i)) kids)))
        ri (idx ":refer") ai (idx ":as") rri (idx ":rename")
        nb (when ri (nth kids (inc ri) nil))
        refers (when (and nb (brackets? nb)) (keep sym-val (rest (ordered-children nb))))
        alias (when ai (sym-val (nth kids (inc ai) nil)))
        rmap (when rri (let [mb (nth kids (inc rri) nil)]   ; {srcname -> localname}
                         (when (and mb (map-node? mb))
                           (loop [cs (rest (ordered-children mb)) m {}]
                             (if (< (count cs) 2) m
                               (recur (drop 2 cs) (assoc m (sym-val (first cs)) (sym-val (second cs)))))))))]
    (cond-> acc
      (seq refers) (update :refer into (map (fn [n] [n modn]) refers))
      alias        (update :as assoc alias modn)
      (seq rmap)   (update :rename into (map (fn [[sn ln]] [ln [modn sn]]) rmap)))))
(defn parse-require [src]   ; {:refer {name->mod}, :as {alias->mod}, :rename {local->[mod srcname]}}
  (let [empty {:refer {} :as {} :rename {}}
        ;; bare top-level beagle requires: (require modn :as a :refer [..] :rename {..})
        bare (reduce (fn [acc f]
                       (if (= "require" (head-sym f))
                         (let [kids (ordered-children f)]
                           (merge-import-opts acc (sym-val (nth kids 1 nil)) (drop 2 kids)))
                         acc))
                     empty (forms-of src))]
    (if-let [nf (ns-form src)]
      (if-let [reqs (some (fn [c] (when (and (= "list" (kind-of c))
                                             (= ":require" (sym-val (first (ordered-children c))))) c))
                          (ordered-children nf))]
        (reduce (fn [acc spec]                      ; ns-form specs: [modn :refer [..] :as a ...]
                  (if-not (brackets? spec) acc
                    (let [kids (rest (ordered-children spec))]
                      (merge-import-opts acc (sym-val (first kids)) (rest kids)))))
                bare (rest (ordered-children reqs)))
        bare)
      bare)))
(defn module-exports [src]            ; {exported-name -> binding-node}  (js/export def OR re-export)
  (into {} (keep (fn [f]
                   (when (= "js/export" (head-sym f))
                     (let [d (second (ordered-children f))]
                       (cond
                         (VALUE-DEFS (head-sym d)) (let [nl (second (ordered-children d))] [(sym-val nl) nl])
                         (sym-val d)               [(sym-val d) d]))))   ; (js/export red) — re-export
                 (forms-of src))))
(defn type-name-leaf [d]              ; a type def's name-leaf, unwrapping a parameterized (Name Params) head
  (let [nl0 (second (ordered-children d))]
    (if (= "list" (kind-of nl0)) (first (ordered-children nl0)) nl0)))
(defn module-types [src]              ; {type-name -> name-leaf}  (defrecord/deftype/.../defunion + variants)
  (let [defs (filter #(TYPE-DEFS (head-sym (unwrap-def %))) (forms-of src))
        names (into {} (keep (fn [f] (let [nl (type-name-leaf (unwrap-def f))]
                                       (when (sym-val nl) [(sym-val nl) nl]))) defs))
        ;; defunion variant constructors: inline (Variant [fields]) OR bare nullary `None`. A bare member
        ;; that is ALSO a top-level type name (e.g. (defunion Result Ok Err) over defrecords) is NOT a new
        ;; binding — `merge names` below makes the type-def name authoritative, and walk binds the member
        ;; occurrence to it as a reference, so the whole type renames together (no defrecord/union split).
        variants (into {} (mapcat (fn [f] (let [d (unwrap-def f)]
                                            (when (= "defunion" (head-sym d))
                                              (keep (fn [v] (cond
                                                              (= "list" (kind-of v))
                                                              (let [vn (first (ordered-children v))]
                                                                (when (sym-val vn) [(sym-val vn) vn]))
                                                              (sym-val v) [(sym-val v) v]))
                                                    (drop 2 (ordered-children d))))))
                                  defs))]
    (merge variants names)))
;; beagle synthesizes a field accessor `<lower(RecordName)>-<field>` per defrecord/deftype field.
;; A renamed record must carry its accessor CALL sites, so map each accessor name to its type + field.
(defn module-accessors [src]          ; {"point-x" -> [Point-name-leaf "x"]}
  (into {} (mapcat (fn [f] (let [d (unwrap-def f)]
                             (when (#{"defrecord" "deftype"} (head-sym d))
                               (let [nl (type-name-leaf d)
                                     fb (first (filter brackets? (drop 2 (ordered-children d))))]
                                 (when (and (sym-val nl) fb)
                                   (let [pfx (str/lower-case (sym-val nl))]
                                     (map (fn [fld] [(str pfx "-" fld) [nl fld]])
                                          (keep sym-val (param-binds fb)))))))))
                   (forms-of src))))

(def srcs (mapv load-edn (drop (case mode "resolve" 1 "rename" 4 "delete" 3 "callgraph" 1
                                          "upsert-form" 3 "set-body" 4) *command-line-args*)))
(def file-modframe (into {} (map (fn [s] [s (module-defs s)]) srcs)))
(def file-typeframe (into {} (map (fn [s] [s (module-types s)]) srcs)))
(def file-accessors (into {} (map (fn [s] [s (module-accessors s)]) srcs)))
(defn def-binding [src nm] (or (get (file-modframe src) nm) (get (file-typeframe src) nm)))  ; value OR type
(def global-exports          ; module-name -> {exported-name -> binding-node}
  ;; beagle modules carry an (ns ...) form but export IMPLICITLY (no js/export), so
  ;; fall back to ALL top-level defs as the export surface. JS modules with explicit
  ;; js/export use those. (Clojure semantics agree: a public def IS exported.)
  (into {} (map (fn [s] [(module-name s)
                         (let [e (module-exports s)] (if (seq e) e (module-defs s)))])
                (filter module-name srcs))))
(def global-type-exports     ; module-name -> {type-name -> type-def name-leaf}
  ;; types export implicitly too; a consumer's :refer/:as of a record/union/protocol
  ;; resolves here. Without it, a foreign type in a `:- T` annotation never tracks a
  ;; rename and a cross-module delete of the type false-reports 'safe'.
  (into {} (map (fn [s] [(module-name s) (module-types s)]) (filter module-name srcs))))
(def global-accessor-exports ; module-name -> {"point-x" -> [type-name-leaf field]}
  ;; synthesized field accessors export too; the cross-module half of the local *aresolve*,
  ;; so a record rename carries c/point-x / :refer'd point-x (parallel to global-type-exports).
  (into {} (map (fn [s] [(module-name s) (module-accessors s)]) (filter module-name srcs))))
(defn make-xresolve [src]
  (let [{:keys [refer as rename]} (parse-require src)
        ;; a :refer'd / :as-qualified / :rename'd name may be a VALUE or a TYPE export
        xport (fn [m n] (or (get-in global-exports [m n]) (get-in global-type-exports [m n])))
        xacc  (fn [m n] (get-in global-accessor-exports [m n]))]   ; [type-leaf field] or nil
    (fn [nm]
      (cond
        (get refer nm)  (let [m (get refer nm)]
                          (if-let [t (xport m nm)] {:target t :mode :tracking}
                            (when-let [a (xacc m nm)] {:target (first a) :mode :tracking :accessor (second a)})))
        (get rename nm) (let [[m sn] (get rename nm)] {:target (xport m sn) :mode :fixed})
        (str/includes? nm "/")
        ;; qualifier is an :as alias OR a fully-spelled module name (e.g. (require acc.prod) then acc.prod/Box)
        (let [[al pn] (str/split nm #"/" 2)
              m (or (get as al)
                    (when (some #(contains? % al) [global-exports global-type-exports global-accessor-exports]) al))]
          (when m
            (if-let [t (xport m pn)] {:target t :mode :qual :alias al}
              (when-let [a (xacc m pn)] {:target (first a) :mode :qual :alias al :accessor (second a)}))))
        :else nil))))
;; --- Turtle #6: resolve identifier mentions INSIDE comments -----------------
;; A comment is a sequence of text + symbol-candidate segments. A symbol segment
;; that EXACTLY names an in-scope binding (module def / type / refer-import) gets
;; a refers_to edge — so it renders the binding's CURRENT name and renames with
;; it, exactly like code. A `red-zone` token is one symbol (≠ `red`) and a quoted
;; `"red"` was demoted to text by beagle's lexer, so neither resolves: the rename
;; win without the sed corruption. Module scope (comments-in-bodies are a follow-up).
(defn cbind! [L target] (c/claim! ctx L REFERS target tx) (swap! n-comment inc))
(defn resolve-comment [e src]
  (doseq [seg (ordered-segs e) :when (= "symbol" (kind-of seg))]
    (let [nm (sym-val seg)
          b  (or (def-binding src nm)              ; module value/type def (forward refs ok)
                 (:target (*xresolve* nm)))]        ; refer/rename/alias import -> tracks current name
      (when b (cbind! seg b)))))
(defn walk-comments [src]
  (doseq [e (@file->ents src) :when (= "comment" (kind-of e))]
    (resolve-comment e src)))

(doseq [src srcs]
  (binding [*xresolve* (make-xresolve src)
            *tresolve* (fn [nm] (get (file-typeframe src) nm))
            *aresolve* (fn [nm] (get (file-accessors src) nm))]
    (walk-all (forms-of src) (list (file-modframe src)))
    (walk-comments src)))

;; --- projection: emit EDN for beagle --render, names resolved via refers_to --
;; follow refers_to transitively (re-export chains: a (js/export name) re-export is
;; itself a reference) to the ULTIMATE binding, and render its current name.
(defn ultimate [B] (loop [b B n 0] (let [t (refers-target b)] (if (and t (< n 64)) (recur t (inc n)) b))))
(defn binding-name [B] (sym-val (ultimate B)))

;; ============================================================================
;; AUTHORING — mint a NEW datum subtree into the SAME claim store (the inverse of
;; claims-roundtrip.rkt's datum->claims). This is what makes add-def / set-body a
;; CLAIM OPERATION, not a text splice: a Clojure EDN datum (the structured edit
;; spec the agent emits, e.g. `(defn add-two [x :- Int] :- Int (+ x 2))`) is walked
;; into fresh entities carrying `kind`/`v`/`fN` claims — exactly the reader-datum
;; shape --emit-edn projects — and registered in file->ents so extract-file! emits
;; them. The wrapper/body fN edges are then wired (append) or SUPERSEDED (replace),
;; reusing the rename template (assert new, supersede old; reads filter superseded).
;; The renderer reconstructs purely from fN/tail, so a minted subtree round-trips
;; byte-stable, and any reference in it resolves via the SAME lexical walk (a fresh
;; pass over forms-of after minting), giving scope-correctness for free.
;; ============================================================================
(defn register! [src e] (swap! file->ents update src (fnil conj []) e) e)
;; leaf-kind: the reader `kind` for a Clojure scalar (mirrors datum->claims:55-64).
;; Beagle reads [..] as (#%brackets ..) and {..} as (#%map ..), so a vector/map datum
;; in the spec is minted as a `list` headed by that desugaring symbol — identical to
;; what --emit-edn produces, keeping the projection lossless.
(defn mint-leaf! [src kind v]
  (let [e (register! src (c/entity! ctx))]
    (c/claim! ctx e KIND (c/value! ctx kind) tx)
    (c/claim! ctx e Vp (c/value! ctx v) tx)
    e))
(defn mint-datum! [src d]
  (cond
    (nil? d)        (let [e (register! src (c/entity! ctx))] (c/claim! ctx e KIND (c/value! ctx "nil") tx) e)
    (symbol? d)     (mint-leaf! src "symbol"  (str d))
    (keyword? d)    (mint-leaf! src "keyword" (subs (str d) 1))
    (string? d)     (mint-leaf! src "string"  d)
    (boolean? d)    (mint-leaf! src "bool"    (if d "true" "false"))
    (char? d)       (mint-leaf! src "char"    (str d))
    (number? d)     (mint-leaf! src "number"  (str d))
    (or (list? d) (seq? d) (vector? d) (map? d))
    ;; symbols built via (symbol ..) — writing #%brackets literally would be read as a
    ;; reader tag by Clojure's reader at load time of this file.
    (let [head  (cond (vector? d) [(symbol "#%brackets")] (map? d) [(symbol "#%map")] :else [])
          elems (concat head (if (map? d) (apply concat (seq d)) (seq d)))
          e     (register! src (c/entity! ctx))]
      (c/claim! ctx e KIND (c/value! ctx "list") tx)
      (doseq [[i x] (map-indexed vector elems)]
        (c/claim! ctx e (c/value! ctx (str "f" i)) (mint-datum! src x) tx))
      e)
    :else (mint-leaf! src "other" (pr-str d))))
;; the body fN edges of a defn form = the consecutive fN child claims whose slot is
;; AFTER the params bracket (everything --emit-edn put at f5,f6,... in `defn` :122).
(defn fN-claims [parent]            ; -> [[N claim-id child-node] ...] over LIVE fN edges, ordered
  (->> (c/by-l ctx parent) (map (fn [cid] [cid (c/claim-of ctx cid)]))
       (keep (fn [[cid cl]] (let [p (c/literal ctx (:p cl))]
                              (when (and (string? p) (re-matches #"f\d+" p))
                                [(parse-long (subs p 1)) cid (:r cl)]))))
       (sort-by first)))
;; supersede a claim WITHOUT a replacement value (e.g. retiring a wrapper/body fN edge).
;; The supersedes edge needs a subject; a fresh entity is fine — the live-view filter
;; keys off the superseded :r (the old claim id), not the subject (cnf.bclj:105-106,116).
(defn retire-claim! [oldc] (c/claim! ctx (c/entity! ctx) SUP oldc tx))

;; --- delete projection: omit a top-level form + its subtree, renumber siblings ---
;; The renderer reads fN children CONSECUTIVELY and includes only nodes reachable from
;; the root, so deleting a form means (a) skip its whole subtree (else its orphaned root
;; would compete with the real wrapper) and (b) re-emit the wrapper's surviving forms at
;; consecutive fN (a gap would truncate the file). Pure projection — the store is not mutated.
(def ^:dynamic *deleted-forms* #{})      ; wrapper-child form node-ids to omit (per src, but ids are global)
(def ^:dynamic *deleted-subtree* #{})    ; all entity ids under deleted forms — skipped on emit
(defn wrapper-of [src] (some (fn [e] (when (= "beagle-file" (head-sym e)) e)) (@file->ents src)))
(defn structural-kids [n]                ; child node ids via fN/segN/commentN/tail edges
  (->> (c/by-l ctx n) (map #(c/claim-of ctx %))
       (keep (fn [cl] (let [p (c/literal ctx (:p cl)) r (:r cl)]
                        (when (and (integer? r) (string? p)
                                   (or (re-matches #"f\d+" p) (re-matches #"seg\d+" p)
                                       (re-matches #"comment\d+" p) (= p "tail")))   ; a form's own doc-comment
                          r))))))
(defn descendants [root]                 ; root + all transitive structural descendants
  (loop [seen #{} stack [root]]
    (if (empty? stack) seen
      (let [n (peek stack)]
        (if (seen n) (recur seen (pop stack))
          (recur (conj seen n) (into (pop stack) (structural-kids n))))))))
(defn form-for-victim [src victim]       ; the top-level form whose def-NAME is victim (only the name —
  (some (fn [f]                          ; a defunion VARIANT is not its own top-level form, so it won't match)
          (let [nl0 (second (ordered-children (unwrap-def f)))
                nl (if (= "list" (kind-of nl0)) (first (ordered-children nl0)) nl0)]   ; (Name Params) head
            (when (= victim nl) f)))
        (rest (ordered-children (wrapper-of src)))))
(defn extract-file! [src out-path]
  (with-open [w (clojure.java.io/writer out-path)]
    (binding [*out* w]
      (println (str "@file " src))
      (let [wrap (when (seq *deleted-forms*) (wrapper-of src))]
        (doseq [e (@file->ents src) :when (not (*deleted-subtree* e)), cid (c/by-l ctx e)]
          (let [cl (c/claim-of ctx cid) p (:p cl) r (:r cl) ps (c/literal ctx p)]
            (cond
              ;; wrapper form-edges under a delete: drop them; renumbered ones re-emitted below
              (and (= e wrap) (string? ps) (re-matches #"f\d+" ps) (pos? (parse-long (subs ps 1)))) nil
              (#{"supersedes" "refers_to" "keep_spelling" "qualifier" "ctor_prefix" "accessor_field"} ps) nil  ; internal edges
              (and (= ps "v") (refers-target e))              ; a resolved reference: render per mode
              (let [D (refers-target e)
                    fixed? (seq (c/by-lp ctx e FIXED))        ; :rename alias — keep own spelling
                    qual (pred-val e "qualifier")             ; x/name — show alias/current-name
                    cpfx (pred-val e "ctor_prefix")           ; "->" / "map->" auto-constructor — re-prefix
                    afield (pred-val e "accessor_field")      ; synth accessor — render <lower(name)>-<field>
                    nm (binding-name D)
                    nm (cond cpfx   (str cpfx nm)
                             afield (str (str/lower-case nm) "-" afield)
                             :else  nm)]
                (println (str "[" e " \"v\" "
                              (pr-str (cond fixed? (c/literal ctx r)
                                            qual   (str qual "/" nm)
                                            :else  nm))
                              "]")))
              (c/value-object? ctx r) (println (str "[" e " " (pr-str ps) " " (pr-str (c/literal ctx r)) "]"))
              :else (println (str "[" e " " (pr-str ps) " " r "]")))))
        (when wrap                                            ; re-emit surviving forms at consecutive fN
          (let [forms (remove *deleted-forms* (rest (ordered-children wrap)))]
            (doseq [[i f] (map-indexed vector forms)]
              (println (str "[" wrap " \"f" (inc i) "\" " f "]")))))))))
;; render output dir honors $RESOLVE_OUT (default /tmp) so concurrent gate runs / agents
;; don't collide on a global /tmp/resolved-*.edn — the gates set it to a per-run temp dir.
(defn out-path [src] (str (or (System/getenv "RESOLVE_OUT") "/tmp") "/resolved-" (-> src (str/split #"/") last) ".edn"))

;; --- no-capture invariant ---------------------------------------------------
;; Renaming def B to `new` is UNSOUND if a reference to B would, after rendering
;; as `new`, be captured by a LOCAL binding `new` in scope at that reference —
;; e.g. (def src 1)(defn f [dst] (+ dst src)), rename src->dst yields (+ dst dst).
;; This is the lexical dual of the def-vs-def collision guard: a reference that
;; resolves to B (unqualified, name-tracking — not a :rename-fixed or x/qualified
;; ref, which don't render as a bare `new`) is captured iff `new` is in its scope.
;; capture-refs reuses walk's exact frame construction so the check is scope-precise.
(defn renders-as-tracked-name? [node]            ; reference that will render the binding's CURRENT name
  (and (not (seq (c/by-lp ctx node FIXED)))      ; :rename — keeps its own spelling
       (not (pred-val node "qualifier"))))        ; x/name — renders alias/, can't be captured by a bare local
(defn capture-refs [node scope B new]            ; refs to B that a local `new` in scope would capture
  (case (kind-of node)
    "symbol" (if (and (refers-target node) (= B (ultimate (refers-target node)))
                      (renders-as-tracked-name? node) (some #(get % new) scope))
               [node] [])
    "list"
    (let [kids (ordered-children node) h (head-sym node)]
      (cond
        (PARAM-FORMS h)                          ; single- OR multi-arity (mirror walk)
        (let [after-name (if (#{"defn" "defn-" "defmacro"} h) (drop 2 kids) (rest kids))
              cap-arity (fn [forms]
                          (let [pv (first (filter brackets? forms))
                                frame (frame-of (if pv (param-binds pv) []))
                                or-vals (when pv (mapcat collect-or-vals (rest (ordered-children pv))))
                                body (loop [xs (rest (drop-while #(not (brackets? %)) forms))]
                                       (if (#{":-" ":" ":raises"} (sym-val (first xs))) (recur (drop 2 xs)) xs))]
                            (concat (mapcat #(capture-refs % scope B new) or-vals)        ; :or defaults: outer scope
                                    (mapcat #(capture-refs % (cons frame scope) B new) body))))]
          (if (some brackets? after-name)
            (cap-arity after-name)
            (mapcat (fn [a] (if (and (= "list" (kind-of a)) (brackets? (first (ordered-children a))))
                              (cap-arity (ordered-children a)) []))
                    after-name)))
        (LET-FORMS h)                            ; SEQUENTIAL: value/:or of binding i see bindings 0..i-1
        (let [bracket (second kids)
              pairs (if (and bracket (brackets? bracket)) (let-bind-pairs bracket) [])
              [final vcaps] (reduce (fn [[sc caps] [bsyms vnode orvals]]
                                      [(cons (frame-of bsyms) sc)
                                       (into caps (concat (mapcat #(capture-refs % sc B new) orvals)
                                                          (when vnode (capture-refs vnode sc B new))))])
                                    [scope []] pairs)]
          (concat vcaps (mapcat #(capture-refs % final B new) (drop 2 kids))))
        (FOR-FORMS h)
        (let [bracket (second kids)
              entries (if (and bracket (brackets? bracket)) (for-bind-pairs bracket) [])
              [final vcaps] (reduce (fn [[sc caps] e]
                                      (if (= :expr (first e))
                                        [sc (into caps (capture-refs (second e) sc B new))]
                                        (let [[_ bsyms vnode orvals] e]
                                          [(cons (frame-of bsyms) sc)
                                           (into caps (concat (mapcat #(capture-refs % sc B new) orvals)
                                                              (when vnode (capture-refs vnode sc B new))))])))
                                    [scope []] entries)]
          (concat vcaps (mapcat #(capture-refs % final B new) (drop 2 kids))))
        (MATCH-FORMS h)                          ; match clause bodies see the pattern's bound names
        (let [kids (ordered-children node)]
          (concat (capture-refs (second kids) scope B new)
                  (mapcat (fn [clause]
                            (if (brackets? clause)
                              (let [cc (rest (ordered-children clause)) pat (first cc) body (rest cc)
                                    frame (frame-of (match-pat-binds pat))]
                                (concat (capture-refs pat scope B new)   ; ctor heads (bind-vars have no refers_to)
                                        (mapcat #(capture-refs % (cons frame scope) B new) body)))
                              []))
                          (drop 2 kids))))
        (= h "letfn")                            ; fn names + each fn's params are bindings
        (let [bracket (second kids)
              fnlists (when (and bracket (brackets? bracket)) (filter #(= "list" (kind-of %)) (rest (ordered-children bracket))))
              frame (frame-of (keep #(first (ordered-children %)) fnlists))
              bodyscope (cons frame scope)
              cap-arity (fn [forms]              ; one fn impl: param frame over bodyscope
                          (let [pv (first (filter brackets? forms))
                                pframe (frame-of (if pv (param-binds pv) []))
                                fbody (loop [xs (rest (drop-while #(not (brackets? %)) forms))]
                                        (if (#{":-" ":" ":raises"} (sym-val (first xs))) (recur (drop 2 xs)) xs))]
                            (mapcat #(capture-refs % (cons pframe bodyscope) B new) fbody)))]
          (concat (mapcat (fn [fl] (cap-arity (rest (ordered-children fl)))) fnlists)
                  (mapcat #(capture-refs % bodyscope B new) (drop 2 kids))))
        (#{"extend-type" "extend-protocol"} h)   ; impl method params are bindings
        (mapcat (fn [c]
                  (if (= "list" (kind-of c))
                    (let [ic (ordered-children c) pv (first (filter brackets? (rest ic)))
                          pframe (frame-of (if pv (param-binds pv) []))
                          fbody (loop [xs (rest (drop-while #(not (brackets? %)) (rest ic)))]
                                  (if (#{":-" ":" ":raises"} (sym-val (first xs))) (recur (drop 2 xs)) xs))]
                      (concat (capture-refs (first ic) scope B new)        ; method name ref
                              (mapcat #(capture-refs % (cons pframe scope) B new) fbody)))
                    (capture-refs c scope B new)))                          ; Type / Proto refs
                (rest kids))
        (= h "as->")                             ; accumulator `name` binds in every step
        (let [init (nth kids 1 nil) name (nth kids 2 nil)
              frame (frame-of (when (sym-val name) [name]))]
          (concat (when init (capture-refs init scope B new))
                  (mapcat #(capture-refs % (cons frame scope) B new) (drop 3 kids))))
        :else (mapcat #(capture-refs % scope B new) kids)))
    []))

;; --- authoring support (used by the upsert-form / set-body case arms) -------
;; re-resolve!: after a mint, the module frame is stale (a new def, or a new body's
;; references). Recompute every module's frame + re-walk forms so fresh references
;; carry refers_to. Idempotent — bind! only adds an edge where one resolves.
(defn re-resolve! []
  (let [modframe  (into {} (map (fn [s] [s (module-defs s)]) srcs))
        typeframe (into {} (map (fn [s] [s (module-types s)]) srcs))
        accessors (into {} (map (fn [s] [s (module-accessors s)]) srcs))]
    (doseq [src srcs]
      (binding [*xresolve* (make-xresolve src)
                *tresolve* (fn [nm] (get (get typeframe src) nm))
                *aresolve* (fn [nm] (get (get accessors src) nm))]
        (walk-all (forms-of src) (list (get modframe src)))
        (walk-comments src)))))
(defn author-emit! [op detail]
  (doseq [src srcs] (extract-file! src (out-path src)))
  (binding [*out* *err*]
    (println (str "================ authoring: " op " ================"))
    (println detail)
    (doseq [src srcs] (println (str "projected -> " (out-path src) "   <- " src)))))

;; ============================================================================
(case mode
  "resolve"
  (binding [*out* *err*]
    (println "================ Turtle #5 — lexical resolution pass ================")
    (println (str "references resolved (carry refers_to → a binding node): " @n-resolved
                  "  (" @n-xmod " cross-module, " @n-type " type references)"))
    (println (str "unresolved (builtins / native — correctly NO refers_to): " @n-unresolved))
    (println (str "comment identifier mentions resolved (rename-correct doc comments): " @n-comment))
    ;; write the resolved projection so identity can be checked: with NO rename,
    ;; projecting through refers_to must reproduce the original source exactly.
    (doseq [src srcs] (extract-file! src (out-path src)))
    (doseq [src srcs]
      (println (str "  " (-> src (str/split #"/") last) ": "
                    (count (filter #(and (= "symbol" (kind-of %)) (refers-target %)) (@file->ents src)))
                    " references carry refers_to; projected (identity) -> " (out-path src)))))

  "rename"
  (let [[old new target] (drop 1 *command-line-args*)
        target-srcs (filter #(str/includes? % target) srcs)
        edits (atom 0)]
    ;; INVARIANT (now EXACT via the resolver): refuse if `new` already names a
    ;; module binding in a file we'd rename. id-based, not a spelling heuristic.
    (doseq [src target-srcs]
      (when (and (def-binding src old) (def-binding src new))
        (binding [*out* *err*]
          (println (str "REJECTED — `" new "` already names a binding in " src
                        " (rename-doesn't-collide; no claims mutated).")))
        (System/exit 3)))
    ;; INVARIANT (type-name shape): beagle type names must be Capitalized — renaming a
    ;; TYPE (record/union/variant/protocol) to a lowercase name builds 'unknown type'.
    (doseq [src target-srcs]
      (when (and (get (file-typeframe src) old) (not (re-find #"^[A-Z]" new)))
        (binding [*out* *err*]
          (println (str "REJECTED — `" new "` is not a valid (Capitalized) type name "
                        "(beagle type-name shape; no claims mutated).")))
        (System/exit 3)))
    ;; INVARIANT (no-capture): refuse if a reference to the renamed def would be
    ;; captured by a LOCAL `new` in scope (checked across ALL srcs — a :refer'd bare
    ;; ref lives in a consumer file). Scope-precise via the resolver, not a heuristic.
    (doseq [src target-srcs]
      (when-let [B (def-binding src old)]
        (let [caps (mapcat (fn [s] (mapcat #(capture-refs % (list (file-modframe s)) B new)
                                           (forms-of s))) srcs)]
          (when (seq caps)
            (binding [*out* *err*]
              (println (str "REJECTED — renaming `" old "` -> `" new "` would be CAPTURED by a local `"
                            new "` in scope at " (count caps) " reference(s) (no-capture; no claims mutated).")))
            (System/exit 4)))))
    ;; INVARIANT (no-import-collision): a consumer that :refer's `old` from the target
    ;; will, after rename, bind `new` — refuse if that consumer ALREADY binds `new` (a
    ;; local def or another import), which would duplicate the binding (invalid module).
    (let [target-mods (set (keep module-name target-srcs))]
      (doseq [src srcs :when (not (some #{src} target-srcs))]
        (let [{:keys [refer rename]} (parse-require src)]
          (when (and (contains? target-mods (get refer old))
                     (or (def-binding src new) (get refer new) (get rename new)))
            (binding [*out* *err*]
              (println (str "REJECTED — renaming `" old "` -> `" new "` would DUPLICATE a binding in consumer "
                            src " (it already binds `" new "`; no-import-collision; no claims mutated).")))
            (System/exit 3)))))
    (doseq [src target-srcs]
      (when-let [B (def-binding src old)]                  ; value OR type def binding occurrence
        (let [oldc (first (filter #(= Vp (:p (c/claim-of ctx %))) (c/by-l ctx B)))
              nc (c/claim! ctx B Vp (c/value! ctx new) tx)]
          (c/claim! ctx nc SUP oldc tx) (swap! edits inc))))
    ;; INVARIANT (rename-hits-something): a rename that edits 0 bindings means `old`
    ;; names nothing in scope (e.g. an unsupported form) — refuse rather than report
    ;; a misleading success on an unchanged tree.
    (when (zero? @edits)
      (binding [*out* *err*]
        (println (str "REJECTED — no binding named `" old "` found in \"" target
                      "\" (nothing to rename; no claims mutated).")))
      (System/exit 5))
    (doseq [src srcs] (extract-file! src (out-path src)))
    (binding [*out* *err*]
      (println "================ Turtle #5 — O(1) shadow-correct rename ================")
      (println (str "edit: rename def `" old "` -> `" new "` in \"" target "\""))
      (println (str "CLAIMS EDITED: " @edits "  (just the definition's name; references follow refers_to)"))
      (doseq [src srcs] (println (str "projected -> " (out-path src) "   <- " src)))))

  "delete"
  (let [[name target] (drop 1 *command-line-args*)
        target-srcs (filter #(str/includes? % target) srcs)
        victims (keep #(def-binding % name) target-srcs)   ; value OR type binding occurrences to delete
        ;; the top-level forms to remove + their whole subtrees (incl. each form's own
        ;; doc-comment AND, for a defunion, its variant-constructor name-leaves). Computed
        ;; FIRST so the orphan check can both exclude refs INSIDE a deleted form and flag
        ;; surviving refs to ANY binding the deletion removes — the union name OR a variant.
        all-forms (set (mapcat (fn [src] (keep #(form-for-victim src %) victims)) srcs))
        subtree (reduce into #{} (map descendants all-forms))
        orphans (for [src srcs, e (@file->ents src)
                      :when (and (= "symbol" (kind-of e)) (refers-target e) (not (subtree e))
                                 (subtree (ultimate (refers-target e))))] e)]   ; ref to a deleted binding
    (when (zero? (count victims))
      (binding [*out* *err*]
        (println (str "REJECTED — no binding named `" name "` found in \"" target "\" (nothing to delete).")))
      (System/exit 5))
    ;; matched a binding but no independently-deletable top-level form (e.g. a defunion
    ;; variant lives nested inside its union) — refuse, don't report a no-op as success.
    (when (empty? all-forms)
      (binding [*out* *err*]
        (println (str "REJECTED — `" name "` is not an independently-deletable top-level form "
                      "(a defunion variant / nested binding); no claims mutated.")))
      (System/exit 5))
    ;; INVARIANT (no-orphaned-refs): refuse if any SURVIVING reference points at a victim.
    (when (pos? (count orphans))
      (binding [*out* *err*]
        (println "================ Turtle #5 — delete + orphaned-reference invariant ================")
        (println (str "REJECTED — " (count orphans) " reference(s) would be ORPHANED (no-orphaned-refs):"))
        (doseq [o (take 5 orphans)] (println (str "  orphan: reference node " o " (`" (sym-val o) "`)"))))
      (System/exit 6))
    ;; SAFE: project each src with the victim forms (and their subtrees) omitted, siblings renumbered.
    (binding [*deleted-forms* all-forms *deleted-subtree* subtree]
      (doseq [src srcs] (extract-file! src (out-path src))))
    (binding [*out* *err*]
      (println "================ Turtle #5 — delete (no-orphaned-refs satisfied) ================")
      (println (str "deleted def `" name "` in \"" target "\": " (count all-forms) " form(s); 0 orphaned refs"))
      (doseq [src srcs] (println (str "projected -> " (out-path src) "   <- " src)))))

  ;; ============================================================================
  ;; AUTHORING VERBS — the GAP closed: a claim operation for novel authoring.
  ;; upsert-form : add a NEW top-level def (append a wrapper fN edge) OR replace an
  ;;               existing top-level def by name (supersede its wrapper fN edge to
  ;;               point at a freshly-minted subtree). The form is given as an EDN
  ;;               datum (the structured edit spec), minted into the SAME store.
  ;; Both reuse extract-file! (the rename/delete render machine) and re-run the
  ;; lexical walk over the post-mint corpus, so a reference in the new code resolves
  ;; via refers_to (scope-correct) exactly like hand-written code — then the recompile
  ;; gate (authoring.sh) is the only acceptance criterion. fail-closed before that.
  ;; ============================================================================
  "upsert-form"
  (let [[scope spec-file] (drop 1 *command-line-args*)
        target-srcs (filter #(str/includes? % scope) srcs)
        datum (edn/read-string (slurp spec-file))]
    ;; INVARIANT (single target): scope must name exactly one source file to author into.
    (when (not= 1 (count target-srcs))
      (binding [*out* *err*]
        (println (str "REJECTED — scope \"" scope "\" matches " (count target-srcs)
                      " source files; upsert-form needs exactly one (no claims mutated).")))
      (System/exit 3))
    ;; INVARIANT (well-formed def): the spec must be a top-level form whose second
    ;; element is the def NAME symbol — else there is nothing to upsert by name.
    (when (and (seq? datum) (not (VALUE-DEFS (str (first datum)))))
      (binding [*out* *err*]
        (println (str "REJECTED — upsert-form spec head `" (first datum)
                      "` is not a value def (def/defn/...); no claims mutated.")))
      (System/exit 3))
    (let [src (first target-srcs)
          wrap (wrapper-of src)
          forms (vec (fN-claims wrap))               ; [[N claim child] ...] incl. f0=beagle-file sym
          new-name (when (and (seq? datum) (VALUE-DEFS (str (first datum)))) (str (second datum)))
          existing (when new-name (def-binding src new-name))
          ;; the existing top-level form (if replacing) — locate its wrapper fN slot
          victim-form (when existing (form-for-victim src existing))
          victim-entry (when victim-form (some (fn [[n cid r]] (when (= r victim-form) [n cid r])) forms))
          new-root (mint-datum! src datum)]
      (if victim-entry
        ;; REPLACE: supersede the old wrapper fN edge; re-point the SAME slot index.
        (let [[n cid _] victim-entry]
          (retire-claim! cid)
          (c/claim! ctx wrap (c/value! ctx (str "f" n)) new-root tx))
        ;; ADD: append at the next consecutive wrapper fN slot (max f-index + 1).
        (let [next-n (inc (apply max (map first forms)))]
          (c/claim! ctx wrap (c/value! ctx (str "f" next-n)) new-root tx)))
      (re-resolve!)
      (author-emit! "upsert-form"
                    (str (if victim-entry "replaced" "added") " top-level def `" new-name
                         "` in \"" scope "\" (1 form minted as claims; refs resolved via refers_to)"))))

  ;; set-body : replace a defn's BODY — supersede every post-params fN edge of the
  ;; named defn and re-wire to a freshly-minted body datum.
  "set-body"
  (let [[name scope body-file] (drop 1 *command-line-args*)
        target-srcs (filter #(str/includes? % scope) srcs)
        datum (edn/read-string (slurp body-file))]
    (when (not= 1 (count target-srcs))
      (binding [*out* *err*]
        (println (str "REJECTED — scope \"" scope "\" matches " (count target-srcs)
                      " source files; set-body needs exactly one (no claims mutated).")))
      (System/exit 3))
    (let [src (first target-srcs)
          B (def-binding src name)
          form (when B (form-for-victim src B))
          d (when form (unwrap-def form))]
      ;; INVARIANT (target exists + is a defn): set-body needs a defn form whose body
      ;; is the post-params fN edges. A bare (def x val) value has no params bracket.
      (when (or (nil? form) (not (PARAM-FORMS (head-sym d))))
        (binding [*out* *err*]
          (println (str "REJECTED — `" name "` is not a defn with a body in \"" scope
                        "\" (set-body needs a defn; no claims mutated).")))
        (System/exit 5))
      (let [kids (fN-claims d)                       ; [[N claim child] ...]
            bracket-n (some (fn [[n _ r]] (when (brackets? r) n)) kids)
            ;; the optional `:- Ret` annotation sits AFTER the params bracket; the body
            ;; is every fN slot after that. Keep f0..(bracket + ret) — replace the rest.
            ret? (some (fn [[n _ r]] (and (= n (inc bracket-n)) (TYPE-COLON (sym-val r)))) kids)
            body-start (+ bracket-n (if ret? 3 1))   ; skip params, and `:- Ret` (2 slots) if present
            body-slots (filter (fn [[n _ _]] (>= n body-start)) kids)
            new-root (mint-datum! src datum)]
        (when (empty? body-slots)
          (binding [*out* *err*]
            (println (str "REJECTED — `" name "` has no body fN edges to replace; no claims mutated.")))
          (System/exit 5))
        ;; supersede every old body fN edge, then wire the new single body form at the
        ;; first body slot (a defn body is an implicit do; one form is well-formed).
        (doseq [[_ cid _] body-slots] (retire-claim! cid))
        (c/claim! ctx d (c/value! ctx (str "f" body-start)) new-root tx)
        (re-resolve!)
        (author-emit! "set-body"
                      (str "replaced body of defn `" name "` in \"" scope "\" ("
                           (count body-slots) " body slot(s) superseded; new body minted as claims)")))))

  ;; ============================================================================
  ;; callgraph — the scope-correct call graph + transitive blast radius, derived
  ;; from the SAME refers_to edges the rename/delete engine uses. A "call" is a
  ;; reference in list-HEAD position whose binding (followed transitively) is a
  ;; top-level defn; the caller is its enclosing top-level defn. Because refers_to
  ;; is the converged cross-module/multi-arity/collision-correct resolution, this
  ;; call graph is too — unlike a bare-callname index, it does NOT drop qualified
  ;; (a/f, m/f) cross-module calls. Emits the JSON beagle-cascade consumes.
  ;; ============================================================================
  "callgraph"
  (let [dkey      (fn [src leaf] (str src "#" leaf))
        defn-meta (into {} (for [src srcs, [nm leaf] (file-modframe src)]
                             [leaf {:key (dkey src leaf) :file src
                                    :module (or (module-name src)
                                                (-> src (str/split #"/") last (str/replace #"\.[^.]+$" "")))
                                    :name nm}]))
        defn-set  (set (keys defn-meta))
        ;; ALL resolved reference symbols in a subtree (any position — not just list-head).
        ;; For a BLAST RADIUS ("what must change if I change X"), every reference to a defn is
        ;; a dependency: a head call (f x), a value-pass (mapv f xs), a threaded step (-> x f),
        ;; a `:- T` annotation. Head-only silently under-reports (proven on shipped fram/src).
        call-refs (fn call-refs [node]
                    (if (refers-target node) [node]
                      (when (= "list" (kind-of node)) (mapcat call-refs (ordered-children node)))))
        ;; callers = [caller-defn-leaf, body-node] pairs. A top-level value defn is one caller;
        ;; an extend-type/extend-protocol attributes each impl method's body to that protocol
        ;; method (the impl method-name resolves to it via refers_to) — those bodies were skipped.
        callers (mapcat
                 (fn [form]
                   (let [d (unwrap-def form) h (head-sym d)]
                     (cond
                       (VALUE-DEFS h)
                       (let [cl (second (ordered-children d))] (when (defn-meta cl) [[cl d]]))
                       (#{"extend-type" "extend-protocol"} h)
                       (keep (fn [c] (when (= "list" (kind-of c))
                                       (let [mnode (first (ordered-children c))
                                             cl (when (sym-val mnode) (ultimate (refers-target mnode)))]
                                         (when (and cl (defn-meta cl)) [cl c]))))
                             (rest (ordered-children d))))))
                 (mapcat forms-of srcs))
        edges (vec (distinct
                    (for [[caller-leaf body] callers
                          r (call-refs body)
                          :let [callee (ultimate (refers-target r))]  ; follow refers_to to the bound defn
                          :when (and (defn-set callee) (not= callee caller-leaf))]
                      [(:key (defn-meta caller-leaf)) (:key (defn-meta callee))])))
        ;; transitive blast radius via Fram Datalog: blast(D) = {x | x transitively calls D}
        bctx (c/new-store) btx (c/begin-tx! bctx "code") EDGE (c/value! bctx "calls-defn")
        k->e (volatile! {})
        bent (fn [k] (or (get @k->e k) (let [e (c/entity! bctx)] (vswap! k->e assoc k e) e)))
        _ (doseq [[a b] edges] (c/claim! bctx (bent a) EDGE (bent b) btx))
        e->k (into {} (map (fn [[k v]] [v k]) @k->e))
        db (d/run-rules bctx
             [(d/rule "reaches" [(d/v :x) (d/v :y)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])])
              (d/rule "reaches" [(d/v :x) (d/v :z)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])
                                                     (d/lit "reaches" [(d/v :y) (d/v :z)])])])
        reaches (set (d/facts db "reaches"))
        blast (reduce (fn [m [xid yid]] (update m (e->k yid) (fnil conj #{}) (e->k xid))) {} reaches)]
    (binding [*out* *err*]
      (println (format "callgraph: %d defns, %d scope-correct edges, %d transitive reaches-pairs (refers_to + Fram Datalog)"
                       (count defn-meta) (count edges) (count reaches))))
    (println (json/generate-string
              {:defns (vec (vals defn-meta)) :edges edges
               :blast (into {} (map (fn [[k vs]] [k (vec vs)]) blast))}))))
