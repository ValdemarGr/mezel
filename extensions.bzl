# load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_jar")
load("//rules:load_mezel.bzl", "load_mezel")

def _mezel_binary_impl(mctx):
  load_mezel()

mezel_binary = module_extension(implementation = _mezel_binary_impl)
