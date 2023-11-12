{
  description = "Shell for dev";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";

    bazel-repo = {
      url = "github:bazelbuild/bazel";
      flake = false;
    };
  };

  outputs = { flake-utils, self, nixpkgs, bazel-repo, ... }: 
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
    gen-protobuf = pkgs.writeShellScriptBin "gen-mezel-protobuf" ''
    ${pkgs.coreutils}/bin/mkdir -p ''${PWD}/src/main/protobuf/src/main/protobuf
    ${pkgs.wget}/bin/wget -O ''${PWD}/src/main/protobuf/src/main/protobuf/build.proto https://raw.githubusercontent.com/bazelbuild/bazel/f906d02543f83d9aee914f72bf89e51c293c2506/src/main/protobuf/build.proto
    ${pkgs.wget}/bin/wget -O ''${PWD}/src/main/protobuf/src/main/protobuf/analysis_v2.proto https://raw.githubusercontent.com/bazelbuild/bazel/f906d02543f83d9aee914f72bf89e51c293c2506/src/main/protobuf/analysis_v2.proto
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
        gen-protobuf
      ];
    };
  };
}
