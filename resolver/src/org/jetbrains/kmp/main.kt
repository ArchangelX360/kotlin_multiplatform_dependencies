package org.jetbrains.kmp

import com.github.ajalt.clikt.command.main

suspend fun main(args: Array<String>) {
    ResolveCommand().main(args)
}
