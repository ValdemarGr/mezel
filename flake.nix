{
  description = "Shell for dev";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { flake-utils, self, nixpkgs, ... }: 
  let
    system = flake-utils.lib.system.x86_64-linux;
    pkgs = nixpkgs.legacyPackages.${system};
    bazel-wrapper = pkgs.writeShellScriptBin "bazel" ''
      exec env --unset=USE_BAZEL_VERSION ${pkgs.bazelisk}/bin/bazelisk "$@"
    '';
    bazel-fhs = pkgs.buildFHSEnv {
      name = "bazel";
      runScript = "bazel";
      targetPkgs = pkgs: [
        bazel-wrapper
        pkgs.zlib.dev
      ];
      unsharePid = false;
      unshareUser = false;
      unshareIpc = false;
      unshareNet = false;
      unshareUts = false;
      unshareCgroup = false;
    };
    bazel-watcher = pkgs.writeShellScriptBin "ibazel" ''
    exec ${pkgs.bazel-watcher}/bin/ibazel -bazel_path=${bazel-fhs}/bin/bazel "$@"
    '';
    fmtall = pkgs.writeShellScriptBin "fmtall" ''
    scalafmt src && buildifier -r src && buildifier -lint fix src/**/*
    '';
  in
  {
    devShells.${system}.default = pkgs.mkShell {
      name = "mazel-dev";
      nativeBuildInputs = [ 
        bazel-fhs 
        pkgs.jdk11
        pkgs.scalafmt
        pkgs.zsh
        pkgs.sbt
        pkgs.graalvm-ce
        bazel-watcher
        fmtall
      ];
    };
  };
}
