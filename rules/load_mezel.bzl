load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.1.3"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "8ee96229eeeb730f2c89d5f6df049ec216cef42ffb8c9db19340134a7eaec9a2",
  )
