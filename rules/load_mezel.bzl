load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.1.2"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "1ec2b27c263b682ec1ffd66da5c1db5d6e2f054d4e9ea8aaa9752e50a1f0b22d",
  )
