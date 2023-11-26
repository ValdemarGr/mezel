load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

def _write_bsp_build_config(ctx):
  exec = ctx.actions.declare_file("create_bsp_config.sh")

  # we need to copy it to get a proper path
  jar_file = ctx.actions.declare_file("mezel.jar")
  ctx.actions.run_shell(
    inputs = [ctx.attr.jar[JavaInfo].runtime_output_jars[0]],
    outputs = [jar_file],
    command = "cp {} {}".format(
      ctx.attr.jar[JavaInfo].runtime_output_jars[0].path,
      jar_file.path
    )
  )

  j = {
    "name": "Mezel",
    "version": "1.0.0",
    "bspVersion": "2.0.0",
    "languages": ["scala"],
    "argv": ["java", "-jar", jar_file.path]
  }

  ctx.actions.write(
    output = exec,
    is_executable = True,
    content = """#!/bin/bash
if [ -z "$1" ]
then
  CONFIG_PATH="$BUILD_WORKING_DIRECTORY"
else
  CONFIG_PATH="$1"
fi
mkdir -p $CONFIG_PATH/.bsp
echo '{}' > $CONFIG_PATH/.bsp/mezel.json""".format(json.encode(j))
  )

  return [
    DefaultInfo(
      executable = exec,
      runfiles = ctx.runfiles(files = [
        jar_file,
        exec,
      ])
    )
  ]

write_bsp_build_config = rule(
  implementation = _write_bsp_build_config,
  executable = True,
  attrs = {
    "jar": attr.label(
        default = Label("@mezel_binary//jar"),
    ),
  },
)
