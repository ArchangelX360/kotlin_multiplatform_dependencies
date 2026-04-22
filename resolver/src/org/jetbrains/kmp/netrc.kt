package org.jetbrains.kmp

import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

internal fun List<String>.withNetrcCredentials(credentialsByMachine: Map<String, Pair<String, String>>): List<JarRepository> {
    return map { repository ->
        val credentials = credentialsByMachine[repository.repositoryHost()]
        JarRepository(
            url = repository,
            userName = credentials?.first.orEmpty(),
            password = credentials?.second.orEmpty(),
        )
    }
}

internal fun readNetrcCredentialsByMachine(): Map<String, Pair<String, String>> {
    val netrc = System.getenv("NETRC")?.takeIf { it.isNotBlank() }?.let(Path::of)
    return when {
        netrc?.exists() == true -> parseNetrcCredentialsByMachine(netrc.readText())
        else -> emptyMap()
    }
}

internal fun parseNetrcCredentialsByMachine(content: String): Map<String, Pair<String, String>> {
    return content
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .netrcMachineSections()
        .mapNotNull { section -> section.netrcCredentials() }
        .toMap()
}

private fun List<String>.netrcMachineSections(): List<List<String>> {
    return when {
        isEmpty() -> emptyList()
        first() != "machine" -> drop(1).netrcMachineSections()
        else -> {
            val sectionSize = drop(2).indexOf("machine").takeIf { it >= 0 }?.plus(2) ?: size
            listOf(take(sectionSize)) + drop(sectionSize).netrcMachineSections()
        }
    }
}

private fun List<String>.netrcCredentials(): Pair<String, Pair<String, String>>? {
    val machine = getOrNull(1)?.takeIf { it.isNotBlank() }
    val attributes = drop(2)
        .chunked(2)
        .mapNotNull { chunk -> chunk.takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()
    val login = attributes["login"]?.takeIf { it.isNotBlank() }
    val password = attributes["password"]?.takeIf { it.isNotBlank() }
    return when {
        machine == null || login == null || password == null -> null
        else -> machine to (login to password)
    }
}

private fun String.repositoryHost(): String? {
    return runCatching { URI.create(this).host }.getOrNull()
}
