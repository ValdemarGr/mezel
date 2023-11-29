load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "v0.0.4"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "899dc95c99d37c729af134ea571382b899fcb714fd1c2e6a784e42b859c8189f"
  )
