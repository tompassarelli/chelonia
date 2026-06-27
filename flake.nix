{
  description = "fram — claim-engine CLIs (babashka-backed; JVM coordinator daemon)";

  # Pinned to the same nixpkgs rev the host system tracks, so `nix build` resolves
  # entirely from the local store (no fetch) on this machine.
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/e8210c649915deed7080033cdbabcc19e40bb899";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f system nixpkgs.legacyPackages.${system});

      mkFram = pkgs:
        let
          # The bin/ scripts resolve HERE = $(dirname $0)/.. and load out/ (compiled
          # Clojure), cnf_coord*.clj, deps.edn, tests/, src/ from there. So we ship the
          # whole runtime tree under libexec and wrap each script with the interpreters
          # on PATH. The CLI + MCP run on babashka against the committed out/ classpath
          # (offline, no maven). The daemon uses JVM clojure + deps.edn.
          runtimePath = pkgs.lib.makeBinPath [
            pkgs.babashka
            pkgs.clojure
            pkgs.jdk
            pkgs.coreutils
            pkgs.bash
            pkgs.gnused
            pkgs.gnugrep
            pkgs.iproute2 # `ss` — fram-up's port probe
            pkgs.git
          ];
        in
        pkgs.stdenv.mkDerivation {
          pname = "fram";
          version = "0-unstable-2026-06-28";
          src = ./.;

          nativeBuildInputs = [ pkgs.makeWrapper pkgs.babashka ];

          dontConfigure = true;
          dontBuild = true;

          installPhase = ''
            runHook preInstall

            mkdir -p $out/libexec/fram $out/bin
            cp -r out bin tests src cnf_coord.clj cnf_coord_daemon.clj deps.edn \
              $out/libexec/fram/
            chmod -R u+w $out/libexec/fram

            # Absolute interpreters for #!/usr/bin/env bash | bb shebangs.
            patchShebangs $out/libexec/fram/bin

            for s in $out/libexec/fram/bin/*; do
              [ -f "$s" ] || continue
              name=$(basename "$s")
              chmod +x "$s"
              makeWrapper "$s" "$out/bin/$name" \
                --prefix PATH : "${runtimePath}"
            done

            runHook postInstall
          '';

          meta = with pkgs.lib; {
            description = "fram claim-engine command-line tools (CLI, MCP server, code-authoring, JVM coordinator daemon)";
            platforms = platforms.unix;
            mainProgram = "fram";
          };
        };
    in
    {
      packages = forAll (system: pkgs: rec {
        fram = mkFram pkgs;
        default = fram;
      });

      apps = forAll (system: pkgs:
        let
          fram = self.packages.${system}.default;
          mkApp = name: { type = "app"; program = "${fram}/bin/${name}"; };
        in
        {
          default = mkApp "fram";
          fram = mkApp "fram";
          fram-daemon = mkApp "fram-daemon";
          fram-mcp = mkApp "fram-mcp";
          fram-primer = mkApp "fram-primer";
          fram-up = mkApp "fram-up";
          fram-code-author = mkApp "fram-code-author";
        });
    };
}
