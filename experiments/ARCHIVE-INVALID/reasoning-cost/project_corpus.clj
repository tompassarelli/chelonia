#!/usr/bin/env bb
;; ============================================================================
;; project_corpus.clj — the ONE-TIME projection translator (graph pre-pays cost).
;; ============================================================================
;; Reads the per-module emit-edn dumps ([id pred value] triples + an @file header,
;; per-file integer ids) and emits ONE flat coordinator log the daemon boots from:
;; one EDN map per line {:tx N :op "assert" :l "@<module>#<id>" :p "<pred>" :r <r>}.
;;
;; The edge/literal rule is EXACTLY resolve.clj/load-edn:50 — `(if (integer? o)
;; (ent o) (value o))`. An integer object IS a node-id reference (ref-string it as
;; "@<module>#<o>"); anything else is a literal (quote as an EDN string). This is the
;; canonical projection the resolver itself uses, so the daemon's warm store holds
;; the SAME AST the resolver would, and resolve-warm-store! materializes refers_to
;; over real code (owned-resolution validity guarantee (i)).
;;
;; Usage: bb project_corpus.clj <emit-edn-dir> <out-flatlog>
;;   <emit-edn-dir> holds <module>.edn files (one per .bclj module).
;; Prints the line count to stderr.
(require '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io])

(defn module-of [edn-file]
  ;; "kernel.edn" -> "kernel"
  (str/replace (.getName (io/file edn-file)) #"\.edn$" ""))

(defn project-module [edn-path module tx-start]
  "Translate one module's emit-edn dump into flat-log maps. Returns [maps next-tx]."
  (let [lines (->> (slurp edn-path) str/split-lines (remove str/blank?))
        triple-lines (filter #(str/starts-with? % "[") lines)
        nref (fn [id] (str "@" module "#" id))]          ; node ref / subject namespacing
    (loop [ls triple-lines, tx tx-start, acc (transient [])]
      (if (empty? ls)
        [(persistent! acc) tx]
        (let [[sid pred obj] (edn/read-string (first ls))
              subj (nref sid)
              ;; THE canonical edge/literal rule (resolve.clj load-edn:50):
              ;; integer object => node-id reference (edge); else => literal.
              r    (if (integer? obj) (nref obj) (str obj))
              m    {:tx tx :op "assert" :l subj :p (str pred) :r r}]
          (recur (rest ls) (inc tx) (conj! acc m)))))))

(defn -main [& args]
  (let [[edn-dir out-path] args
        edn-files (->> (file-seq (io/file edn-dir))
                       (filter #(str/ends-with? (.getName %) ".edn"))
                       (sort-by #(.getName %)))
        _ (when (empty? edn-files)
            (binding [*out* *err*] (println "no .edn files in" edn-dir)) (System/exit 2))]
    (with-open [w (io/writer out-path)]
      (loop [fs edn-files, tx 1, total 0]
        (if (empty? fs)
          (binding [*out* *err*]
            (println (str "projected " total " lines from " (count edn-files) " modules -> " out-path)))
          (let [f (first fs)
                module (module-of f)
                [maps next-tx] (project-module (.getPath f) module tx)]
            (doseq [m maps] (.write w (str (pr-str m) "\n")))
            (recur (rest fs) next-tx (+ total (count maps)))))))))

(apply -main *command-line-args*)
