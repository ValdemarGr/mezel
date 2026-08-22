load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")

def load_mezel():
  version = "release-v0.5.2"
  http_jar(
      name = "mezel_binary",
      url = "https://github.com/valdemargr/mezel/releases/download/{}/{}".format(version, "mezel.jar"),
      sha256 = "13c558049a2783fc1608be23ed32580e3fde6cdae393205236f7062d1ed37650",
  )
