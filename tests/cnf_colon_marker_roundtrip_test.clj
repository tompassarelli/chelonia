;; ============================================================================
;; cnf_colon_marker_roundtrip_test.clj — the `:-` type marker survives
;; graph-authoring → render-EDN → beagle --build-edn.
;; ============================================================================
;; REGRESSION GUARD for the keyword-leaf encode mismatch in mint-datum!.
;;
;; Beagle reads .bclj via Racket's reader, which has NO keyword syntax: a `:foo`
;; token (and the `:-` inline TYPE MARKER specifically) comes back as the SYMBOL
;; |:foo| / |:-| — claims-roundtrip emits it as kind="symbol", v=":-" (colon
;; RETAINED). The authoring spec, by contrast, is parsed by clojure.edn where `:-`
;; IS a keyword, so mint-datum! USED to mint it as kind="keyword", v="-" (colon
;; stripped). beagle's decoder then rebuilt that as the Clojure keyword `:-`, which
;; renders as `#:-` — `(def base #:- String "Howdy")` — and the build REJECTS it.
;;
;; Root fix (chartroom/src/resolve.clj mint-datum!): a keyword datum mints as a
;; SYMBOL leaf with the colon retained, matching beagle's reader convention so the
;; whole graph→EDN→beagle path round-trips the `:-` marker intact.
;;
;; PROVES:
;;   A  mint-datum! of `:-` produces kind="symbol" v=":-" (NOT keyword) — the
;;      encode invariant at the unit level.
;;   B  a typed def authored through the REAL verb (verb-upsert-form!), rendered to
;;      EDN via extract-file!, builds via beagle --build-edn with 0 errors AND the
;;      type annotation survives (^String reaches the emitted Clojure).
;;
;;   bb -cp out tests/cnf_colon_marker_roundtrip_test.clj   (from the repo root)
;; Needs: racket + bb + chartroom/src/resolve.clj + beagle (claims-roundtrip.rkt +
;; beagle-build-all). Skips with a clear message if a beagle prereq is missing.
;; SAFE: /tmp work dir, in-process; no daemon, no socket, no canonical log touched.
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.java.io :as io] '[babashka.process :as proc])

(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def roundtrip-rkt (or (System/getenv "FRAM_ROUNDTRIP") (str beagle-home "/beagle-lib/private/claims-roundtrip.rkt")))
(def build-all (or (System/getenv "FRAM_BUILD_ALL") (str beagle-home "/bin/beagle-build-all")))

(doseq [[p label] [[(str root "/chartroom/src/resolve.clj") "chartroom resolve.clj"]
                   [roundtrip-rkt "claims-roundtrip.rkt"]
                   [build-all "beagle-build-all"]]]
  (when-not (.exists (io/file p))
    (println "SKIP — missing prerequisite:" label "(" p ")") (System/exit 0)))

(load-file (str root "/chartroom/src/resolve.clj"))

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))

(def work (str (System/getProperty "java.io.tmpdir") "/colon-marker-rt-" (System/nanoTime)))
(.mkdirs (io/file work))

;; --- A: the unit invariant — mint-datum! of `:-` is a SYMBOL leaf, colon kept ---
;; Mint a bare `:-` and a real keyword `:enable` into a fresh bound store, then read
;; back each leaf's kind/v. (resolve-edn! over an EMPTY corpus just sets up the bound
;; store; the body mints into it and asserts.)
(resolve/resolve-edn! []
  (fn []
    (let [marker (resolve/mint-datum! "demo" (symbol ":-"))     ; the type marker token
          enable (resolve/mint-datum! "demo" :enable)]          ; an ordinary keyword
      (chk "A1: `:-` mints as kind=symbol (not keyword)"
           (= "symbol" (resolve/pred-val marker "kind")))
      (chk "A2: `:-` leaf retains the colon (v == \":-\")"
           (= ":-" (resolve/pred-val marker "v")))
      ;; a real keyword round-trips through beagle the same way: symbol leaf, colon kept,
      ;; so beagle re-reads it as the `:enable` token (its .bclj reader has no keyword kind).
      (chk "A3: an ordinary keyword `:enable` also mints as kind=symbol, colon kept"
           (and (= "symbol" (resolve/pred-val enable "kind"))
                (= ":enable" (resolve/pred-val enable "v")))))))

;; --- B: end-to-end — author typed defs → render EDN → beagle --build-edn ------
;; Seed a minimal module (text), emit-edn it so resolve/load-edn can ingest the
;; wrapper, then author two typed defs through the REAL authoring verb, project to
;; EDN via extract-file!, and build that EDN with beagle. This is the exact path the
;; daemon's :render op feeds to --build-edn, minus the socket.
(def seed (str work "/demo.bclj"))
(spit seed "#lang beagle/clj\n(def seed-marker :- Int 0)\n")
(def seed-edn (str work "/demo.edn"))
(def emit-r (proc/sh {:out :string :err :string} "racket" roundtrip-rkt "--emit-edn" seed))
(if-not (zero? (:exit emit-r))
  (chk "B0: emit-edn seed module" false)
  (do
    (spit seed-edn (:out emit-r))
    (def out-edn (str work "/rendered.edn"))
    (resolve/resolve-edn! [seed-edn]
      (fn []
        (binding [resolve/*reject!* (fn [code] (throw (ex-info (str "verb rejected " code) {})))]
          (resolve/verb-upsert-form! "demo" '(def base :- String "Howdy"))
          (resolve/verb-upsert-form! "demo" '(defn greet [who :- String] :- String (str base ", " who "!"))))
        (resolve/extract-file! (first resolve/srcs) out-edn)))
    (let [edn (slurp out-edn)
          ;; the render must NOT carry any keyword-kind leaf (that was the bug); the `:-`
          ;; markers are present and spelled with the colon — exactly the text-path shape.
          no-keyword-leaf (not (str/includes? edn "\"kind\" \"keyword\""))
          colon-markers   (count (re-seq #"\"v\" \":-\"" edn))]
      (chk "B1: render-EDN carries NO keyword-kind leaf (the mint mismatch is gone)" no-keyword-leaf)
      (chk "B2: render-EDN spells the `:-` markers with the colon (>=3 occurrences)" (>= colon-markers 3))
      (let [br (proc/sh {:out :string :err :string} build-all out-edn "--build-edn")
            blob (str (:out br) (:err br))]
        (chk "B3: beagle --build-edn of the render-EDN builds 0 errors"
             (re-find #"\b1 built, 0 error\(s\)" blob))
        ;; and the build emits to a known out dir so we can confirm the TYPE survived
        (let [bout (str work "/build")]
          (.mkdirs (io/file bout))
          (proc/sh {:out :string :err :string} build-all out-edn "--build-edn" "--out" bout)
          (let [emitted (->> (file-seq (io/file bout)) (filter #(.isFile %))
                             (map slurp) (str/join "\n"))]
            (chk "B4: the `:- String` annotation survives to the emitted Clojure (^String)"
                 (str/includes? emitted "^String"))))))))

;; --- verdict ----------------------------------------------------------------
(println "\n=== `:-` type marker round-trips: graph-author → render-EDN → --build-edn ===")
(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (do (println "\nPASS —" (count cs) "/" (count cs) ": the `:-` marker survives the authoring→EDN→beagle path intact.")
        (System/exit 0))
    (do (println "\nFAIL —" (count fails) "of" (count cs) "checks failed.") (System/exit 1))))
