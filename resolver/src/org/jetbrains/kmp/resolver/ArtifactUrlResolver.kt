package org.jetbrains.kmp.resolver

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import org.jetbrains.amper.dependency.resolution.MavenRepository
import java.util.concurrent.ConcurrentHashMap

internal sealed class ArtifactFile {
    data class Resolved(val url: String) : ArtifactFile()
    object NotFound : ArtifactFile()
}

internal class ArtifactUrlResolver : AutoCloseable {
    private val httpClient = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
    }
    private val availabilityByUrl = ConcurrentHashMap<String, ArtifactFile>()

    private suspend fun artifactExistsAt(repository: MavenRepository, artifactPath: String): ArtifactFile {
        val artifactUrl = "${repository.url.trimEnd('/')}/$artifactPath"
        return availabilityByUrl.getOrPut(artifactUrl) {
            val resolved = httpClient.head {
                url(artifactUrl)
                val username = repository.userName
                val password = repository.password
                when {
                    username != null && password != null -> basicAuth(username, password)
                    else -> {}
                }
            }.status.isSuccess()
            when {
                resolved -> ArtifactFile.Resolved(artifactUrl)
                else -> ArtifactFile.NotFound
            }
        }
    }

    override fun close() {
        httpClient.close()
    }
}