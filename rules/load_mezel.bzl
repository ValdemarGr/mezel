load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.0.21"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "697a4562cba9424f8704bdf10a34e24eb34e5d8f84521437587115b1bc02a99f",
  )
