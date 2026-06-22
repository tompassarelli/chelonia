(ns fram.tools
  (:require [fram.kernel :as k]
            [fram.query :as q]
            [clojure.string :as str]))

(def Op-values #{::one ::many ::revfrom ::threads ::show ::dependents ::validate ::query ::set ::add ::remove ::upsert-form ::set-body ::rename-def ::insert-after})

(defrecord IdTitle [id title])

(defn idtitle-id [r] (:id r))

(defn idtitle-title [r] (:title r))

(defrecord PredVal [pred value])

(defn predval-pred [r] (:pred r))

(defn predval-value [r] (:value r))

(defrecord ThreadViolation [thread violation])

(defn threadviolation-thread [r] (:thread r))

(defn threadviolation-violation [r] (:violation r))

(defrecord Param [name type required])

(defn param-name [r] (:name r))

(defn param-type [r] (:type r))

(defn param-required [r] (:required r))

(defrecord ToolSpec [name desc params op pred])

(defn toolspec-name [r] (:name r))

(defn toolspec-desc [r] (:desc r))

(defn toolspec-params [r] (:params r))

(defn toolspec-op [r] (:op r))

(defn toolspec-pred [r] (:pred r))

(defn- at [id]
  (if (string? id) (if (str/starts-with? id "@") id (str "@" id)) id))

(defn- distinct-preds [claims]
  (vec (sort (reduce (fn [acc c] (conj acc (:p c))) #{} claims))))

(defn- ^Boolean ref-pred? [claims ^String pred]
  (loop [cs claims]
  (if (empty? cs) false (let [c (first cs)]
  (if (and (= (:p c) pred) (string? (:r c)) (str/starts-with? (:r c) "@")) true (recur (rest cs)))))))

(defn- ^Boolean all-ref? [claims ^String pred]
  (loop [cs claims
   seen false]
  (if (empty? cs) seen (let [c (first cs)]
  (if (= (:p c) pred) (if (and (string? (:r c)) (str/starts-with? (:r c) "@")) (recur (rest cs) true) false) (recur (rest cs) seen))))))

(defn ref-value [claims ^String pred value]
  (if (all-ref? claims pred) (at value) value))

(defn- pred-tools [claims ^String pred]
  (let [single (k/single-in? claims pred)
   ref (ref-pred? claims pred)
   id-param [(->Param "id" "string" true)]
   idv-param [(->Param "id" "string" true) (->Param "value" "string" true)]
   reads (if single [(->ToolSpec (str pred "-of") (str "Get the " pred " of <id> (single-valued).") id-param :one pred)] [(->ToolSpec (str pred "-list") (str "List the " pred " values of <id>.") id-param :many pred)])
   revs (if ref [(->ToolSpec (str pred "-from") (str "Entities whose " pred " points at <id> (reverse edge).") id-param :revfrom pred)] [])
   writes (if single [(->ToolSpec (str "set-" pred) (str "Set the " pred " of <id> to <value> (replaces; single-valued).") idv-param :set pred)] [(->ToolSpec (str "add-" pred) (str "Add <value> to the " pred " of <id>.") idv-param :add pred) (->ToolSpec (str "remove-" pred) (str "Remove <value> from the " pred " of <id>.") idv-param :remove pred)])]
  (vec (concat reads (concat revs writes)))))

(defn- dedupe-by-name [specs]
  (loop [ss specs
   seen #{}
   out []]
  (if (empty? ss) out (let [s (first ss)
   nm (:name s)]
  (if (contains? seen nm) (recur (rest ss) seen out) (recur (rest ss) (conj seen nm) (conj out s)))))))

(defn catalog [claims]
  (let [structural [(->ToolSpec "threads" "List all threads (entities with a title) as {id,title}." [] :threads "") (->ToolSpec "show" "All claims about <id>." [(->Param "id" "string" true)] :show "") (->ToolSpec "dependents-of" "Threads that depend_on <id> (reverse depends_on)." [(->Param "id" "string" true)] :dependents "") (->ToolSpec "validate" "Structural integrity violations (cycles, dangling refs) across all threads." [] :validate "") (->ToolSpec "query" (str "Ad-hoc recursive query for multi-hop questions no named tool covers. " "Pass a structured Datalog-shaped object: " "{:find <rel> :rules [{:head {:rel R :args [terms]} :body [{:rel r :args [terms] :neg <bool>}]}]}. " "A term is {:var \"x\"} or a constant; base relations are triple(l,p,r) and claim(cid,l,p,r).") [(->Param "query" "object" true)] :query "") (->ToolSpec "add-def" (str "Author a claim-canonical Beagle module: add a NEW top-level def, or " "REPLACE an existing one by name (upsert by the def name). `form` is the " "whole top-level form as an EDN datum string, e.g. " "\"(defn add-two [x :- Int] :- Int (base (+ x 2)))\". Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "form" "string" true)] :upsert-form "") (->ToolSpec "set-body" (str "Author a claim-canonical Beagle module: replace the BODY of an existing " "defn named <name>. `body` is the new body as an EDN datum string, e.g. " "\"(* x 10)\" (params + return type are preserved). Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "name" "string" true) (->Param "body" "string" true)] :set-body "") (->ToolSpec "rename-def" (str "Author a claim-canonical Beagle module: rename a top-level def from <name> " "to <new-name> (O(1), scope-correct via refers_to, shadow-safe; references " "follow by identity). Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "name" "string" true) (->Param "new-name" "string" true)] :rename-def "") (->ToolSpec "insert-after" (str "Author a claim-canonical Beagle module: insert a NEW top-level def AFTER an " "anchor def named <after>, at a CRDT (path,tie) order-key strictly between the " "anchor and its next sibling. `form` is the whole top-level form as an EDN datum " "string, e.g. \"(defn add-two [x :- Int] :- Int (base (+ x 2)))\". Two concurrent " "inserts after the same anchor COMMUTE (both land at distinct ties). " "Recompile-gated + fail-closed.") [(->Param "module" "string" true) (->Param "after" "string" true) (->Param "form" "string" true)] :insert-after "")]
   per-pred (reduce (fn [acc pred] (vec (concat acc (pred-tools claims pred)))) [] (distinct-preds claims))]
  (dedupe-by-name (vec (concat structural per-pred)))))

(defn- spec-by-name [cat ^String name]
  (loop [cs cat]
  (if (empty? cs) nil (if (= (:name (first cs)) name) (first cs) (recur (rest cs))))))

(defn- missing-req [op args]
  (let [need-id (or (= op :one) (or (= op :many) (or (= op :revfrom) (or (= op :show) (or (= op :dependents) (or (= op :set) (or (= op :add) (= op :remove))))))))
   need-val (or (= op :set) (or (= op :add) (= op :remove)))
   need-module (or (= op :upsert-form) (or (= op :set-body) (or (= op :rename-def) (= op :insert-after))))
   e1 (if (and need-id (nil? (:id args))) ["missing required param 'id'"] [])
   e2 (if (and need-val (nil? (:value args))) ["missing required param 'value'"] [])
   e3 (if (and (= op :query) (nil? (:query args))) ["missing required param 'query'"] [])
   e4 (if (and need-module (nil? (:module args))) ["missing required param 'module'"] [])
   e5 (if (and (= op :upsert-form) (nil? (:form args))) ["missing required param 'form'"] [])
   e6 (if (and (= op :set-body) (or (nil? (:name args)) (nil? (:body args)))) ["missing required param 'name' and/or 'body'"] [])
   e7 (if (and (= op :rename-def) (or (nil? (:name args)) (nil? (:new-name args)))) ["missing required param 'name' and/or 'new-name'"] [])
   e8 (if (and (= op :insert-after) (or (nil? (:after args)) (nil? (:form args)))) ["missing required param 'after' and/or 'form'"] [])]
  (vec (concat e1 (concat e2 (concat e3 (concat e4 (concat e5 (concat e6 (concat e7 e8))))))))))

(defn call [claims idx cat ^String tool args]
  (let [spec (spec-by-name cat tool)]
  (if (nil? spec) {:error [(str "unknown tool '" tool "' — call `tools` for the catalog")]} (let [op (:op spec)
   pred (:pred spec)
   miss (missing-req op args)]
  (if (not (empty? miss)) {:error miss} (let [id (:id args)
   value (:value args)
   te (at id)
   rv (ref-value claims pred value)]
  (cond
  (= op :one) {:rows (let [v (k/one-i idx te pred)]
  (if (some? v) [v] []))}
  (= op :many) {:rows (k/many-i idx te pred)}
  (= op :revfrom) {:rows (reduce (fn [acc c] (if (and (= (:p c) pred) (= (:r c) te)) (conj acc (:l c)) acc)) [] claims)}
  (= op :threads) {:rows (mapv (fn [t] (->IdTitle t (k/one-i idx t "title"))) (k/thread-ids-i idx))}
  (= op :show) {:rows (mapv (fn [c] (->PredVal (:p c) (:r c))) (k/q-by-l claims te))}
  (= op :dependents) {:rows (k/dependents-i idx te)}
  (= op :validate) {:rows (reduce (fn [acc t] (vec (concat acc (mapv (fn [v] (->ThreadViolation t v)) (k/violations-i idx t))))) [] (k/thread-ids-i idx))}
  (= op :query) (q/run claims (:query args))
  (= op :set) {:write {:op "assert" :l te :p pred :r rv}}
  (= op :add) {:write {:op "assert" :l te :p pred :r rv}}
  (= op :remove) {:write {:op "retract" :l te :p pred :r rv}}
  (= op :upsert-form) {:edit {:op "upsert-form" :module (:module args) :form (:form args)}}
  (= op :set-body) {:edit {:op "set-body" :module (:module args) :name (:name args) :body (:body args)}}
  (= op :rename-def) {:edit {:op "rename" :module (:module args) :name (:name args) :new-name (:new-name args)}}
  (= op :insert-after) {:edit {:op "insert-form" :module (:module args) :after (:after args) :form (:form args)}}
  :else {:error [(str "unhandled op for tool '" tool "'")]})))))))
