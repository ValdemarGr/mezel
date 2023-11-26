# mezel

Mezel needs an aspect to work, so you can add the following to your `WORKSPACE` file to get it into scope:
```starlark
mezel_version = "commit id"  # update this as needed
http_archive(
    name = "mezel",
    sha256 = "hash (bazel can tell you this also)",
    strip_prefix = "mezel-%s" % mezel_version,
    type = "zip",
    url = "https://github.com/valdemargr/mezel/archive/%s.zip" % mezel_version,
)
```
The Mezel aspect will be on the path `@mezel//aspects:aspect.bzl`.

Now we need a bsp config and the actual bsp server binary.
The mezel archive ships with a rule that generates a bsp config.

Here is an example of a bsp config for mezel:
```json
{
  "name": "Mezel",
  "version": "1.0.0",
  "bspVersion": "2.0.0",
  "languages": ["scala"],
  "argv": ["java", "-jar", "mezel.jar"]
}
```

To use the bazel rules to generate the config:
```bash
bazel run @mezel//rules:gen_bsp_config
```

If you want to specify the folder to create the config in:
```bash
bazel run @mezel//rules:gen_bsp_config /path/to/workspace
```
