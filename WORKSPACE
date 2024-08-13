workspace(name = "mezel")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@bazel_tools//tools/build_defs/repo:git.bzl", "new_git_repository", "git_repository")

skylib_version = "1.0.3"

http_archive(
    name = "bazel_skylib",
    sha256 = "1c531376ac7e5a180e0237938a2536de0c54d93f5c278634818e0efc952dd56c",
    type = "tar.gz",
    url = "https://mirror.bazel.build/github.com/bazelbuild/bazel-skylib/releases/download/{}/bazel-skylib-{}.tar.gz".format(skylib_version, skylib_version),
)

rules_proto_commit = "dcf9e47b0df2218ca33e02a1a51803ab3134f42d"
http_archive(
    name = "rules_proto",
    sha256 = "d31d04a8bb1912fbc122bcc7eea49964c9b75c6e091ac3f9deea2bb6a8025a4a",
    strip_prefix = "rules_proto-%s" % rules_proto_commit,
    urls = [
        "https://github.com/bazelbuild/rules_proto/archive/%s.tar.gz" % rules_proto_commit,
    ],
)

load("@rules_proto//proto:repositories.bzl", "rules_proto_dependencies", "rules_proto_toolchains")

rules_proto_dependencies()

rules_proto_toolchains()

# scala
rules_scala_version = "f9381414068466b9c74ff7681d204e1eb19c7f80"  # update this as needed

http_archive(
    name = "io_bazel_rules_scala",
    sha256 = "63be9ff9e5788621cbf087f659f4c4e8d2a328e6d77353e455a09d923b653b56",
    strip_prefix = "rules_scala-%s" % rules_scala_version,
    type = "zip",
    url = "https://github.com/bazelbuild/rules_scala/archive/%s.zip" % rules_scala_version,
)

load("@io_bazel_rules_scala//:scala_config.bzl", "scala_config")

scala_config("2.13.12")
# scala_config("3.3.0")

load("@io_bazel_rules_scala//scala:scala.bzl", "scala_repositories")

register_toolchains("//:toolchain")

scala_repositories()

RULES_JVM_EXTERNAL_TAG = "4.5"
RULES_JVM_EXTERNAL_SHA = "b17d7388feb9bfa7f2fa09031b32707df529f26c91ab9e5d909eb1676badd9a6"

http_archive(
    name = "rules_jvm_external",
    strip_prefix = "rules_jvm_external-%s" % RULES_JVM_EXTERNAL_TAG,
    sha256 = RULES_JVM_EXTERNAL_SHA,
    url = "https://github.com/bazelbuild/rules_jvm_external/archive/%s.zip" % RULES_JVM_EXTERNAL_TAG,
)

load("@rules_jvm_external//:repositories.bzl", "rules_jvm_external_deps")

rules_jvm_external_deps()

load("@rules_jvm_external//:setup.bzl", "rules_jvm_external_setup")

rules_jvm_external_setup()

load("@rules_jvm_external//:defs.bzl", "maven_install")

maven_install(
    artifacts = [
        "org.typelevel:cats-effect_2.13:3.5.2",
        "org.typelevel:kind-projector_2.13.12:0.13.2",
    ],
    repositories = [
        "https://maven.google.com",
        "https://repo1.maven.org/maven2",
    ],
    maven_install_json = "//:maven_install.json",
    fetch_sources = True
)
load("@maven//:defs.bzl", "pinned_maven_install")
pinned_maven_install()

load("//rules:load_mezel.bzl", "load_mezel")
load_mezel()

new_git_repository(
   name = "hxl",
   commit = "4dcca3c131c0d3b35a49252fa6d26dda7bc17abf",
   shallow_since = "1710012503 +0100",
   remote = "git@github.com:casehubdk/hxl",
   build_file_content = """
load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
scala_library(
    name = "hxl",
    srcs = glob(["modules/core/src/main/scala/**/*.scala"]),
    visibility = ["//visibility:public"],
    plugins = [
      "@maven//:org_typelevel_kind_projector_2_13_12",
    ],
    deps = [
      "@maven//:org_typelevel_cats_effect_2_13",
      "@maven//:org_typelevel_cats_core_2_13"
    ],
)
""",
   workspace_file_content = """
workspace(name = "hxl")
"""
)
# new_local_repository(
#    name = "hxl",
#    path = "../hxl",
#    # commit = "4dcca3c131c0d3b35a49252fa6d26dda7bc17abf",
#    # shallow_since = "1710012503 +0100",
#    # remote = "git@github.com:casehubdk/hxl",
#    build_file_content = """
# load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
# scala_library(
#     name = "hxl",
#     srcs = glob(["modules/core/src/main/scala/**/*.scala"]),
#     visibility = ["//visibility:public"],
#     plugins = [
#       "@maven//:org_typelevel_kind_projector_2_13_12",
#     ],
#     deps = [
#       "@maven//:org_typelevel_cats_effect_2_13",
#       "@maven//:org_typelevel_cats_core_2_13"
#     ],
# )
# """,
#    workspace_file_content = """
# workspace(name = "hxl")
# """
# )
