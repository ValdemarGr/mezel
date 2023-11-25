load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")
load("@io_bazel_rules_scala//scala:semanticdb_provider.bzl", "SemanticdbInfo")
load("@io_bazel_rules_scala//scala:providers.bzl", "DepsInfo")

BuildTargetInfo = provider(
  fields = {
    "outputs": "list of outputs"
  }
)

def _mezel_aspect(target, ctx):
  attrs = ctx.rule.attr

  # label_str = target.label.package + ":" + target.label.name
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
  source_jars = target[JavaInfo].transitive_source_jars.to_list()
  src_jars = [x.path for x in source_jars]

  # srcs = attrs.srcs

  bi = ctx.actions.declare_file("{}_bsp_info".format(target.label.name))

  dep_providers = tc.dep_providers
  scala_compile_classpath = [
    f 
    for prov in dep_providers if prov[DepsInfo].deps_id == "scala_compile_classpath" 
    for dep in prov[DepsInfo].deps 
    for f in dep[JavaInfo].compile_jars.to_list()
  ]

  data = struct(
    compilerVersion= compiler_version,
    scalacopts= opts,
    semanticdbPlugin= semanticdb_plugin,
    classpath= cp_jars,
    sourcejars= src_jars,
    scalaCompilerClasspath= [x.path for x in scala_compile_classpath],
    sources= [f.path for src in attrs.srcs for f in src.files.to_list()]
  )

  dep_outputs = [
    output
    for x in attrs.deps if BuildTargetInfo in x 
    for output in x[BuildTargetInfo].outputs
  ]
  result = struct(
    label = target.label,
    deps = dep_outputs,#[x.label for x in dep_outputs],
    directory = target.label.package,
    bspContent = data,
    scalaCompilerClasspath= data.scalaCompilerClasspath
  )

  ctx.actions.write(bi, json.encode(data))

  #output_depset = [result] + all_deps

  return [
    OutputGroupInfo(bsp_info = [bi]),#[x.bspFile for x in output_depset]),
    BuildTargetInfo(outputs = [target.label])#[bi] + dep_outputs)
  ]

  # child_targets = [y for x in attrs.deps if BuildTargetInfo in x for y in x[BuildTargetInfo].labels]

  # result = struct(
  #   label = target.label,
  #   bsp_info = data,
  # )

  # return [
  #   BuildTargetInfo(labels = [result] + child_targets)
  # ]

def _mezel_config0(ctx):
  deps = ctx.attr.deps
  attrs = ctx.attr

  if not deps:
    fail("No deps provided")

  if len(deps) == 0:
    fail("No deps provided")

  tc = ctx.toolchains["@io_bazel_rules_scala//scala:toolchain_type"]

  if not tc.enable_semanticdb:
    fail("SemanticDB is not enabled, please set the `enable_semanticdb` attribute to `True` in your `scala_toolchain`", tc)

  if tc.semanticdb_bundle_in_jar:
    fail("SemanticDB is bundled in the output jar, please generate it separately by setting the `semanticdb_bundle_in_jar` attribute to `False` in your `scala_toolchain`")

  sdb = deps[0][SemanticdbInfo]

  dep_providers = tc.dep_providers
  scala_compile_classpath = [
    f 
    for prov in dep_providers if prov[DepsInfo].deps_id == "scala_compile_classpath" 
    for dep in prov[DepsInfo].deps 
    for f in dep[JavaInfo].compile_jars.to_list()
  ]

  compile_jars = deps[0][JavaInfo].transitive_compile_time_jars
  source_jars = deps[0][JavaInfo].transitive_source_jars

  compiler_version = SCALA_VERSION

  tc_opts = tc.scalacopts if tc.scalacopts else []
  attr_opts = [] #attrs.scalacopts if attrs.scalacopts else []
  opts = tc_opts + attr_opts

  semanticdb_plugin = sdb.plugin_jar
  semanticdb_target_root = sdb.target_root

  f = ctx.actions.declare_file("bsp_info.json")

  data = struct(
    label = str(ctx.label),
    compilerVersion = compiler_version,
    semantictdbPlugin = semanticdb_plugin,
    classpath = [x.path for x in compile_jars.to_list()],
    sourcejars = [x.path for x in source_jars.to_list()],
    scalaCompilerClasspath = [x.path for x in scala_compile_classpath],
    scalacopts = opts,
    directory = ctx.label.package,
  )

  ctx.actions.write(f, json.encode(data))

  return DefaultInfo(runfiles = ctx.runfiles(files = [f]))

def _mezel_config(ctx):
  output = [
    {
      "label": str(l.label),
      #"bspContent": l.bspContent,
      "directory": l.directory,
      "deps": [str(x) for x in l.deps.to_list()],
      "scalaCompilerClasspath": l.scalaCompilerClasspath
    }
    for d in ctx.attr.deps for l in d[BuildTargetInfo].outputs 
  ]

  f = ctx.actions.declare_file("all_build_targets.json")
  ctx.actions.write(f, json.encode(output))

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
  },
  toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
)
