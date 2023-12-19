load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.0.14"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "74b1ff521642594903f7c10cf2cc1e41010ce9f84e0b0ed2e8ab1c3db78dde7f",
  )
