package org.jetbrains.kmp.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryCredentialsTest {
    @Test
    fun parsesCredentialsFromStream() {
        this::class.java.getResourceAsStream("/credentials.json").use { credentials ->
            val actual = RepositoryCredentials.fromStream(credentials)
            val expected = listOf(
                RepositoryCredentials(repositoryUrl = "repo.example.com", username = "alice", password = "token-a"),
                RepositoryCredentials(repositoryUrl = "packages.example.org", username = "bob", password = "token-b"),
            ).associateBy { it.repositoryUrl }
            assertEquals(expected, actual)
        }
    }
}