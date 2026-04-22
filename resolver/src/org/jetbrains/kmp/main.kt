package org.jetbrains.kmp

import com.github.ajalt.clikt.command.main
import org.jetbrains.kmp.commands.ResolveCommand

suspend fun main(args: Array<String>) {
    ResolveCommand().main(args)
}
