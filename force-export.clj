;; force-export.clj — regenerate threads/ from the authoritative log, skipping the
;; symmetric export guard (which refuses unless files already == log). Replicates
;; fram.main/cmd-export's body (main.bclj:80-87). Run: cd ~/code/fram && bb -cp out force-export.clj
(require '[fram.kernel :as k] '[fram.fold :as fold] '[fram.export :as exp] '[fram.rt :as rt])
(def log "/home/tom/.local/state/tern/claims.log")
(def out "/home/tom/.local/state/tern/threads")
(def log-claims (:claims (fold/fold (rt/read-log log))))
(def idx (k/build-index log-claims))
(def tes (k/thread-ids-i idx))
(rt/ensure-dir out)
(doseq [te tes]
  (let [title (k/one-i idx te "title")
        fname (str (subs te 1) "-" (rt/slugify (if (some? title) title "untitled")) ".md")]
    (rt/spit-file (str out "/" fname) (exp/thread-md log-claims te))))
(println "exported" (count tes) "threads ->" out)
