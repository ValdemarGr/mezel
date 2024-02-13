load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.1.0"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "d2ff82248ad8e3a6ffbf727eed25ad393f44b8732d30c3df2e00d832fa245a18",
  )
