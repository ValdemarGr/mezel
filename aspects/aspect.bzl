load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")
load("@io_bazel_rules_scala//scala:semanticdb_provider.bzl", "SemanticdbInfo")
load("@io_bazel_rules_scala//scala:providers.bzl", "DepsInfo")

BuildTargetInfo = provider(
  fields = {
    "labels": "depset of structs"
  }
)

def _mezel_aspect(target, ctx):
  attrs = ctx.rule.attr

  label_str = target.label.package + ":" + target.label.name
  # print("rule name", ctx.rule.kind, attrs.name, target.label, label_str, "common" in label_str)
  if ctx.rule.kind != "scala_library":# or "common" in label_str or "std" in label_str or "spice" in label_str:
    return []

  tc = ctx.toolchains["@io_bazel_rules_scala//scala:toolchain_type"]

  if not tc.enable_semanticdb:
    fail("SemanticDB is not enabled, please set the `enable_semanticdb` attribute to `True` in your `scala_toolchain`", tc)

  if tc.semanticdb_bundle_in_jar:
    fail("SemanticDB is bundled in the output jar, please generate it separately by setting the `semanticdb_bundle_in_jar` attribute to `False` in your `scala_toolchain`")

  tc_opts = tc.scalacopts if tc.scalacopts else []
  attr_opts = attrs.scalacopts if attrs.scalacopts else []
  opts = tc_opts + attr_opts

  compiler_version = SCALA_VERSION

  sdb = target[SemanticdbInfo]

  semanticdb_plugin = sdb.plugin_jar
  semanticdb_target_root = sdb.target_root

  compile_jars = target[JavaInfo].transitive_compile_time_jars.to_list()
  cp_jars = [x.path for x in compile_jars]

  dep_providers = tc.dep_providers
  scala_compile_classpath = [
    f 
    for prov in dep_providers if prov[DepsInfo].deps_id == "scala_compile_classpath" 
    for dep in prov[DepsInfo].deps 
    for f in dep[JavaInfo].compile_jars.to_list()
  ]
  
  srcs = attrs.srcs

  bi = ctx.actions.declare_file("{}_bsp_info".format(target.label.name))

  data = struct(
    compiler_version= compiler_version,
    scalacopts= opts,
    semanticdb_plugin= semanticdb_plugin,
    classpath= cp_jars,
    scala_compiler_classpath= [x.path for x in scala_compile_classpath],
    sources= [f.path for src in srcs for f in src.files.to_list()]
  )

  ctx.actions.write(bi, proto.encode_text(data))

  return [
    OutputGroupInfo(bsp_info = [bi]),
    BuildTargetInfo(labels = depset(
      [target.label],
      transitive = [x[BuildTargetInfo].labels for x in attrs.deps if BuildTargetInfo in x]
    )),
  ]

  # child_targets = [y for x in attrs.deps if BuildTargetInfo in x for y in x[BuildTargetInfo].labels]

  # result = struct(
  #   label = target.label,
  #   bsp_info = data,
  # )

  # return [
  #   BuildTargetInfo(labels = [result] + child_targets)
  # ]

def _mezel_config(ctx):
  all_labels = [str(l) for l in depset([], transitive = [d[BuildTargetInfo].labels for d in ctx.attr.deps]).to_list()]
  # all_labels = [{"label": str(x.label), "bsp_info": x.bsp_info} for d in ctx.attr.deps for x in d[BuildTargetInfo].labels]

  f = ctx.actions.declare_file("all_build_targets.json")
  ctx.actions.write(f, json.encode(all_labels))

  return DefaultInfo(runfiles = ctx.runfiles(files = [f]))

mezel_aspect = aspect(
  implementation = _mezel_aspect,
  attr_aspects = ["deps"],
  required_aspect_providers = [[JavaInfo, SemanticdbInfo]],
  toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
)

mezel_config = rule(
  implementation = _mezel_config,
  attrs = {
    "deps": attr.label_list(
      mandatory=True,
      aspects = [mezel_aspect],
      providers = [JavaInfo, SemanticdbInfo]
    )
  }
)
