;; tls_test.clj — engine-terminated mutual TLS on the coordinator.
;; The JVM daemon, given FRAM_TLS_*, REQUIRES + verifies a client cert
;; (SSLServerSocket + setNeedClientAuth). Proves: a trusted-cert client gets
;; through; a plaintext client and a wrong-cert client are rejected. The client
;; side runs here on babashka (client mTLS works on bb).
;;   bb tls_test.clj   (run from the repo root; needs keytool + clojure on PATH)
(require '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

(def td (str (System/getProperty "java.io.tmpdir") "/fram-tls-" (System/nanoTime)))
(.mkdirs (io/file td))
(def PW "framtest")
(def PORT 39921)
(defn path [f] (str td "/" f))
(defn kt [& args] (apply p/shell {:out :string :err :string :continue true} "keytool" args))

;; --- certs: server + client keypairs, cross-trusted truststores; plus a rogue ---
(kt "-genkeypair" "-alias" "server" "-keyalg" "RSA" "-keysize" "2048" "-validity" "1"
    "-dname" "CN=coordinator" "-keystore" (path "server.p12") "-storetype" "PKCS12" "-storepass" PW "-keypass" PW)
(kt "-genkeypair" "-alias" "client" "-keyalg" "RSA" "-keysize" "2048" "-validity" "1"
    "-dname" "CN=gateway" "-keystore" (path "client.p12") "-storetype" "PKCS12" "-storepass" PW "-keypass" PW)
(kt "-genkeypair" "-alias" "rogue" "-keyalg" "RSA" "-keysize" "2048" "-validity" "1"
    "-dname" "CN=rogue" "-keystore" (path "rogue.p12") "-storetype" "PKCS12" "-storepass" PW "-keypass" PW)
(kt "-exportcert" "-alias" "server" "-keystore" (path "server.p12") "-storepass" PW "-file" (path "server.crt"))
(kt "-exportcert" "-alias" "client" "-keystore" (path "client.p12") "-storepass" PW "-file" (path "client.crt"))
;; server trusts the client cert; client trusts the server cert (rogue is in NEITHER)
(kt "-importcert" "-noprompt" "-alias" "client" "-file" (path "client.crt")
    "-keystore" (path "servertrust.p12") "-storetype" "PKCS12" "-storepass" PW)
(kt "-importcert" "-noprompt" "-alias" "server" "-file" (path "server.crt")
    "-keystore" (path "clienttrust.p12") "-storetype" "PKCS12" "-storepass" PW)

;; --- bb client (mutual TLS) ---
(defn ssl-ctx [ks ts]
  (let [pw (.toCharArray PW)
        load (fn [pth] (with-open [in (io/input-stream pth)] (doto (java.security.KeyStore/getInstance "PKCS12") (.load in pw))))
        kmf (doto (javax.net.ssl.KeyManagerFactory/getInstance (javax.net.ssl.KeyManagerFactory/getDefaultAlgorithm)) (.init (load ks) pw))
        tmf (doto (javax.net.ssl.TrustManagerFactory/getInstance (javax.net.ssl.TrustManagerFactory/getDefaultAlgorithm)) (.init (load ts)))]
    (doto (javax.net.ssl.SSLContext/getInstance "TLS") (.init (.getKeyManagers kmf) (.getTrustManagers tmf) nil))))

(defn tls-ask [ks ts]
  (with-open [s (.createSocket (.getSocketFactory (ssl-ctx ks ts)))]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int PORT)) 1500)
    (.setSoTimeout s 3000) (.startHandshake s)
    (let [w (io/writer (.getOutputStream s)) r (io/reader (.getInputStream s))]
      (.write w "{:op :version}\n") (.flush w) (edn/read-string (.readLine r)))))

(defn plain-ask []
  (with-open [s (java.net.Socket.)]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int PORT)) 1500) (.setSoTimeout s 3000)
    (let [w (io/writer (.getOutputStream s)) r (io/reader (.getInputStream s))]
      (.write w "{:op :version}\n") (.flush w) (edn/read-string (.readLine r)))))

(defn rejected? [thunk] (try (not (map? (thunk))) (catch Exception _ true)))

;; --- start the JVM daemon with mTLS on ---
(def log (path "claims.log")); (spit log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"t\"}\n")
(spit log "{:tx 1 :op \"assert\" :l \"@a\" :p \"title\" :r \"A\" :frame \"t\"}\n")
(def proc (p/process ["bin/fram-daemon" (str PORT) log]
                     {:out (io/file (path "d.out")) :err (io/file (path "d.err"))
                      :extra-env {"FRAM_BIND" "127.0.0.1"
                                  "FRAM_TLS_KEYSTORE" (path "server.p12")
                                  "FRAM_TLS_TRUSTSTORE" (path "servertrust.p12")
                                  "FRAM_TLS_PASS" PW}}))
(try
  ;; poll for the mTLS listener (JVM start)
  (loop [i 0 up false]
    (when (and (not up) (< i 60))
      (let [ok (try (map? (tls-ask (path "client.p12") (path "clienttrust.p12"))) (catch Exception _ false))]
        (when-not ok (Thread/sleep 500) (recur (inc i) false)))))
  (chk "mTLS happy path: trusted client cert -> :version"
       (some? (:version (tls-ask (path "client.p12") (path "clienttrust.p12")))))
  (chk "plaintext client is REJECTED (no TLS handshake)" (rejected? plain-ask))
  (chk "wrong/untrusted client cert is REJECTED" (rejected? #(tls-ask (path "rogue.p12") (path "clienttrust.p12"))))
  (finally (p/destroy-tree proc)))

(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram mTLS:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram mTLS:" (count fails) "FAILED")
        (println "--- daemon stderr ---") (println (try (slurp (path "d.err")) (catch Exception _ "")))
        (System/exit 1))))
