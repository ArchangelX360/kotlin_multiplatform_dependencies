package org.jetbrains.kmp

internal data class JarRepository(
    val url: String,
    val userName: String = "",
    val password: String = "",
)
