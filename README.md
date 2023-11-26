# mezel

Mezel needs an aspect to work, so you can add the following to your `WORKSPACE` file to get it into scope:
```
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

