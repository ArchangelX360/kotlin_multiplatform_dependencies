package org.jetbrains.kmp.resolver

import com.github.ajalt.clikt.command.main

suspend fun main(args: Array<String>) {
    ResolveCommand().main(args)
}
