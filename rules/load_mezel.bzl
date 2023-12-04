load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "v0.0.6"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "94a66ca99e4cd47edbcffd3c9c2c156483796a90ca0a11a5ebf7f267f1f00e4d"
  )
