load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.2.0"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "7d6c0acf2d0bf73d25cf216503eb0dca87c0cb6a0c87e0dbe2190a2cde1e61eb",
  )
