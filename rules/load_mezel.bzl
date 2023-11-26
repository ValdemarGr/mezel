load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "v0.0.2"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "1ee3b844dbc65bd7357e90ef5d35075b55a277194728b0fff64732121ccf6d55"
  )
