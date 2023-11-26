load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "0.0.2"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/bazeltools/bsp4bazel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  )
