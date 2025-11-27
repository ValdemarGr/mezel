load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def _mezel_binary_impl(mctx):
  version = "release-v0.2.12"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "c3d9a80407760f06c07b46ee4bffb6c8061a06490ad6817c8fda3da7e9323d94",
  )

mezel_binary = module_extension(implementation = _mezel_binary_impl)
