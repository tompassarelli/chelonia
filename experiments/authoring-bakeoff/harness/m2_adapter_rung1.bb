#!/usr/bin/env bb
;; m2_adapter_rung1.bb — the M2 claim-changeset adapter for Rung-1 EDIT tasks.
;; ============================================================================
;; Rung-0's adapter (m2_adapter.bb) is ADDITIVE-only: the model mints all-fresh
;; "@m#N" ids and the harness never surfaces existing engine ids. Rung-1 is about
;; EDITS to an existing program (rename, re-mint a body) — so the model must ADDRESS
;; EXISTING NODES. This adapter adds exactly that, as a SEPARATE file (the Rung-0
;; run is frozen; nothing here touches it):
;;
;;   - the M2 STATE is the engine integer-node dump (the real ids), so a use site is
;;     reachable as `[<id> :v "f"]`. The model edits by RETRACT/ASSERT over those ids.
;;   - existing nodes keep their INTEGER ids; fresh "@m#N" model ids are minted above
;;     the current max (no collision), exactly like Rung-0.
;;   - supersede semantics: `[RETRACT <id> :v "old"]` + `[<id> :v "new"]` re-spells a
;;     leaf in place; a bare assert on an existing predicate overwrites it.
;;   - render-back preserves srcloc-free node order and re-attaches fresh top-level
;;     forms to the file wrapper (for 1B's net-new body subtree, if any).
;;
;; THE GATE IS UNCHANGED: the rendered dump goes to `beagle-build-all --build-edn`
;; (the recompile gate) + the runtime grader — the route-edit/flip-graph-edit! path,
;; NOT :edit-min. (FREEZE D3.)
;; ============================================================================
(ns m2-adapter-rung1
  (:require [clojure.string :as str]
            [clojure.edn :as edn]))

(defn- read-all-forms [line]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. line))]
    (loop [acc []]
      (let [form (try (edn/read {:eof ::eof} rdr) (catch Exception _ ::bad))]
        (cond
          (= form ::eof) acc
          (= form ::bad) ::bad
          :else (recur (conj acc form)))))))

;; an id token in a changeset may be a bare int (5), an int-string ("5"), or "@m#N".
;; normalize: ints/int-strings -> the long; "@..." stays a string. The "@..."-string is
;; NOT yet decided fresh-vs-existing here — that needs the current dump (an "@m#8" that
;; names an EXISTING node 8 addresses it; one whose number is unused is a fresh mint).
;; That decision lives in render-engine-dump/resolve, which has the dump in scope.
(defn- norm-id [x]
  (cond
    (integer? x) x
    (and (string? x) (re-matches #"\d+" x)) (parse-long x)
    (and (string? x) (str/starts-with? x "@")) x
    (symbol? x) (str x)
    :else x))

;; the trailing integer of an "@id" spelling, if any: "@m#8" -> 8, "@8" -> 8, "@mN" -> nil.
;; The M2 prompt spells EVERY node "@m#<N>" (its worked example + the E6 rename), so the
;; model addresses an existing engine node N as "@m#N". This extracts that N so resolution
;; can map it back to the integer node when N exists in the current state. (Without this,
;; every "@id" was minted FRESH — the binding-leaf respell never reached node N and the
;; rename silently no-op'd: the false REFERENCE_ERROR this fix kills.)
(defn- id-int-suffix [x]
  (when (and (string? x) (str/starts-with? x "@"))
    (when-let [m (re-find #"(\d+)$" x)]
      (parse-long (second m)))))

(defn- classify-tuple [form]
  (cond
    (not (vector? form))
    [:error (str "STRUCTURAL_INVALID: changeset element is not a tuple: " (pr-str form))]
    (and (= 4 (count form)) (#{'RETRACT 'ASSERT} (first form)))
    (let [[verb s p o] form
          triple [(norm-id s) (keyword p) (if (symbol? o) (str o) (norm-id o))]]
      [(if (= verb 'RETRACT) :retract :assert) triple])
    (= 3 (count form))
    (let [[s p o] form]
      [:assert [(norm-id s) (keyword p) (if (symbol? o) (str o) (norm-id o))]])
    :else
    [:error (str "STRUCTURAL_INVALID: tuple wrong arity (" (count form) "): " (pr-str form))]))

(defn parse-changeset [text]
  (let [lines (->> (str/split-lines text)
                   (map str/trim)
                   (remove str/blank?)
                   (remove #(str/starts-with? % ";")))
        all-forms (reduce (fn [acc ln]
                            (let [fs (read-all-forms ln)]
                              (if (= fs ::bad) (reduced ::bad) (into acc fs))))
                          [] lines)]
    (if (= all-forms ::bad)
      {:error "STRUCTURAL_INVALID: unparseable changeset line"}
      (loop [fs all-forms asserts [] retracts []]
        (if (empty? fs)
          (if (and (empty? asserts) (empty? retracts))
            {:error "STRUCTURAL_INVALID: empty changeset (no tuples emitted)"}
            {:asserts asserts :retracts retracts})
          (let [[kind v] (classify-tuple (first fs))]
            (case kind
              :error   {:error v}
              :assert  (recur (rest fs) (conj asserts v) retracts)
              :retract (recur (rest fs) asserts (conj retracts v)))))))))

;; engine dump -> {id {pred obj}} keeping INTEGER ids; drop srcloc preds.
(defn props-from-dump [lines]
  (reduce
   (fn [m line]
     (if-not (str/starts-with? line "[")
       m
       (let [rdr (java.io.PushbackReader. (java.io.StringReader. line))]
         (loop [m m]
           (let [form (try (edn/read {:eof ::eof} rdr) (catch Exception _ ::eof))]
             (if (or (= form ::eof) (not (vector? form)) (not= 3 (count form)))
               m
               (let [[id pred obj] form
                     pk (if (keyword? pred) (name pred) (str pred))]
                 (if (#{"line" "col" "pos" "span" "child"} pk)
                   (recur m)
                   (recur (update m id (fnil assoc {}) pk obj))))))))))
   {} lines))

(defn render-engine-dump [current changeset module-file]
  (let [cur-lines (or (:lines current) [])
        cur-props (props-from-dump cur-lines)
        ;; current max integer id (existing nodes are ints)
        cur-max (reduce (fn [mx id] (max mx (if (int? id) id 0))) 0 (keys cur-props))
        all-tuples (concat (:asserts changeset) (:retracts changeset))
        existing-id? (fn [n] (contains? cur-props n))
        ;; every "@..."-string id mentioned (subject or @-valued object)
        all-at-ids (->> all-tuples
                        (mapcat (fn [[s _ o]] [s (when (and (string? o) (str/starts-with? o "@")) o)]))
                        (remove nil?)
                        (filter #(and (string? %) (str/starts-with? % "@")))
                        distinct)
        ;; an "@m#N" whose N names an EXISTING node addresses that node (the prompt spells
        ;; EVERY node "@m#N", incl. existing ones it asks the model to edit). Only an "@id"
        ;; whose number is NOT an existing node is a genuine fresh mint (1B net-new subtree).
        fresh-model-ids (->> all-at-ids
                             (remove (fn [aid]
                                       (when-let [n (id-int-suffix aid)] (existing-id? n))))
                             distinct)
        next-id (atom cur-max)
        model->eng (reduce (fn [m mid] (assoc m mid (swap! next-id inc))) {} fresh-model-ids)
        resolve (fn [x]
                  (cond
                    (and (string? x) (str/starts-with? x "@"))
                    (let [n (id-int-suffix x)]
                      (cond
                        ;; existing node addressed by its "@m#N" spelling -> the integer node
                        (and n (existing-id? n)) n
                        ;; genuinely fresh "@id" -> its minted integer
                        (model->eng x) (model->eng x)
                        :else
                        (throw (ex-info (str "REFERENCE_ERROR: @id not in current state or changeset: " x) {}))))
                    (int? x) x
                    (and (string? x) (re-matches #"\d+" x)) (parse-long x)
                    :else x))]
    (try
      (let [props (atom cur-props)]
        ;; retracts first (supersede): remove the predicate only if its value matches
        (doseq [[s p o] (:retracts changeset)]
          (let [eid (resolve s) pk (name p)
                ov (if (and (string? o) (str/starts-with? o "@")) (resolve o) o)]
            (when (= (get-in @props [eid pk]) ov)
              (swap! props update eid dissoc pk))))
        ;; asserts (mint/overwrite)
        (doseq [[s p o] (:asserts changeset)]
          (let [eid (resolve s) pk (name p)
                ov (if (and (string? o) (str/starts-with? o "@")) (resolve o) o)]
            (swap! props update eid (fnil assoc {}) pk ov)))
        ;; bound_to RESOLUTION (reference-follows-identity). The frozen M2 prompt teaches
        ;; the surgical rename: re-spell ONE binding leaf + emit `[use :bound_to def]` and
        ;; "the uses render for free". But the renderer (`--build-edn`) keys each leaf off
        ;; its OWN :v and IGNORES bound_to (verified), so a single-leaf respell leaves the
        ;; uses dangling -> the gate (runtime grade) would falsely fail M2. We honor the
        ;; identity edge HERE (the sanctioned EDN->claims harness translation, R3): for
        ;; each `[use :bound_to def]`, propagate def's CURRENT :v onto the use leaf, then
        ;; drop the bound_to (renderer-irrelevant). This makes M2's taught rename actually
        ;; reach the gate as a real reference-follows edit. (Explicit re-spell-every-leaf
        ;; also works and needs no bound_to.)
        (doseq [[use-id _ def-id] (filter (fn [[_ p _]] (= :bound_to p)) (:asserts changeset))]
          (let [u (resolve use-id) d (resolve def-id)
                dv (get-in @props [d "v"])]
            (when dv
              (swap! props update u (fnil assoc {}) "v" dv)
              (swap! props update u dissoc "bound_to"))))
        ;; re-attach any fresh top-level list form (not the file wrapper, not already a
        ;; child) to the beagle-file wrapper as the next ordered child (1B body subtree).
        (let [p @props
              wrapper (some (fn [[id h]]
                              (when (and (= "list" (get h "kind"))
                                         (= "beagle-file" (get-in p [(get h "f0") "v"])))
                                id))
                            p)
              child-ids (set (for [[_ h] p [pred o] h :when (re-matches #"f\d+.*" pred)] o))
              wrapper-h (get p wrapper)
              used-idxs (->> (keys wrapper-h)
                             (keep #(when-let [mm (re-matches #"f(\d+)" %)] (parse-long (second mm))))
                             (into #{}))
              next-slot (atom (if (empty? used-idxs) 0 (inc (apply max used-idxs))))
              new-forms (for [[id h] p
                              :when (and (= "list" (get h "kind"))
                                         (not= id wrapper)
                                         (not (child-ids id))
                                         (string? id))]  ; only freshly-minted @-ids (now ints? no — model->eng vals are ints)
                          id)]
          ;; NB: fresh ids became ints via model->eng; treat any list node neither wrapper
          ;; nor referenced as a new top-level form.
          (let [new-forms2 (for [[id h] @props
                                 :when (and (= "list" (get h "kind"))
                                            (not= id wrapper)
                                            (not (child-ids id)))]
                             id)]
            (doseq [fid (sort-by str new-forms2)]
              (swap! props assoc-in [wrapper (str "f" @next-slot)] fid)
              (swap! next-slot inc)))
          (let [final @props
                idkey (fn [id] (if (int? id) id (str id)))
                lines (concat
                       [(str "@file " (or module-file "mod.bclj"))]
                       (for [id (sort-by idkey (keys final))
                             [pred o] (sort-by key (get final id))
                             :let [line (cond
                                          (= pred "kind") (format "[%s \"kind\" %s]" id (pr-str (str o)))
                                          (= pred "v") (format "[%s \"v\" %s]" id (pr-str (str o)))
                                          (re-matches #"f\d+.*" pred) (format "[%s %s %s]" id (pr-str pred) o)
                                          (#{"bound_to"} pred) nil
                                          :else (format "[%s %s %s]" id (pr-str pred) o))]
                             :when line]
                         line))]
            {:lines (vec lines) :file (or module-file "mod.bclj")})))
      (catch clojure.lang.ExceptionInfo e
        {:error (.getMessage e)}))))

(defn apply-m2 [current model-text module-file]
  (let [cs (parse-changeset model-text)]
    (if (:error cs) cs (render-engine-dump current cs module-file))))
