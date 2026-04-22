package org.jetbrains.kmp.commands

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.jetbrains.amper.dependency.resolution.*
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import org.jetbrains.kmp.JarRepository
import org.jetbrains.kmp.readNetrcCredentialsByMachine
import org.jetbrains.kmp.withNetrcCredentials
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

class ResolveCommand : SuspendingCliktCommand("resolver") {
    private val coordinates by option(
        "--coordinate",
        help = "Maven coordinate to resolve. Repeat for multiple values.",
    ).multiple(required = true)

    private val outputManifest by option(
        "--output-manifest-file",
        help = "Path to the output manifest file.",
    ).convert { Path.of(it) }.required()

    private val repositories by option(
        "--repository",
        help = "Maven repository URL. Repeat for multiple values.",
    ).multiple(required = true)

    override suspend fun run() {
        val cache = outputManifest.parent
        cache.deleteIfExists()
        val credentials = readNetrcCredentialsByMachine()
        val resolver = MultiplatformResolver(
            cachePath = cache,
            manifestPath = outputManifest,
            mavenRepositories = repositories.withNetrcCredentials(credentials),
        )
        resolver.dumpManifestToCache(
            BazelManifest(
                askedCoordinates = coordinates,
                askedRepositories = repositories,
                libraries = coordinates.toSet()
                    .flatMap { library -> resolver.resolveMultiplatformComponentsOf(library) }.associateBy { it.id },
            )
        )
    }
}

internal typealias MultiplatformLibraryId = String

internal sealed class MultiplatformLibrary {
    data object NoneMatching : MultiplatformLibrary()

    @Serializable
    data class Resolved(
        val id: MultiplatformLibraryId,
        val klibs: List<MultiplatformLibraryArtifact>,
        val sourceJar: MultiplatformLibraryArtifact?,
        val runtimeDependencies: List<MultiplatformLibraryId>,
        val compileTimeDependencies: List<MultiplatformLibraryId>,
        val linkTimeDependencies: List<MultiplatformLibraryId>,
    ) : MultiplatformLibrary()
}

@Serializable
internal data class MultiplatformLibraryArtifact(
    val sha256checksum: String?,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val urls: List<String>,
)

@Serializable
internal data class BazelManifest(
    val askedCoordinates: List<String>,
    val askedRepositories: List<String>,
    val libraries: Map<MultiplatformLibraryId, MultiplatformLibrary.Resolved>,
)

private class MultiplatformResolver(
    private val cachePath: Path,
    private val manifestPath: Path,
    mavenRepositories: List<JarRepository>,
) {
    private val amperCachePath: Path = cachePath.resolve("amper-cache")

    private val repositories by lazy {
        mavenRepositories.map {
            MavenRepository(
                it.url,
                userName = it.userName,
                password = it.password,
            )
        }
    }

    companion object {
        private val json = Json {
            allowStructuredMapKeys = true
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun dumpManifestToCache(manifest: BazelManifest) {
        manifestPath.createParentDirectories()
        manifestPath.outputStream().use { output ->
            json.encodeToStream(manifest, output)
        }
    }

    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    suspend fun resolveMultiplatformComponentsOf(mavenGav: String): Set<MultiplatformLibrary.Resolved> {
        val root = Context {
            scope = ResolutionScope.RUNTIME
            platforms = setOf(ResolutionPlatform.WASM_JS)
            repositories = this@MultiplatformResolver.repositories
            cache = {
                javaClass.getMethod("setAmperCache\$main", Path::class.java).invoke(this, amperCachePath)
            }
        }.use { context ->
            mavenGav.toMavenNode(context).also { node ->
                val resolver = Resolver()
                resolver.buildGraph(root = node, level = ResolutionLevel.NETWORK, transitive = true)
                node.dependency.resolveChildren(context = node.context, level = ResolutionLevel.NETWORK)
            }
        }

        val errors = root.resolutionErrors()
        return when {
            errors.isEmpty() -> {
                val repoUrls = repositories.map { repository -> repository.url }
                val resolved = mutableSetOf<MultiplatformLibrary.Resolved>()
                val visited = mutableSetOf<MavenDependencyNode>()
                val queue = ArrayDeque<MavenDependencyNode>()
                queue.add(root)
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirstOrNull()
                    when {
                        node == null -> {}
                        visited.contains(node) -> {}
                        else -> {
                            visited.add(node)

                            val actualNode = node.actualWasmJsMavenDependency()
                            val children = actualNode.children.filterIsInstance<MavenDependencyNode>()
                            queue.addAll(children)

                            val actualChildren = children.map { it.actualWasmJsMavenDependency() }

                            val klibs = actualNode.filesMatching(repoUrls) { it.klib() }
                            val sourceJars = actualNode.filesMatching(repoUrls) { it.sourceJar() }
                            require(sourceJars.size <= 1) { "Expected at most one source jar, found ${sourceJars.size}: $sourceJars" }
                            val sourceJar = sourceJars.singleOrNull()

                            resolved.add(
                                MultiplatformLibrary.Resolved(
                                    id = actualNode.idForBazel,
                                    klibs = klibs,
                                    sourceJar = sourceJar,
                                    runtimeDependencies = actualChildren.map { it.idForBazel }.distinct(),
                                    linkTimeDependencies = emptyList(), // TODO
                                    compileTimeDependencies = emptyList(), // TODO
                                )
                            )
                        }
                    }
                }
                resolved
            }

            else -> {
                error(buildString {
                    appendLine("'$mavenGav' failed to resolve with:")
                    errors.flatMap { it.detailedMessage.lines() }.forEach {
                        appendLine(it)
                    }
                })
            }
        }
    }
}

private val MavenDependencyNode.idForBazel get() = "$group:$module:$version"

private suspend fun MavenDependencyNode.actualWasmJsMavenDependency(): MavenDependencyNode =
    actualMavenDependencyOfVariantsMatching { it.klib() || it.sourceJar() }

private fun MultiplatformLibraryArtifact.cacheKey(): String {
    val url = urls.firstOrNull() ?: error("Resolved artifact does not contain any URLs: $this")
    return "$groupId|$artifactId|$version|${url.basenameFromUrl()}"
}

private fun String.basenameFromUrl(): String {
    return substringBefore('?').substringBefore('#').substringAfterLast('/')
}

private fun MavenDependencyNode.resolutionErrors(): List<Message> {
    return messages.filter { it.severity >= Severity.ERROR } + children.filterIsInstance<MavenDependencyNode>()
        .flatMap { child -> child.resolutionErrors() }
}

private fun Map<String, String>.sourceJar(): Boolean {
    return this["org.gradle.category"] == "documentation" && this["org.gradle.docstype"] == "sources" && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

private fun Map<String, String>.klib(): Boolean {
    return this["org.gradle.category"] == "library" && this["org.gradle.usage"] in setOf(
        "kotlin-api", "kotlin-runtime"
    ) && this["org.jetbrains.kotlin.platform.type"] == "wasm" && this["org.jetbrains.kotlin.wasm.target"] == "js"
}

@Suppress("INVISIBLE_REFERENCE")
private suspend fun MavenDependencyNode.actualMavenDependencyOfVariantsMatching(attributeMatcher: (Map<String, String>) -> Boolean): MavenDependencyNode {
    val originalDep = this
    originalDep.dependency.resolveChildren(context = originalDep.context, level = ResolutionLevel.NETWORK)
    val availableAts =
        originalDep.dependency.variants.filter { attributeMatcher(it.attributes) }.map { it.`available-at` }.toSet()
    return when {
        availableAts.all { it == null } -> originalDep // no variant indirection, it means that dependency is the actual one we need to consider
        availableAts.singleOrNull() == null -> error("all matched variants must point to the same Maven dependency, but got: ${originalDep.dependency.variants}")
        else -> { // all variants are pointing to the same Maven dependency, e.g. the `-wasm-js` one, that's the one we must consider here
            val availableAt = availableAts.single()
            val variantDep = originalDep.children.filterIsInstance<MavenDependencyNode>().first {
                it.group == availableAt.group && it.module == availableAt.module && it.version == availableAt.version
            }
            variantDep.dependency.resolveChildren(
                context = originalDep.context, level = ResolutionLevel.NETWORK
            ) // force resolve
            variantDep
        }
    }
}

@Suppress("INVISIBLE_REFERENCE")
private suspend fun MavenDependencyNode.filesMatching(
    repositoryUrls: List<String>,
    attributeMatcher: (Map<String, String>) -> Boolean,
): List<MultiplatformLibraryArtifact> = dependency.variants.filter { variant ->
    attributeMatcher(variant.attributes)
}.flatMap { variant ->
    variant.files.map { file ->
        MultiplatformLibraryArtifact(
            sha256checksum = file.sha256,
            groupId = dependency.group.orUnspecified(),
            artifactId = dependency.module,
            version = dependency.version.orUnspecified(),
            urls = urlsFor(file.url, repositoryUrls),
        )
    }
}.distinctBy { artifact ->
    artifact.cacheKey()
}

private fun MavenDependencyNode.urlsFor(fileUrl: String, repositoryUrls: List<String>): List<String> = artifactUrls(
    group = group,
    module = module,
    version = version.orUnspecified(),
    fileUrl = fileUrl,
    preferredRepositoryUrl = dependency.preferredRepositoryUrl(),
    repositoryUrls = repositoryUrls,
)

private fun MavenDependency.preferredRepositoryUrl(): String? = runCatching {
    javaClass.getMethod("getRepository\$main").invoke(this) as? MavenRepository
}.getOrNull()?.url

internal fun artifactUrls(
    group: String,
    module: String,
    version: String,
    fileUrl: String,
    preferredRepositoryUrl: String?,
    repositoryUrls: List<String>,
): List<String> {
    val artifactPath = artifactPathFor(
        group = group,
        module = module,
        version = version,
        fileUrl = fileUrl,
    )
    return when {
        fileUrl.isAbsoluteUrl() -> listOf(fileUrl)
        else -> (listOfNotNull(preferredRepositoryUrl) + repositoryUrls.filter { repositoryUrl -> repositoryUrl != preferredRepositoryUrl }).map { repositoryUrl ->
            repositoryUrl.resolveArtifactUrl(
                artifactPath
            )
        }.distinct()
    }
}

private fun artifactPathFor(
    group: String,
    module: String,
    version: String,
    fileUrl: String,
): String {
    val groupPath = group.split('.').joinToString("/")
    return "${groupPath}/${module}/${version}/${fileUrl.removePrefix("./")}"
}

private fun String.resolveArtifactUrl(artifactPath: String): String {
    return URI.create(this.trimEnd('/') + "/").resolve(artifactPath).toString()
}

private fun String.isAbsoluteUrl(): Boolean {
    return startsWith("https://") || startsWith("http://")
}

private fun String.toMavenNode(context: Context): MavenDependencyNode {
    val isBom = startsWith("bom:")
    val parts = removePrefix("bom:").trim().split(":")
    val group = parts[0]
    val module = parts[1]
    val version = when {
        parts.size > 2 -> parts[2]
        else -> null
    }
    return MavenDependencyNode(context, group, module, version, isBom = isBom)
}

internal data class ResolvableLibrary(
    val mavenCoordinates: String,
) : Comparable<ResolvableLibrary> {
    override fun compareTo(other: ResolvableLibrary): Int = mavenCoordinates.compareTo(other.mavenCoordinates)
}
