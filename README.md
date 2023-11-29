# mezel

Mezel needs an aspect to work, so you can add the following to your `WORKSPACE` file to get it into scope:
```starlark
mezel_version = "6129b24dc78bb1c04d02dcb93bcb15114a9c479b"  # update this as needed
http_archive(
    name = "mezel",
    sha256 = "1fda9f6663909b102319e99c18db37c09e5cc65c8ecb7e2ba88fd72ff8e6dd03",
    strip_prefix = "mezel-%s" % mezel_version,
    type = "zip",
    url = "https://github.com/valdemargr/mezel/archive/%s.zip" % mezel_version,
)
# loads the bsp binary
load("@mezel//rules:load_mezel.bzl", "load_mezel")
load_mezel()
```
The Mezel aspect will be on the path `@mezel//aspects:aspect.bzl`.

Now we need a bsp config and the actual bsp server binary.
The mezel archive ships with a rule that generates a bsp config.

To use the bazel rules to generate the config:
```bash
bazel run @mezel//rules:gen_bsp_config
```

If you want to specify the folder to create the config in:
```bash
bazel run @mezel//rules:gen_bsp_config /path/to/workspace
```

And that's it. Start your editor and use the `Mezel` build tool!
