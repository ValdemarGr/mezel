load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")
load("@io_bazel_rules_scala//scala:semanticdb_provider.bzl", "SemanticdbInfo")
load("@io_bazel_rules_scala//scala:providers.bzl", "DepsInfo")

BuildTargetInfo = provider(
  fields = {
    "output": "output"
  }
)

def _mezel_aspect(target, ctx):
  attrs = ctx.rule.attr

  if ctx.rule.kind != "scala_library":
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

  scalac_options_file = ctx.actions.declare_file("{}_bsp_scalac_options.json".format(target.label.name))
  scalac_options_content = struct(
    scalacopts= opts,
    semanticdbPlugin= semanticdb_plugin,
    classpath= cp_jars,
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
    label = target.label,
    scalac_options_file = scalac_options_file,
    sources_file = sources_file,
    dependency_sources_file = dependency_sources_file,
    build_target_file = build_target_file
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
    ),
    BuildTargetInfo(output = files)
  ]
  # transitive = [
  #   x[OutputGroupInfo].bsp_info
  #   for x in attrs.deps if OutputGroupInfo in x and hasattr(x[OutputGroupInfo], "bsp_info")
  # ]
  # return [
  #   OutputGroupInfo(bsp_info = depset([bsp_target_file], transitive = transitive)),
  #   BuildTargetInfo(output = struct(label = target.label, bsp_target_file = bsp_target_file))
  # ]

#def _mezel_config(ctx):
#  all_files = [x[BuildTargetInfo].output for x in ctx.attr.deps]
#  scalac_options_files = [x.scalac_options_file for x in all_files]
#  sources_files = [x.sources_file for x in all_files]
#  dependency_sources_files = [x.dependency_sources_file for x in all_files]
#  build_target_files = [x.build_target_file for x in all_files]

#  all_outputs = scalac_options_files + sources_files + dependency_sources_files + build_target_files
#  # files = [x[OutputGroupInfo].bsp_info for x in ctx.attr.deps]
#  #output = [
#  #  {
#  #    "label": str(l.label),
#  #    #"bspContent": l.bspContent,
#  #    "directory": l.directory,
#  #    "deps": [str(x) for x in l.deps.to_list()],
#  #    "scalaCompilerClasspath": l.scalaCompilerClasspath
#  #  }
#  #  for d in ctx.attr.deps for l in d[BuildTargetInfo].output 
#  #]

#  #f = ctx.actions.declare_file("all_build_targets.json")
#  #ctx.actions.write(f, json.encode(output))

#  # return DefaultInfo(runfiles = ctx.runfiles(files = [f]))
#  # ds = depset([], transitive = files)
#  # allocate a label for the aspect
#  ctx.actions.do_nothing(
#    mnemonic = "MezelConfig",
#    inputs = all_outputs
#  )
#  return DefaultInfo(files = depset(all_outputs))

mezel_aspect = aspect(
  implementation = _mezel_aspect,
  attr_aspects = ["deps"],
  required_aspect_providers = [[JavaInfo, SemanticdbInfo]],
  toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
)

# mezel_config = rule(
#   implementation = _mezel_config,
#   attrs = {
#     "deps": attr.label_list(
#       mandatory=True,
#       aspects = [mezel_aspect],
#       providers = [JavaInfo, SemanticdbInfo]
#     )
#   },
#   toolchains = ["@io_bazel_rules_scala//scala:toolchain_type"],
# )
