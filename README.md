# Mezel
Mezel is a Scala [BSP](https://build-server-protocol.github.io/) implementation for Bazel.
Mezel acts as a communication layer between Bazel and Scala BSP consumers such as [Metals](https://scalameta.org/metals/).

## Getting started
Make sure that you are using version `f9381414068466b9c74ff7681d204e1eb19c7f80` of the bazel scala rules.
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
```starlark
load("@io_bazel_rules_scala//scala:scala_toolchain.bzl", "scala_toolchain")

scala_toolchain(
    name = "my_toolchain",
    # other settings like your scalac flags...
    enable_diagnostics_report = True,
    enable_semanticdb = True
)

toolchain(
    name = "toolchain",
    toolchain = "my_toolchain",
    toolchain_type = "@io_bazel_rules_scala//scala:toolchain_type"
)
```

Mezel needs an aspect to work, so add the following to your `WORKSPACE` file to get it into scope:
```starlark
mezel_version = "6129b24dc78bb1c04d02dcb93bcb15114a9c479b"  # update this as needed
http_archive(
    name = "mezel",
    sha256 = "1fda9f6663909b102319e99c18db37c09e5cc65c8ecb7e2ba88fd72ff8e6dd03",
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
