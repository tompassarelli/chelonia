#!/usr/bin/env bash
# Build a native fram binary from the beagle-emitted clojure (../out) +
# fram.rt. fram.main is the entry (beagle emits its (:gen-class)).
# Run under GraalVM CE:
#   nix shell nixpkgs#graalvmPackages.graalvm-ce -c ./build.sh
# Deps are minimal (clojure core + java interop only) — no YAML/reflection, so
# no native-image tracing agent step is needed.
set -euo pipefail
cd "$(dirname "$0")"

echo "== [1/2] AOT compile fram.main (emitter must be AOT-clean) =="
rm -rf classes && mkdir -p classes
clojure -M -e "(compile 'fram.main)"

CP="$(clojure -Spath):classes"

echo "== [2/2] native-image =="
time native-image -cp "$CP" \
  --no-fallback \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  -o fram-native \
  fram.main

echo "== done -> $(pwd)/fram-native =="; ls -lh fram-native
