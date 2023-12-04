# Mezel
Mezel is a Scala [BSP](https://build-server-protocol.github.io/) implementation for Bazel.
Mezel acts as a communication layer between Bazel and Scala BSP consumers such as [Metals](https://scalameta.org/metals/).

## Getting started
### Scala rules setup
Make sure that you are using version `f9381414068466b9c74ff7681d204e1eb19c7f80` or newer of the bazel scala rules.
Failing to do so will cause issues with generation of diagnostics.
```starlark
rules_scala_version = "f9381414068466b9c74ff7681d204e1eb19c7f80"  # update this as needed

http_archive(
    name = "io_bazel_rules_scala",
    sha256 = "63be9ff9e5788621cbf087f659f4c4e8d2a328e6d77353e455a09d923b653b56",
    strip_prefix = "rules_scala-%s" % rules_scala_version,
    type = "zip",
    url = "https://github.com/bazelbuild/rules_scala/archive/%s.zip" % rules_scala_version,
)
```

Your Scala toolchain needs to be configured to emit semanticdb files and diagnostics.
I recommend having a separate toolchain for your LSP server, so that you can have different settings for it.
```starlark
load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")

scala_toolchain(
    name = "my_lsp_toolchain",
    enable_diagnostics_report = True,
    enable_semanticdb = True
    # other settings...
    # for the best editor experince I recommend not having fatal warnings and
    # not having 'strict_deps_mode' and 'unused_dependency_checker_mode' set to error
)

toolchain(
    name = "lsp_toolchain",
    toolchain = "my_lsp_toolchain",
    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type"
)
```
Furthermore I suggest omitting the fatal warnings flag when you are editing your code, since downstream targets won't build if their dependencies don't compile.
```starlark
# flags.bzl
_flags = [
  "-encoding",
  "UTF-8",
  "-Wconf:cat=other-implicit-type:s",
  "-Wdead-code",
  "-Wextra-implicit",
  "-Wunused:explicits"
  # and so on...
]
flags = _flags + select({
  "//my_config:no_fatal_warnings": [],
  "//conditions:default": ["-Xfatal-warnings"]
})

# myconfig/BUILD.bazel
config_setting(
  name = "no_fatal_warnings",
  define_values = {
    "no_fatal_warnings": "true"
  }
)
```

### Mezel setup
Mezel needs an aspect to work, so add the following to your `WORKSPACE` file to get it into scope:
```starlark
mezel_version = "7a6e5a190a7c18bfc043e324a29cfc3b2342c99a"  # update this as needed
http_archive(
    name = "mezel",
    sha256 = "e4b0c3bc5ef393c0d70b2e2b7e27a1cb88102cc04a9244aea7c1e7ff6d786750",
    strip_prefix = "mezel-%s" % mezel_version,
    type = "zip",
    url = "https://github.com/valdemargr/mezel/archive/%s.zip" % mezel_version,
)
# loads the bsp binary
load("@mezel//rules:load_mezel.bzl", "load_mezel")
load_mezel()
```
The Mezel BSP program will expect the Mezel aspect to be at the label `@mezel//aspects:aspect.bzl`.

Now we need to generate the bsp config:
```bash
bazel run @mezel//rules:gen_bsp_config
```

If you want to specify the folder to create the config in:
```bash
bazel run @mezel//rules:gen_bsp_config /path/to/workspace
```

And that's it. Start your editor and select `Mezel` as your BSP server.
### Configuration
I suggest checking your bsp file into VCS `.bsp/mezel.json` since it'll likely contain custom flags.
The path to the java binary should be stable for every user since it will be relative to your workspace root.

If you want to supply custom flags to Mezel, you can do so by modifying the `mezel.json` file.
To see what flags are available, you can run the binary with the `--help` flag:
```bash
# cat .bsp/mezel.json
# {"argv":["java","-jar","bazel-out/k8-fastbuild/bin/external/mezel/rules/mezel.jar"],"bspVersion":"2.0.0","languages":["scala"],"name":"Mezel","version":"1.0.0"}
java -jar bazel-out/k8-fastbuild/bin/external/mezel/rules/mezel.jar --help
```

For instance, using a custom toolchain and set of configuration options for local development (semanticdb + diagnostics + no fatal warnings):
```json
{"argv":[
  "java", "-jar", "bazel-out/k8-fastbuild/bin/external/mezel/rules/mezel.jar",
  "--build-arg", "--extra_toolchains=//toolchain:lsp",
  "--build-arg", "--define=no_fatal_warnings=true",
  "--aquery-arg", "--extra_toolchains=//toolchain:lsp",
  "--aquery-arg", "--define=no_fatal_warnings=true"
],"bspVersion":"2.0.0","languages":["scala"],"name":"Mezel","version":"1.0.0"}
```

[A bug](https://github.com/bazelbuild/bazel/issues/10653) with bazel build/aquery causes bazel to consider the convinience symlinks it creates to be considered build targets.
To get around this, tell bazel to ignore the symlink when running bazel operations:
```bash
echo "bazel-$(basename $PWD)" >> .bazelignore
```
