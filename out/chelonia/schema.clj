(ns chelonia.schema
  (:require [chelonia.cnf :as c]))

(defn setup! [ctx tx]
  (let [name-p (c/value! ctx "name")
   sup-p (c/value! ctx "cnf-supersedes")
   card-p (c/value! ctx "cardinality")
   kind-p (c/value! ctx "value_kind")]
  (c/set-supersedes-pred! ctx sup-p)
  (c/claim! ctx name-p card-p (c/value! ctx "single") tx)
  (c/claim! ctx card-p card-p (c/value! ctx "single") tx)
  (c/claim! ctx kind-p card-p (c/value! ctx "single") tx)
  (c/claim! ctx sup-p card-p (c/value! ctx "multi") tx)
  ctx))

(defn cardinality [ctx pname]
  (let [pid (c/value-id ctx pname)
   cs (c/by-lp ctx pid (c/value-id ctx "cardinality"))]
  (if (empty? cs) "multi" (c/literal ctx (:r (c/claim-of ctx (first cs)))))))

(defn- replace! [ctx subj pid new-cid tx]
  (let [sup (c/value! ctx "cnf-supersedes")]
  (doseq [old (c/by-lp ctx subj pid)]
  (if (not (= old new-cid)) (do
  (c/claim! ctx new-cid sup old tx))))))

(defn assert! [ctx subj pname v tx]
  (let [pid (c/value! ctx pname)
   new-cid (c/claim! ctx subj pid (c/value! ctx v) tx)]
  (if (= "single" (cardinality ctx pname)) (do
  (replace! ctx subj pid new-cid tx)))
  new-cid))

(defn link! [ctx subj pname target tx]
  (let [pid (c/value! ctx pname)
   new-cid (c/claim! ctx subj pid target tx)]
  (if (= "single" (cardinality ctx pname)) (do
  (replace! ctx subj pid new-cid tx)))
  new-cid))

(defn lookup-all [ctx subj pname]
  (let [pid (c/value-id ctx pname)]
  (mapv (fn [cid] (let [r (:r (c/claim-of ctx cid))]
  (if (c/value-object? ctx r) (c/literal ctx r) r))) (c/by-lp ctx subj pid))))

(defn lookup [ctx subj pname]
  (let [all (lookup-all ctx subj pname)]
  (if (empty? all) nil (first all))))

(defn find-by [ctx pname v]
  (let [pid (c/value-id ctx pname)
   vid (c/value-id ctx v)]
  (mapv (fn [cid] (:l (c/claim-of ctx cid))) (c/by-pr ctx pid vid))))

(defn def-predicate! [ctx pname card kind tx]
  (let [pid (c/value! ctx pname)]
  (assert! ctx pid "cardinality" card tx)
  (assert! ctx pid "value_kind" kind tx)
  pid))

(defn name! [ctx subj nm tx]
  (assert! ctx subj "name" nm tx))

(defn name-of [ctx subj]
  (lookup ctx subj "name"))

(defn resolve-name [ctx nm]
  (let [ls (find-by ctx "name" nm)]
  (if (empty? ls) nil (first ls))))
