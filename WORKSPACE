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
rules_scala_version = "6c6ba4e31cb56c038fe9967efd222007d73fd5cf"  # update this as needed

http_archive(
    name = "io_bazel_rules_scala",
    sha256 = "3aad1238ba84d53f1f9471452580835ff0f3e8b6f03567e9e17017e8cc8e3894",
    strip_prefix = "rules_scala-%s" % rules_scala_version,
    type = "zip",
    url = "https://github.com/bazelbuild/rules_scala/archive/%s.zip" % rules_scala_version,
)

load("@io_bazel_rules_scala//:scala_config.bzl", "scala_config")

scala_config(
  scala_version="3.3.1",
  scala_versions=["2.13.12", "3.3.1"]
)
# scala_config("3.3.0")

load("@io_bazel_rules_scala//scala:scala.bzl", "scala_repositories")

register_toolchains("//:toolchain")
register_toolchains("//:toolchain_3")

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
  name = "maven3",
  artifacts = [
      "org.typelevel:cats-effect_3:3.5.2"
  ],
  repositories = [
      "https://maven.google.com",
      "https://repo1.maven.org/maven2",
  ],
  maven_install_json = "//:maven3_install.json",
  fetch_sources = True
)
load("@maven3//:defs.bzl", pinned_maven_install_3="pinned_maven_install")
pinned_maven_install_3()

maven_install(
  name = "maven",
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
load("@maven//:defs.bzl", pinned_maven_install_2="pinned_maven_install")
pinned_maven_install_2()

load("//rules:load_mezel.bzl", "load_mezel")
load_mezel()

new_git_repository(
   name = "hxl",
   commit = "4dcca3c131c0d3b35a49252fa6d26dda7bc17abf",
   shallow_since = "1710012503 +0100",
   remote = "git@github.com:casehubdk/hxl",
   build_file_content = """
load("@io_bazel_rules_scala//scala:scala_cross_version_select.bzl", "select_for_scala_version")
load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library")
scala_library(
    name = "hxl",
    srcs = glob(["modules/core/src/main/scala/**/*.scala"]),
    visibility = ["//visibility:public"],
    scalacopts = select_for_scala_version(
      before_3_3 = [
        "-Xsource:3"
      ]
    ),
    plugins = select_for_scala_version(
      before_3_3 = [
        "@maven//:org_typelevel_kind_projector_2_13_12",
      ]
    ),
    deps = select_for_scala_version(
      before_3_3 = [
        "@maven//:org_typelevel_cats_effect_2_13",
        "@maven//:org_typelevel_cats_core_2_13"
      ],
      since_3_3 = [
        "@maven3//:org_typelevel_cats_effect_3",
        "@maven3//:org_typelevel_cats_core_3"
      ]
    ),
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
