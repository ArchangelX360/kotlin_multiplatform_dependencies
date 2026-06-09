"""Extensions for bzlmod.

Exposes in repositories Kotlin Multiplatform dependencies resolved against Maven repositories.
"""

load(
    "//kmp/private/extensions:kmp.bzl",
    _kmp = "kmp",
)

kmp = _kmp
