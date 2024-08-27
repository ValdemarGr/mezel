# Mezel
Mezel is a Scala [BSP](https://build-server-protocol.github.io/) implementation for Bazel.
Mezel acts as a communication layer between Bazel and Scala BSP consumers such as [Metals](https://scalameta.org/metals/).

Mezel is work-in-progress. I daily drive it with Metals using NeoVim, but any Metals-enabled editor works.
A non-exhaustive list of features that work:
* Semanticdb consumption from Bazel (code navigation).
* External dependencies (from maven for instance, but also locally built and imported jars).
* Presentation compiler support with plugins (type hints and such).
* Caching of semanticdb files (bazel clears the output directory on every build).
* Streamed diagnostics reporting for errors and warnings (diagnostics will appear as they are generated instead of waiting for the build to finish).
* Build change propagation (mezel always builds all targets so you get full diagnostics).
* Logging of all build events with performance traces.
* Build isolation, uses a custom `--output_base` to avoid destroying your build cache.
* Custom build/query flags.
* Semi-automatic configuration generation.
* Minimal build target definitons using ijars over jars.
* Automatic reload on build change.
* IntelliJ support (lightly tested).
* BSP Task cancellation.
* Cancellation of stale builds, saving a file mid-build restarts the build.

Things that I will be working that Mezel doesn't support yet:
* Caching of output jars/ijars from local targets (improves DX on big refactorings since transitive jars will persist through bazel output extermination)
* Support for an add-it-all-to-classpath mode such that you won't need to add a dependency to your BUILD.bazel for it to be available in your IDE?
* Scala3 support [*](https://github.com/ValdemarGr/mezel/issues/12#issuecomment-1845372743)
* Easier way to run commands on the same bazel server as the BSP uses.

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
mezel_version = "52b70adff0e6c81666242624db66b4548a9da12a"
http_archive(
    name = "mezel",
    sha256 = "b8e48667a6b0d98f580a85dd1cc25786f50ae4cc4dc0cefcd561d284fc905602",
    strip_prefix = "mezel-%s" % mezel_version,
    type = "zip",
    url = "https://github.com/valdemargr/mezel/archive/%s.zip" % mezel_version,
)
# loads the bsp binary
load("@mezel//rules:load_mezel.bzl", "load_mezel")
load_mezel()
```
The Mezel BSP program will expect the Mezel aspect to be at the label `@mezel//aspects:aspect.bzl`, so make sure to name the `http_achive` `mezel` like in the example.

Now we have a Mezel binary to run.
Create a config for Metals at `.bsp/mezel.json`:
```json
{"argv":[
  "bazel", "run", "@mezel//rules:mezel_jar", "--"
],"bspVersion":"2.1.0","languages":["scala"],"name":"Mezel","version":"1.0.0"}
```

And that's it. Start your editor and select `Mezel` as your BSP server.

### External dependencies
External dependencies should work but have only been tested with [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external).
When you import external dependencies you must ensure that you fetch their sources, such that there are sources to navigate to on "goto definition" type actions.
For rules_jvm_external you can flag this in your `maven_install` as seen [here](https://github.com/bazelbuild/rules_jvm_external#fetch-source-jars).

### Configuration
I suggest checking your bsp file into VCS `.bsp/mezel.json` so it works for other developers without any configuration.

A configuration example that uses a custom toolchain and set of configuration options for local development (semanticdb + diagnostics + no fatal warnings):
```json
{"argv":[
  "bazel", "run", "@mezel//rules:mezel_jar", "--",
  "--build-arg", "--extra_toolchains=//toolchain:lsp",
  "--build-arg", "--define=no_fatal_warnings=true",
  "--aquery-arg", "--extra_toolchains=//toolchain:lsp",
  "--aquery-arg", "--define=no_fatal_warnings=true"
],"bspVersion":"2.1.0","languages":["scala"],"name":"Mezel","version":"1.0.0"}
```

If you have a custom rcfile, you can also ask bazel to include that:
```json
{"argv":[
  "bazel", "run", "@mezel//rules:mezel_jar", "--",
  "--build-arg", "--bazelrc=.bazelrc.bsp",
  "--aquery-arg", "--bazelrc=.bazelrc.bsp"
],"bspVersion":"2.1.0","languages":["scala"],"name":"Mezel","version":"1.0.0"}
```

[A bug](https://github.com/bazelbuild/bazel/issues/10653) with bazel build/aquery causes bazel to consider the convinience symlinks it creates to be considered build targets.
To get around this, tell bazel to ignore the symlink when running bazel operations:
```bash
echo "bazel-$(basename $PWD)" >> .bazelignore
```

### Help something isn't working!
You can try building the aspect yourself. This will emit information about what the mezel aspect finds in your build.
```bash
bazel build '//...' --aspects '@mezel//aspects:aspect.bzl%mezel_aspect' '--output_groups=bsp_info,bsp_info_deps'
```

### Note for using `nvim-metals`
The default `find_root_dir` function in the `nvim-metals` plugin does not work when files do not occur in the same workspace (goto definition of file in bazel cache).
I suggest using the following configuration:
```lua
local metals_config = require("metals").bare_config()
metals_config.find_root_dir = function ()
  return vim.fn.getcwd()
end
```
Courtesy of [this post](https://github.com/scalameta/nvim-metals/issues/671#issuecomment-2194575956).

### Metals configuration (optional)
For larger projects, some operations can take longer than Metals (by default) will wait for a response before timing out.

Mezel is not supposed to deadlock or become stuck, if this ever occurs it is a bug.

I suggest turning off the reconnection feature of Metals and enabling debug logging:
```
-Dmetals.verbose=true
-Dmetals.askToReconnect=false
-Dmetals.loglevel=debug
-Dmetals.build-server-ping-interval=10h
```
