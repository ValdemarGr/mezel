{
  description = "Shell for dev";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";

    bazel-repo = {
      url = "github:bazelbuild/bazel";
      flake = false;
    };
    rules-scala = {
      url = "github:bazelbuild/rules_scala";
      flake = false;
    };
  };

  outputs = { flake-utils, self, nixpkgs, bazel-repo, rules-scala, ... }: 
  let
    system = flake-utils.lib.system.x86_64-linux;
    pkgs = nixpkgs.legacyPackages.${system};
    lib = nixpkgs.lib;
    list = lib.lists;
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
    bazel-protos = [
      "src/main/java/com/google/devtools/build/lib/packages/metrics/package_load_metrics.proto"
      "src/main/protobuf/action_cache.proto"
      "src/main/protobuf/command_line.proto"
      "src/main/protobuf/option_filters.proto"
      "src/main/protobuf/failure_details.proto"
      "src/main/protobuf/invocation_policy.proto"
      "src/main/protobuf/analysis_v2.proto"
      "src/main/protobuf/build.proto"
      "src/main/java/com/google/devtools/build/lib/buildeventstream/proto/build_event_stream.proto"
    ];
    cps = lib.strings.concatLines 
      (lib.lists.map (path: ''cp ${bazel-repo}/${path} ''${PWD}/src/main/protobuf/${path}'') bazel-protos);
    gen-protobuf = pkgs.writeShellScriptBin "gen-mezel-protobuf" ''
    rm -rf ''${PWD}/src/main/protobuf
    ${pkgs.coreutils}/bin/mkdir -p ''${PWD}/src/main/protobuf/src/main/protobuf
    ${pkgs.coreutils}/bin/mkdir -p ''${PWD}/src/main/protobuf/src/main/java/com/google/devtools/build/lib/buildeventstream/proto
    ${pkgs.coreutils}/bin/mkdir -p ''${PWD}/src/main/protobuf/src/main/java/com/google/devtools/build/lib/packages/metrics
    cp \
      ${rules-scala}/src/protobuf/io/bazel/rules_scala/diagnostics.proto \
      ''${PWD}/src/main/protobuf/src/main/protobuf/diagnostics.proto

    ${cps}
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
