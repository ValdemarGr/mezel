load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

def _write_bsp_build_config(ctx):
  exec = ctx.actions.declare_file("create_bsp_config.sh")

  j = {
    "name": "Mezel",
    "version": "1.0.0",
    "bspVersion": "2.0.0",
    "languages": ["scala"],
    "argv": ["bazel", "run", "@mezel//rules:mezel_binary", "--"]
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
        exec
      ])
    )
  ]

write_bsp_build_config = rule(
  implementation = _write_bsp_build_config,
  executable = True
)
