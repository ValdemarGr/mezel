load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.3.0"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "acdd824ffedd3f0ce17f6010e51e7d519fc320ead1f528fccda49a51db31281f",
  )
