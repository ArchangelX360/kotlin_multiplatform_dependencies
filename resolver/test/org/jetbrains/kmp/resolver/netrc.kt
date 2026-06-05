package org.jetbrains.kmp.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

class NetrcTest {
    @Test
    fun parsesNetrcCredentialsForArbitraryMachines() {
        val credentials = parseNetrcCredentialsByMachine(
            """
            default login ignored password ignored
            machine repo.example.com login alice password token-a
            machine packages.example.org
              password token-b
              login bob
            machine incomplete.example.com login missing-password
            """.trimIndent(),
        )

        assertEquals(
            mapOf(
                "repo.example.com" to ("alice" to "token-a"),
                "packages.example.org" to ("bob" to "token-b"),
            ),
            credentials,
        )
    }

    @Test
    fun attachesNetrcCredentialsOnlyToMatchingRepositoryHost() {
        val repositories = listOf(
            "https://repo.example.com/maven2",
            "https://cache-redirector.example.org/repo1.maven.org/maven2",
        ).withNetrcCredentials(
            mapOf("repo.example.com" to ("alice" to "token-a")),
        )

        assertEquals(
            listOf(
                JarRepository(
                    url = "https://repo.example.com/maven2",
                    userName = "alice",
                    password = "token-a",
                ),
                JarRepository(
                    url = "https://cache-redirector.example.org/repo1.maven.org/maven2",
                ),
            ),
            repositories,
        )
    }
}
