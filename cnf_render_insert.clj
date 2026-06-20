;; insert a probe via the CRDT mid-insert verb, committing to a /tmp log, so the real
;; render CLI (bin/fram-render-code) can then render it. SAFE: /tmp copy, in-process.
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")
(def log "/tmp/cnf-render-test.log")
(io/copy (io/file ".fram/code.log") (io/file log))
(boot-flat! log)
(def st (:store @co))
(defn vof [e] (let [Vp (c/value-id st "v")] (some->> (c/by-lp st e Vp) first (c/claim-of st) :r (c/literal st))))
(defn forms-of [wrap]
  (->> (c/by-l st wrap)
       (keep (fn [cid] (let [cl (c/claim-of st cid) k (resolve/ord-parse (c/literal st (:p cl)))] (when k {:key k :child (:r cl)}))))
       (sort-by :key resolve/ord-cmp) vec))
(defn def-name [child]
  (let [kids (->> (c/by-l st child) (keep (fn [cid] (let [k (resolve/ord-parse (c/literal st (:p (c/claim-of st cid))))] (when k [k (c/claim-of st cid)])))) (sort-by first resolve/ord-cmp))]
    (when (>= (count kids) 2) (vof (:r (second (nth kids 1)))))))
(defn head-of [child] (vof (:child (first (forms-of child)))))
(defn wrapper [m]
  (let [NAME (c/value-id st "name") pfx (str "@" m "#")]
    (->> (c/by-p st NAME)
         (keep (fn [cid] (let [nm (c/literal st (:r (c/claim-of st cid)))] (when (and (string? nm) (str/starts-with? nm pfx)) (:l (c/claim-of st cid))))))
         (filter (fn [e] (let [fs (forms-of e)] (and (seq fs) (= "beagle-file" (vof (:child (first fs)))))))) first)))
(def wrap (wrapper "kernel"))
(def pre (forms-of wrap))
(def ai (first (filter (fn [i] (and (< (inc i) (count pre))
                                    (#{"def" "defn" "defn-" "def-" "defonce"} (head-of (:child (nth pre i))))
                                    (def-name (:child (nth pre i))))) (range (count pre)))))
(def anchor-name (def-name (:child (nth pre ai))))
(def after-name (def-name (:child (nth pre (inc ai)))))
(def r (handle {:op :edit-min :spec {:op "insert-form" :module "kernel" :after anchor-name :datum (list 'def 'fram_render_probe 42)}}))
(spit "/tmp/cnf-render-anchor.txt" (str anchor-name "\n" after-name "\n"))
(println "INSERT after" anchor-name "(before" after-name ") ->" (select-keys r [:ok :reject]))
