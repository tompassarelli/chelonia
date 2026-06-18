#!/usr/bin/env bb
;; roundtrip_fram — load reader-level claims into a real Fram store, then
;; re-extract them FROM the store. Proves the program persists through the
;; engine (not just an in-memory map): source -> claims -> Fram -> claims.
;;
;;   racket .../claims-roundtrip.rkt --emit-edn FILE > A.edn
;;   bb -cp ~/code/fram/out src/roundtrip_fram.clj A.edn > B.edn
;;   racket .../claims-roundtrip.rkt --verify B.edn FILE
(ns roundtrip-fram
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fram.cnf :as c]))

(def edn-path (first *command-line-args*))

(def ctx (c/new-store))
(def tx  (c/begin-tx! ctx "code"))
(def lid->ent (atom {}))
(defn ent [lid] (or (@lid->ent lid)
                    (let [e (c/entity! ctx)] (swap! lid->ent assoc lid e) e)))

;; load: obj that is an int is a node-ref (-> entity); a string is a leaf value.
(doseq [line (str/split-lines (slurp edn-path))
        :when (str/starts-with? line "[")]
  (let [[s p o] (edn/read-string line)
        L (ent s)
        P (c/value! ctx p)
        R (if (integer? o) (ent o) (c/value! ctx o))]
    (c/claim! ctx L P R tx)))

(binding [*out* *err*]
  (println "loaded" (count (c/current-claims ctx)) "claims into a Fram store"))

;; re-extract every live claim straight from the store, back to EDN triples.
;; value-object? distinguishes a leaf value (string) from a node entity (int ref).
(doseq [cid (c/current-claims ctx)]
  (let [cl (c/claim-of ctx cid)
        l (:l cl) p (:p cl) r (:r cl)
        ps (c/literal ctx p)]
    (if (c/value-object? ctx r)
      (println (str "[" l " " (pr-str ps) " " (pr-str (c/literal ctx r)) "]"))
      (println (str "[" l " " (pr-str ps) " " r "]")))))
