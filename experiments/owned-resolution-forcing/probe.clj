#!/usr/bin/env bb
;; probe.clj — receipts for the "spelling is not identity" cold open.
;; Pins, against a /tmp copy of honeysql (READ-ONLY corpus; never mutates the original):
;;   (A) clj-kondo's identity vocabulary is spelling+location only (no content-stable id);
;;   (B) the LOCATION-corruption: a 2-line insert above honey.sql.util/str moves its def, so a
;;       persisted location edge silently mis-points at a different binding;
;;   (C) the honest reference counts: distinct PHYSICAL rewrite sites vs the dual-lang dump count
;;       (a .cljc usage is counted once per :lang, so the dump count ~= 2x physical sites).
;;
;; Usage:  bb experiments/owned-resolution-forcing/probe.clj
;; Requires: clojure-lsp on PATH; ~/code/reference/honeysql present.
(require '[clojure.java.shell :refer [sh]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def SRC (str (System/getenv "HOME") "/code/reference/honeysql"))
(def WD  (str "/tmp/orf-probe-" (System/currentTimeMillis)))

(defn dump [root]
  ;; clojure-lsp dump emits EDN on stdout (progress -> stderr). :analysis is keyed by file-URI,
  ;; each value a map with :var-definitions / :var-usages. We flatten across files.
  (let [{:keys [out]} (sh "clojure-lsp" "dump" "--project-root" root)
        a (:analysis (edn/read-string out))]
    {:var-definitions (mapcat :var-definitions (vals a))
     :var-usages      (mapcat :var-usages (vals a))}))

(defn -main []
  (println "## owned-resolution-forcing probe — corpus:" SRC)
  (sh "cp" "-r" SRC WD)
  (sh "rm" "-rf" (str WD "/.clj-kondo/.cache"))
  (let [ana   (dump WD)
        defs  (:var-definitions ana)
        uses  (:var-usages ana)
        ;; (A) identity vocabulary clj-kondo exposes for a var-definition:
        str-def (first (filter #(and (= 'honey.sql.util (:ns %)) (= 'str (:name %))) defs))
        ;; (C) usages that resolve to honey.sql.util/str:
        str-uses (filter #(and (= 'honey.sql.util (:to %)) (= 'str (:name %))) uses)
        by-lang  (frequencies (map :lang uses))
        ;; physical sites: collapse the dual-lang .cljc double-count (same file/row/col, two :lang)
        phys     (distinct (map (juxt :filename :row :col) str-uses))]
    (println "\n(A) clj-kondo var-definition keyset for honey.sql.util/str:")
    (println "   " (sort (keys str-def)))
    (println "    -> identity available: SPELLING (:ns/:name) + LOCATION (:row/:col). No :id/:hash.")
    (println "    str def at :name-row" (:name-row str-def))
    (println "\n(C) reference counts for honey.sql.util/str:")
    (println "    dual-lang dump count (once per :lang):" (count str-uses))
    (println "    DISTINCT PHYSICAL sites (what a rename rewrites / identity re-points):" (count phys))
    (println "    total project var-usages by lang:" by-lang)
    ;; (B) the location corruption: insert 2 lines at top of util.cljc, re-dump, re-find row 8's owner
    (let [util  (str WD "/src/honey/sql/util.cljc")
          lines (str/split-lines (slurp util))
          row   (:name-row str-def)            ; str's def line; splice a 2-line defn right above it
          ins   (str (str/join "\n" (concat (take (dec row) lines)
                                            ["(defn newest-helper [x] x)" ""]
                                            (drop (dec row) lines))) "\n")]
      (spit util ins)
      (sh "rm" "-rf" (str WD "/.clj-kondo/.cache"))
      (let [ana2 (dump WD)
            str2 (first (filter #(and (= 'honey.sql.util (:ns %)) (= 'str (:name %)))
                                (:var-definitions ana2)))
            at-old-row (filter #(and (= 'honey.sql.util (:ns %))
                                     (= (:name-row str-def) (:name-row %)))
                               (:var-definitions ana2))]
        (println "\n(B) LOCATION corruption after a 2-line insert above str:")
        (println "    str moved:" (:name-row str-def) "->" (:name-row str2))
        (println "    a persisted edge keyed on row" (:name-row str-def)
                 "now resolves to:" (mapv :name at-old-row))
        (println "    => a persisted LOCATION edge silently mis-points (corruption, not staleness).")))
    (sh "rm" "-rf" WD)
    (println "\nOK — corpus untouched; /tmp copy removed.")))

(-main)
