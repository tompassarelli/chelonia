(ns fram.cnf-coord
  (:require [fram.types :as t]
            [fram.cnf :as c]
            [fram.schema :as s]
            [clojure.string :as str]
            [fram.rt :as rt]))

(def ^String lease-pred "lease")

(defrecord Coord [store log lock])

(defn coord-store [r] (:store r))

(defn coord-log [r] (:log r))

(defn coord-lock [r] (:lock r))

(defn- append-tx! [^Coord co records]
  (let [lg (:log co)]
  (if (some? lg) (rt/append-records-fsync! lg records) nil)))

(defn live-cids-lp [^Coord co te pid]
  (c/by-lp (:store co) te pid))

(defn- seq-of [^Coord co cid]
  (let [tx (c/claim-tx (:store co) cid)]
  (if (some? tx) (c/tx-seq (:store co) tx) 0)))

(defn base-version [^Coord co te pid]
  (reduce (fn [m cid] (max m (seq-of co cid))) 0 (c/by-lp (:store co) te pid)))

(defn current-seq [^Coord co]
  (c/current-seq (:store co)))

(defn- ^Boolean live-has-r? [^Coord co live target]
  (not (empty? (filterv (fn [cd] (= target (c/claim-r (:store co) cd))) live))))

(defn- ent! [^Coord co tx ^String nm]
  (let [ex (s/resolve-name (:store co) nm)]
  (if (some? ex) ex (let [e (c/entity! (:store co))]
  (s/name! (:store co) e nm tx)
  e))))

(defn- succ-ids [^Coord co pid x]
  (mapv (fn [cd] (let [r (c/claim-r (:store co) cd)]
  (if (some? r) r 0))) (c/by-lp (:store co) x pid)))

(defn- ^Boolean reaches? [^Coord co pid from to]
  (loop [front [from]
   seen #{}]
  (cond
  (empty? front) false
  (= (first front) to) true
  (contains? seen (first front)) (recur (vec (rest front)) seen)
  :else (recur (vec (concat (rest front) (succ-ids co pid (first front)))) (conj seen (first front))))))

(defn ^Coord new-coord! [^String log-path]
  (rt/spit-file log-path "")
  (let [st (c/new-store)
   tx0 (c/begin-tx! st "bootstrap")
   co (->Coord st log-path (rt/new-monitor))]
  (s/setup! st tx0)
  (append-tx! co (c/records-since st 0 tx0))
  co))

(defn ^String register-pred! [^Coord co ^String pname ^String card ^String kind]
  (let [out (rt/with-lock (:lock co) (fn [] (let [st (:store co)
   since (c/next-id st)
   tx (c/begin-tx! st "schema")]
  (s/def-predicate! st pname card kind tx)
  (append-tx! co (c/records-since st since tx))
  pname)))]
  out))

(defn commit! [^Coord co agent ^String te-name ^String pred kind r-spec base]
  (let [out (rt/with-lock (:lock co) (fn [] (let [st (:store co)
   pid (c/value-id st pred)
   te0 (s/resolve-name st te-name)
   tgt0 (if (= kind :link) (s/resolve-name st (str r-spec)) nil)
   vid (if (= kind :assert) (c/value-id st r-spec) nil)
   single (= "single" (s/cardinality st pred))
   bv (if (and (some? te0) (some? pid)) (base-version co te0 pid) 0)
   live (if (and (some? te0) (some? pid)) (c/by-lp st te0 pid) [])]
  (cond
  (and single (> bv base)) {:reject :conflict :version (c/current-seq st)}
  (and (= kind :link) (contains? #{"depends_on" "part_of"} pred) (or (= te-name (str r-spec)) (and (some? te0) (some? tgt0) (some? pid) (reaches? co pid tgt0 te0)))) {:reject [(str pred " cycle")] :version (c/current-seq st)}
  (and (not single) (= kind :link) (some? tgt0) (live-has-r? co live tgt0)) {:ok (c/current-seq st) :idempotent true}
  (and (not single) (= kind :assert) (some? vid) (live-has-r? co live vid)) {:ok (c/current-seq st) :idempotent true}
  :else (let [since (c/next-id st)
   tx (c/begin-tx! st agent)
   te (ent! co tx te-name)]
  (if (= kind :link) (s/link! st te pred (ent! co tx (str r-spec)) tx) (s/assert! st te pred r-spec tx))
  (append-tx! co (c/records-since st since tx))
  {:ok (c/tx-seq st tx)})))))]
  out))

(defn retract! [^Coord co agent ^String te-name ^String pred r-spec base]
  (let [out (rt/with-lock (:lock co) (fn [] (let [st (:store co)
   pid (c/value-id st pred)
   te0 (s/resolve-name st te-name)
   single (= "single" (s/cardinality st pred))]
  (if (and (some? te0) (some? pid)) (let [bv (base-version co te0 pid)]
  (if (and single (> bv base)) {:reject :conflict :version (c/current-seq st)} (let [tgt (if (and (some? r-spec) (str/starts-with? (str r-spec) "@")) (s/resolve-name st (str r-spec)) (c/value-id st r-spec))
   allc (c/by-lp st te0 pid)
   victims (if single allc (filterv (fn [cd] (= tgt (c/claim-r st cd))) allc))]
  (if (empty? victims) {:ok (c/current-seq st)} (let [since (c/next-id st)
   tx (c/begin-tx! st agent)
   sup (c/value! st "cnf-supersedes")]
  (doseq [old victims]
  (c/claim! st old sup old tx))
  (append-tx! co (c/records-since st since tx))
  {:ok (c/tx-seq st tx)}))))) {:ok (c/current-seq st)}))))]
  out))

(defn- ^String lease-subj [^String res]
  (str "@lease:" res))

(defn- ^String encode-lease [^String h exp epoch]
  (str h "|" exp "|" epoch))

(defn- ^String lease-holder [m]
  (:holder m))

(defn- lease-exp [m]
  (:exp m))

(defn- lease-epoch [m]
  (:epoch m))

(defn- decode-lease [v]
  (if (string? v) (let [parts (str/split v #"\|")]
  (if (= 3 (count parts)) {:holder (nth parts 0) :exp (parse-long (nth parts 1)) :epoch (parse-long (nth parts 2))} nil)) nil))

(defn- read-lease [^Coord co ^String res]
  (let [st (:store co)
   te (s/resolve-name st (lease-subj res))
   pid (c/value-id st lease-pred)]
  (if (and (some? te) (some? pid)) (let [cid (first (c/by-lp st te pid))]
  (if (some? cid) (let [rid (c/claim-r st cid)]
  (if (some? rid) (decode-lease (c/literal st rid)) nil)) nil)) nil)))

(defn acquire-lease! [^Coord co ^String holder ^String res ttl-ms]
  (let [out (rt/with-lock (:lock co) (fn [] (let [st (:store co)
   now (rt/now-ms)
   cur (read-lease co res)]
  (if (and (some? cur) (> (lease-exp cur) now) (not (= (lease-holder cur) holder))) {:reject :held :holder (lease-holder cur) :exp (lease-exp cur) :version (c/current-seq st)} (let [epoch (+ 1 (if (some? cur) (lease-epoch cur) 0))
   exp (+ now ttl-ms)
   since (c/next-id st)
   tx (c/begin-tx! st holder)
   te (ent! co tx (lease-subj res))]
  (if (not (= "single" (s/cardinality st lease-pred))) (s/def-predicate! st lease-pred "single" "literal" tx) nil)
  (s/assert! st te lease-pred (encode-lease holder exp epoch) tx)
  (append-tx! co (c/records-since st since tx))
  {:ok (c/tx-seq st tx) :holder holder :exp exp :epoch epoch})))))]
  out))

(defn release-lease! [^Coord co ^String holder ^String res]
  (let [out (rt/with-lock (:lock co) (fn [] (let [cur (read-lease co res)]
  (if (and (some? cur) (= (lease-holder cur) holder)) (retract! co holder (lease-subj res) lease-pred nil (current-seq co)) {:ok (current-seq co) :noop true}))))]
  out))

(defn ^Boolean fence-ok? [^Coord co ^String res ^String holder epoch]
  (let [out (rt/with-lock (:lock co) (fn [] (let [cur (read-lease co res)]
  (and (some? cur) (= (lease-holder cur) holder) (= epoch (lease-epoch cur)) (> (lease-exp cur) (rt/now-ms))))))]
  out))

(defn- read-records [^String path]
  (filterv (fn [r] (some? r)) (mapv (fn [ln] (rt/parse-edn ln)) (rt/read-lines path))))

(defn- committed-records [recs]
  (loop [rs recs
   buf []
   out []]
  (if (empty? rs) out (let [r (first rs)]
  (if (= (:k r) :commit) (recur (vec (rest rs)) [] (vec (concat out buf))) (recur (vec (rest rs)) (vec (concat buf [r])) out))))))

(defn- recs-of [recs k]
  (filterv (fn [r] (= (:k r) k)) recs))

(defn- assemble-dump [recs]
  (let [vrecs (recs-of recs :value)
   crecs (recs-of recs :claim)
   trecs (recs-of recs :tx)
   erecs (recs-of recs :entity)
   vals (mapv (fn [r] [(:id r) (:v r)]) vrecs)
   claims (mapv (fn [r] [(:cid r) {:l (:l r) :p (:p r) :r (:r r)}]) crecs)
   tx-of (mapv (fn [r] [(:cid r) (:tx r)]) crecs)
   txs (mapv (fn [r] [(:tx r) {:seq (:seq r) :agent (:agent r)}]) trecs)
   val-ids (mapv (fn [r] (:id r)) vrecs)
   ent-ids (mapv (fn [r] (:id r)) erecs)
   cl-ids (mapv (fn [r] (:cid r)) crecs)
   tx-ids (mapv (fn [r] (:tx r)) trecs)
   sup (reduce (fn [acc r] (if (= (:v r) "cnf-supersedes") (:id r) acc)) nil vrecs)
   superd (mapv (fn [r] (:r r)) (filterv (fn [r] (= (:p r) sup)) crecs))
   all-id (vec (concat val-ids ent-ids cl-ids tx-ids))
   all-sq (mapv (fn [r] (:seq r)) trecs)]
  {:next-id (reduce (fn [m x] (let [xi x]
  (max m xi))) 0 all-id) :next-seq (reduce (fn [m x] (let [xi x]
  (max m xi))) 0 all-sq) :supersedes-pred sup :objects (vec (concat val-ids ent-ids cl-ids)) :values vals :claims claims :tx-of tx-of :txs txs :superseded superd}))

(defn replay! [^String path]
  (let [st (c/new-store)]
  (c/load-store! st (assemble-dump (committed-records (read-records path))))
  st))

(defn dump-log! [st ^String path]
  (rt/spit-file path "")
  (rt/append-records-fsync! path (vec (concat (c/all-records st) [{:k :commit :tx :migration}]))))

(defn live-triples [st]
  (reduce (fn [acc cid] (conj acc (c/claim-lpr st cid))) #{} (c/current-claims st)))
