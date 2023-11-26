load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")
load("@io_bazel_rules_scala//scala:semanticdb_provider.bzl", "SemanticdbInfo")
load("@io_bazel_rules_scala//scala:providers.bzl", "DepsInfo")

BuildTargetInfo = provider(
  fields = {
    "output": "output"
  }
)

def _mezel_aspect(target, ctx):
  if ctx.rule.kind != "scala_library":
    return []

  attrs = ctx.rule.attr

  jdk = ctx.attr._jdk

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

  dep_providers = tc.dep_providers
  scala_compile_classpath = [
    f 
    for prov in dep_providers if prov[DepsInfo].deps_id == "scala_compile_classpath" 
    for dep in prov[DepsInfo].deps 
    for f in dep[JavaInfo].compile_jars.to_list()
  ]

  dep_outputs = [
    x[BuildTargetInfo].output
    for x in attrs.deps if BuildTargetInfo in x 
  ]

  transitive_compile_jars = target[JavaInfo].transitive_compile_time_jars.to_list()
  cp_jars = [x.path for x in transitive_compile_jars]
  transitive_source_jars = target[JavaInfo].transitive_source_jars.to_list()
  src_jars = [x.path for x in transitive_source_jars]

  raw_plugins = attrs.plugins if attrs.plugins else []
  plugins = [y.path for x in raw_plugins if JavaInfo in x for y in x[JavaInfo].compile_jars.to_list()]

  scalac_options_file = ctx.actions.declare_file("{}_bsp_scalac_options.json".format(target.label.name))
  scalac_options_content = struct(
    scalacopts= opts,
    semanticdbPlugin= semanticdb_plugin,
    plugins= plugins,
    classpath= cp_jars,
    targetroot= semanticdb_target_root,
  )
  ctx.actions.write(scalac_options_file, json.encode(scalac_options_content))

  sources_file = ctx.actions.declare_file("{}_bsp_sources.json".format(target.label.name))
  sources_content = struct(
    sources = [f.path for src in attrs.srcs for f in src.files.to_list()]
  )
  ctx.actions.write(sources_file, json.encode(sources_content))

  dependency_sources_file = ctx.actions.declare_file("{}_bsp_dependency_sources.json".format(target.label.name))
  dependency_sources_content = struct(
    sourcejars = src_jars
  )
  ctx.actions.write(dependency_sources_file, json.encode(dependency_sources_content))

  build_target_file = ctx.actions.declare_file("{}_bsp_build_target.json".format(target.label.name))
  build_target_content = struct(
    javaHome = jdk[java_common.JavaRuntimeInfo].java_home,
    scalaCompilerClasspath= [x.path for x in scala_compile_classpath],
    compilerVersion= compiler_version,
    deps = [str(x.label) for x in dep_outputs],
    directory = target.label.package,
  )
  ctx.actions.write(build_target_file, json.encode(build_target_content))

  ctx.actions.do_nothing(
    mnemonic = "MezelAspect",
    inputs = [scalac_options_file, sources_file, dependency_sources_file, build_target_file]
  )

  files = struct(
    label = target.label
  )

  transitive_output_files = [
    x[OutputGroupInfo].bsp_info
    for x in attrs.deps if OutputGroupInfo in x and hasattr(x[OutputGroupInfo], "bsp_info")
  ]

  return [
    OutputGroupInfo(
      bsp_info = depset(
        [scalac_options_file, sources_file, dependency_sources_file, build_target_file],
        transitive = transitive_output_files
      ),
      bsp_info_deps = depset(
        scala_compile_classpath,
        transitive = [
          target[JavaInfo].transitive_compile_time_jars,
          target[JavaInfo].transitive_source_jars
        ] + [x[JavaInfo].compile_jars for x in raw_plugins]
      )
    ),
    BuildTargetInfo(output = files)
  ]

mezel_aspect = aspect(
  implementation = _mezel_aspect,
  attr_aspects = ["deps"],
  required_aspect_providers = [[JavaInfo, SemanticdbInfo]],
  attrs = {
    "_jdk": attr.label(
        default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
        providers = [java_common.JavaRuntimeInfo],
    ),
  },
  toolchains = [
    "@io_bazel_rules_scala//scala:toolchain_type",
  ],
)
