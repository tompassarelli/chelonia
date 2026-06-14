#!/usr/bin/env bash
# Build a native chelonia binary from the beagle-emitted clojure (../out) +
# chelonia.rt. chelonia.main is the entry (beagle emits its (:gen-class)).
# Run under GraalVM CE:
#   nix shell nixpkgs#graalvmPackages.graalvm-ce -c ./build.sh
# Deps are minimal (clojure core + java interop only) — no YAML/reflection, so
# no native-image tracing agent step is needed.
set -euo pipefail
cd "$(dirname "$0")"

echo "== [1/2] AOT compile chelonia.main (emitter must be AOT-clean) =="
rm -rf classes && mkdir -p classes
clojure -M -e "(compile 'chelonia.main)"

CP="$(clojure -Spath):classes"

echo "== [2/2] native-image =="
time native-image -cp "$CP" \
  --no-fallback \
  --features=clj_easy.graal_build_time.InitClojureClasses \
  -o chelonia-native \
  chelonia.main

echo "== done -> $(pwd)/chelonia-native =="; ls -lh chelonia-native
