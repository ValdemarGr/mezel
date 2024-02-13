def _impl(ctx):
  build_args = " ".join(["--build-arg={}".format(x) for x in ctx.attr.build_args])
  aquery_args = " ".join(["--aquery-arg={}".format(x) for x in ctx.attr.aquery_args])

  xs = ctx.attr._mezel_jar.files.to_list()
  if len(xs) != 1:
    fail("Expected exactly one file in _mezel_jar, got {} files".format(len(xs)))

  binary_file = ctx.attr._mezel_jar.files.to_list()[0]

  mk_runscript_content = """#!/usr/bin/env bash
BINARY_FILE={}
CHECKSUM=$(sha256sum $BINARY_FILE | head -c 64)
OUTPUT_BINARY_FILE=/tmp/mezel-$CHECKSUM.jar
cp $BINARY_FILE $OUTPUT_BINARY_FILE

SYM='$@'
echo "#!/usr/bin/env bash" > $1
echo "java -jar ${{OUTPUT_BINARY_FILE}} {} {} $SYM" >> $1
chmod +x $1
""".format(binary_file.path, build_args, aquery_args)
  mk_runscript = ctx.actions.declare_file("make-runscript.sh")
  ctx.actions.write(
    output = mk_runscript,
    content = mk_runscript_content,
    is_executable = True
  )

  return DefaultInfo(executable = mk_runscript, runfiles = ctx.runfiles(files = [binary_file]))

make_mezel_launcher = rule(
  implementation=_impl,
  executable=True,
  attrs = {
    "build_args": attr.string_list(mandatory = False),
    "aquery_args": attr.string_list(mandatory = False),
    "_mezel_jar": attr.label(
      default = Label("@mezel_binary//jar"),
      allow_files = True
    ),
  },
)
