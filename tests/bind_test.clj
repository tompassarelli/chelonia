;; bind_test.clj — the coordinator's configurable bind (FRAM_BIND). Asserts the two
;; modes from the gateway hand-off: default loopback (unchanged), and FRAM_BIND=0.0.0.0
;; binding all interfaces while local loopback clients keep working + a startup warning.
;;   bb bind_test.clj   (run from the repo root)
(require '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str] '[clojure.java.io :as io])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

(def tmp (str (System/getProperty "java.io.tmpdir") "/fram-bind-test-" (System/nanoTime)))
(.mkdirs (io/file tmp))
(def log (str tmp "/claims.log"))
(spit log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"test\"}\n")
(def PORT-LO 39811)   ; default bind (loopback)
(def PORT-ALL 39812)  ; FRAM_BIND=0.0.0.0

(defn ask [port m]
  (with-open [s (java.net.Socket.)]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 1000)
    (.setSoTimeout s 1000)
    (let [w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream s)))
          r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))]
      (.write w (pr-str m)) (.newLine w) (.flush w)
      (edn/read-string (.readLine r)))))

(defn wait-listening [port tries]
  (loop [t tries]
    (cond
      (zero? t) false
      (try (some? (:version (ask port {:op :version}))) (catch Exception _ false)) true
      :else (do (Thread/sleep 150) (recur (dec t))))))

(defn ss-addr [port]
  (try (let [out (:out (p/shell {:out :string :err :string :continue true} "ss" "-tlnH" (str "sport = :" port)))]
         (str/trim out))
       (catch Exception _ ::no-ss)))

(def errA (str tmp "/a.err"))
(def errB (str tmp "/b.err"))
(def procA (p/process ["bin/fram-daemon" (str PORT-LO) log]  {:out (io/file (str tmp "/a.out")) :err (io/file errA)}))
(def procB (p/process ["bin/fram-daemon" (str PORT-ALL) log] {:out (io/file (str tmp "/b.out")) :err (io/file errB)
                                                              :extra-env {"FRAM_BIND" "0.0.0.0"}}))
(try
  (chk "default daemon is listening (loopback)" (wait-listening PORT-LO 40))
  (chk "FRAM_BIND=0.0.0.0 daemon is listening" (wait-listening PORT-ALL 40))

  ;; loopback clients work in BOTH modes (the recommended-approach guarantee)
  (chk "default: :version answers on 127.0.0.1" (some? (:version (ask PORT-LO {:op :version}))))
  (chk "0.0.0.0: :version STILL answers on 127.0.0.1 (loopback preserved)"
       (some? (:version (ask PORT-ALL {:op :version}))))

  ;; ss bind-address check (skip gracefully if ss unavailable)
  (let [a (ss-addr PORT-LO) b (ss-addr PORT-ALL)]
    (if (or (= a ::no-ss) (= b ::no-ss))
      (do (chk "ss check skipped (ss unavailable)" true)
          (binding [*out* *err*] (println "  (note: ss not available; bind-address check skipped)")))
      (do (chk "default bind address is loopback (127.0.0.1 in ss)" (str/includes? a "127.0.0.1"))
          (chk "FRAM_BIND=0.0.0.0 binds all interfaces (0.0.0.0 / * in ss)"
               (or (str/includes? b "0.0.0.0") (str/includes? b "*"))))))

  ;; warning only on non-loopback
  (Thread/sleep 200)
  (let [ea (slurp errA) eb (slurp errB)]
    (chk "non-loopback logs the UNAUTHENTICATED warning" (str/includes? eb "UNAUTHENTICATED"))
    (chk "default (loopback) logs NO unauthenticated warning" (not (str/includes? ea "UNAUTHENTICATED"))))
  (finally
    (p/destroy-tree procA) (p/destroy-tree procB)))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram bind:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram bind:" (count fails) "FAILED")
        (println "--- a.err ---") (println (try (slurp errA) (catch Exception _ "")))
        (println "--- b.err ---") (println (try (slurp errB) (catch Exception _ "")))
        (System/exit 1))))
