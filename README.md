# Bazel `kmp` module

> ⚠️ **Warning:** Repository under construction, some extensions APIs and their behavior may change without notice

Hermetic provider of Kotlin Multiplatform dependencies resolution in Bazel.

## Installation

```starlark
bazel_dep(name = "kmp", version = "0.0.1")

kmp_extension = use_extension("@kmp//kmp:extensions.bzl", "kmp")
kmp_extension.configure(
    deps = ["com.example:mylib:0.0.1"],
    repositories = ["https://repo1.maven.org/maven2"],
)
use_repo(kmp_extension, "kmp_deps")
```
