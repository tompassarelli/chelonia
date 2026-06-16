(ns chelonia.cnf)

(defn new-store []
  (atom {:next-id 0 :next-seq 0 :supersedes-pred nil :objects {} :values {} :val-intern {} :claims {} :tx-of {} :txs {} :superseded {} :idx-by-l {} :idx-by-p {} :idx-by-r {} :idx-by-lp {} :idx-by-pr {}}))

(defn fresh-id! [ctx]
  (:next-id (swap! ctx update :next-id inc)))

(defn entity! [ctx]
  (let [id (fresh-id! ctx)]
  (swap! ctx update :objects assoc id true)
  id))

(defn value! [ctx v]
  (let [vi (:val-intern (deref ctx))]
  (if (contains? vi v) (get vi v) (let [id (fresh-id! ctx)]
  (swap! ctx update :objects assoc id true)
  (swap! ctx update :values assoc id v)
  (swap! ctx update :val-intern assoc v id)
  id))))

(defn ^Boolean value-object? [ctx id]
  (contains? (:values (deref ctx)) id))

(defn literal [ctx id]
  (get (:values (deref ctx)) id))

(defn value-id [ctx v]
  (get (:val-intern (deref ctx)) v))

(defn begin-tx! [ctx agent]
  (let [tx (fresh-id! ctx)
   seq (:next-seq (swap! ctx update :next-seq inc))]
  (swap! ctx update :txs assoc tx {:seq seq :agent agent})
  tx))

(defn tx-seq [ctx tx]
  (let [m (get (:txs (deref ctx)) tx)]
  (if (some? m) (:seq m) 0)))

(defn set-supersedes-pred! [ctx pid]
  (swap! ctx assoc :supersedes-pred pid))

(defn- index-claim! [ctx cid l p r]
  (swap! ctx update :idx-by-l update l (fn [o] (conj (or o []) cid)))
  (swap! ctx update :idx-by-p update p (fn [o] (conj (or o []) cid)))
  (swap! ctx update :idx-by-r update r (fn [o] (conj (or o []) cid)))
  (swap! ctx update :idx-by-lp update [l p] (fn [o] (conj (or o []) cid)))
  (swap! ctx update :idx-by-pr update [p r] (fn [o] (conj (or o []) cid))))

(defn claim! [ctx l p r tx]
  (let [cid (fresh-id! ctx)]
  (swap! ctx update :objects assoc cid true)
  (swap! ctx update :claims assoc cid {:l l :p p :r r})
  (swap! ctx update :tx-of assoc cid tx)
  (index-claim! ctx cid l p r)
  (if (= p (:supersedes-pred (deref ctx))) (do
  (swap! ctx update :superseded assoc r true)))
  cid))

(defn claim-of [ctx cid]
  (get (:claims (deref ctx)) cid))

(defn claim-tx [ctx cid]
  (get (:tx-of (deref ctx)) cid))

(defn ^Boolean live? [ctx cid]
  (not (contains? (:superseded (deref ctx)) cid)))

(defn- live-only [ctx cids]
  (filterv (fn [c] (live? ctx c)) cids))

(defn by-l [ctx l]
  (live-only ctx (get (:idx-by-l (deref ctx)) l [])))

(defn by-p [ctx p]
  (live-only ctx (get (:idx-by-p (deref ctx)) p [])))

(defn by-r [ctx r]
  (live-only ctx (get (:idx-by-r (deref ctx)) r [])))

(defn by-lp [ctx l p]
  (live-only ctx (get (:idx-by-lp (deref ctx)) [l p] [])))

(defn by-pr [ctx p r]
  (live-only ctx (get (:idx-by-pr (deref ctx)) [p r] [])))

(defn current-claims [ctx]
  (live-only ctx (vec (keys (:claims (deref ctx))))))
