load("@io_bazel_rules_scala_config//:config.bzl", "SCALA_VERSION")
load("@io_bazel_rules_scala//scala:semanticdb_provider.bzl", "SemanticdbInfo")
load("@io_bazel_rules_scala//scala:providers.bzl", "DepsInfo")

BuildTargetInfo = provider(
  fields = {
    "output": "output"
  }
)

def _mezel_aspect(target, ctx):
  if (ctx.attr.non_root_projects_as_build_targets == "false" and target.label.workspace_root != "") or not (SemanticdbInfo in target) or not (JavaInfo in target):
    return []
  print("mezel aspect for ", target.label)

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

  x = tc.scala_version if "scala_version" in dir(tc) else None
  compiler_version = x if x != None else SCALA_VERSION

  sdb = target[SemanticdbInfo]

  def resolve():
    j = sdb.plugin_jar
    if j == None:
      return ""
    if type(j) == "string":
      return j
    return j.path
  semanticdb_plugin = resolve()
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
  direct_dep_labels = [x.label for x in dep_outputs]
  print("direct dep labels", direct_dep_labels)

  transitive_labels = depset([target.label], transitive = [x.transitive_labels for x in dep_outputs])
  ignore = transitive_labels.to_list()
  print("transitive labels", transitive_labels.to_list())

  output_class_jars = [x.class_jar.path for x in target[JavaInfo].java_outputs]
  if (len(output_class_jars) != 1):
    fail("Expected exactly one output class jar, got {}".format(output_class_jars))
  output_class_jar = output_class_jars[0]

  def non_self(file):
    return file.owner != target.label

  def external_dep(file):
    return file.owner not in ignore

  def depcheck(file):
    return non_self(file) and external_dep(file)

  transitive_compile_jars = target[JavaInfo].transitive_compile_time_jars.to_list()
  cp_jars = [x.path for x in transitive_compile_jars if non_self(x)]
  print("classpath_jars", cp_jars)
  transitive_source_jars = target[JavaInfo].transitive_source_jars.to_list()
  src_jars = [x.path for x in transitive_source_jars if depcheck(x)]
  print("src_jars", src_jars)

  raw_plugins = attrs.plugins if attrs.plugins else []
  plugins = [y.path for x in raw_plugins if JavaInfo in x for y in x[JavaInfo].compile_jars.to_list()]

  t = target.label.workspace_root
  wsr = None if t == "" else t

  scalac_options_file = ctx.actions.declare_file("{}_bsp_scalac_options.json".format(target.label.name))
  scalac_options_content = struct(
    scalacopts= opts,
    semanticdbPlugin= semanticdb_plugin,
    plugins= plugins,
    classpath= cp_jars,
    targetroot= semanticdb_target_root,
    outputClassJar = output_class_jar,
    compilerVersion = compiler_version,
    workspaceRoot = wsr,
  )
  ctx.actions.write(scalac_options_file, json.encode(scalac_options_content))

  sources_file = ctx.actions.declare_file("{}_bsp_sources.json".format(target.label.name))
  sources_content = struct(
    sources = [struct(
      path= f.path,
      isSource= f.is_source,
      isDirectory= f.is_directory
    ) for src in attrs.srcs for f in src.files.to_list()]
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
    deps = [str(l) for l in direct_dep_labels],
    directory = target.label.package,
    workspaceRoot = wsr,
  )
  ctx.actions.write(build_target_file, json.encode(build_target_content))

  ctx.actions.do_nothing(
    mnemonic = "MezelAspect",
    inputs = [scalac_options_file, sources_file, dependency_sources_file, build_target_file]
  )

  files = struct(
    label = target.label,
    transitive_labels = transitive_labels,
  )

  transitive_output_files = [
    x[OutputGroupInfo].bsp_info
    for x in attrs.deps if OutputGroupInfo in x and hasattr(x[OutputGroupInfo], "bsp_info")
  ]

  transitive_info_deps = [
    target[JavaInfo].transitive_compile_time_jars,
    target[JavaInfo].transitive_source_jars
  ] + [x[JavaInfo].compile_jars for x in raw_plugins]

  # this is bad practice (depset materialization)
  # but I need to filter in the transitive outputs to remove scala labels to avoid forcing
  # builds when querying the bsp info
  bsp_info_deps = depset(
    [x for x in scala_compile_classpath if depcheck(x)],
    transitive = [depset([x for x in xs.to_list() if depcheck(x)]) for xs in transitive_info_deps]
  )

  return [
    OutputGroupInfo(
      bsp_info = depset(
        [scalac_options_file, sources_file, dependency_sources_file, build_target_file],
        transitive = transitive_output_files
      ),
      bsp_info_deps = bsp_info_deps,
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
    "non_root_projects_as_build_targets": attr.string(
      default = "true",
      values = ["true", "false"],
    )
  },
  toolchains = [
    "@io_bazel_rules_scala//scala:toolchain_type",
  ],
)

def _mezel_rule(ctx):
  pass

mezel_rule = rule(
  implementation = _mezel_rule,
  attrs = {
    "_jdk": attr.label(
        default = Label("@bazel_tools//tools/jdk:current_java_runtime"),
        providers = [java_common.JavaRuntimeInfo],
    ),
    "non_root_projects_as_build_targets": attr.string(default = "true"),
    "deps": attr.label_list(allow_files = True, aspects=[mezel_aspect]),
  }
)
