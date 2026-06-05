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
                libraries = resolver.resolveMultiplatformComponentsOf(coordinates.toSet()).associateBy { it.id },
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
        /**
         * .klib of this imported dependency, exposed to the compile library path of direct dependents.
         */
        val klib: MultiplatformLibraryArtifact,
        val sourceJar: MultiplatformLibraryArtifact?,
        /**
         * Dependencies of this library, exposed to the link path of dependents transitively.
         */
        val dependencies: List<MultiplatformLibraryId>,
        /**
         * Dependencies of this library, exposed to the link path of dependents transitively, and exposed to the compile library path of *direct* dependents.
         */
        val exportedDependencies: List<MultiplatformLibraryId>,
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

    @Suppress("INVISIBLE_MEMBER")
    suspend fun resolveMultiplatformComponentsOf(coordinatesBag: Set<String>): Set<MultiplatformLibrary.Resolved> {
        val platforms = setOf(ResolutionPlatform.WASM_JS)
        val defaultSettings: SettingsBuilder.() -> Unit = {
            this.platforms = setOf(ResolutionPlatform.WASM_JS)
            repositories = this@MultiplatformResolver.repositories
            cache = getDefaultFileCacheBuilder(amperCachePath)
        }
        val templateContext = Context {
            defaultSettings()
        }
        val runtimeContext = Context {
            defaultSettings()
            scope = ResolutionScope.RUNTIME
        }
        val compileContext = Context {
            defaultSettings()
            scope = ResolutionScope.COMPILE
        }
        val root = RootDependencyNodeWithContext(
            graphEntryName = "root",
            children = coordinatesBag.map { c -> c.toMavenNode(runtimeContext) } + coordinatesBag.map { c ->
                c.toMavenNode(compileContext)
            },
            rootCacheEntryKey = RootCacheEntryKey.Key(
                CacheEntryKey.CompositeCacheEntryKey(
                    coordinatesBag.toList() + repositories + platforms,
                )
            ),
            templateContext = templateContext,
        )
        val graph =
            Resolver().resolveDependencies(root = root, resolutionLevel = ResolutionLevel.NETWORK, transitive = true)
        val errors = root.resolutionErrors()
        return when {
            errors.isEmpty() -> {
                val repoUrls = repositories.map { repository -> repository.url }
                val resolved = mutableMapOf<String, MultiplatformLibrary.Resolved>()
                val visited = mutableSetOf<MavenDependencyNode>()
                val queue = ArrayDeque<MavenDependencyNode>()
                queue.addAll(root.children.filterIsInstance<MavenDependencyNode>())
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
                            when (klibs.size) {
                                0 -> {}
                                1 -> {
                                    val klib = klibs.singleOrNull()
                                        ?: error("Expected exactly one klib for dependency ${actualNode.idForBazel}, got: ${klibs}")

                                    val sourceJars = actualNode.filesMatching(repoUrls) { it.sourceJar() }
                                    require(sourceJars.size <= 1) { "Expected at most one source jar, found ${sourceJars.size}: $sourceJars" }
                                    val sourceJar = sourceJars.singleOrNull()

                                    val (runtimeDeps, compileDeps) = actualChildren.partition { it.dependency.resolutionConfig.scope == ResolutionScope.RUNTIME }
                                    resolved.compute(actualNode.idForBazel) { _, v ->
                                        v?.copy(
                                            dependencies = (v.dependencies + runtimeDeps.map { it.idForBazel }).distinct(),
                                            exportedDependencies = (v.exportedDependencies + compileDeps.map { it.idForBazel }).distinct(),
                                        ) ?: MultiplatformLibrary.Resolved(
                                            id = actualNode.idForBazel,
                                            klib = klib,
                                            sourceJar = sourceJar,
                                            dependencies = runtimeDeps.map { it.idForBazel }.distinct(),
                                            exportedDependencies = compileDeps.map { it.idForBazel }.distinct(),
                                        )
                                    }
                                }

                                else -> error("expected at most one klib for dependency ${actualNode.idForBazel}, got: ${klibs}")
                            }
                        }
                    }
                }

                resolved.values.toSet()
            }

            else -> {
                error(buildString {
                    appendLine("failed to resolve with:")
                    errors.flatMap { it.detailedMessage.lines() }.forEach {
                        appendLine(it)
                    }
                })
            }
        }
    }
}

private val MavenDependencyNode.idForBazel get() = "$group:$module:${resolvedVersion().orUnspecified()}"

private suspend fun MavenDependencyNode.actualWasmJsMavenDependency(): MavenDependencyNode =
    actualMavenDependencyOfVariantsMatching { it.klib() || it.sourceJar() }

private fun MultiplatformLibraryArtifact.cacheKey(): String {
    val url = urls.firstOrNull() ?: error("Resolved artifact does not contain any URLs: $this")
    return "$groupId|$artifactId|$version|${url.basenameFromUrl()}"
}

private fun String.basenameFromUrl(): String {
    return substringBefore('?').substringBefore('#').substringAfterLast('/')
}

private fun DependencyNode.resolutionErrors(): List<Message> {
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
    val originalDep = this.dependency as MavenDependencyImpl
    val availableAts =
        originalDep.variants.filter { attributeMatcher(it.attributes) }.map { it.`available-at` }.toSet()
    return when {
        availableAts.all { it == null } -> this // no variant indirection, it means that dependency is the actual one we need to consider
        availableAts.singleOrNull() == null -> error("all matched variants must point to the same Maven dependency, but got: ${originalDep.variants}")
        else -> { // all variants are pointing to the same Maven dependency, e.g. the `-wasm-js` one, that's the one we must consider here
            val availableAt = availableAts.single()
            children.filterIsInstance<MavenDependencyNode>().first {
                it.group == availableAt.group && it.module == availableAt.module && it.dependency.version == availableAt.version
            }
        }
    }
}

@Suppress("INVISIBLE_REFERENCE")
private suspend fun MavenDependencyNode.filesMatching(
    repositoryUrls: List<String>,
    attributeMatcher: (Map<String, String>) -> Boolean,
): List<MultiplatformLibraryArtifact> {
    val dependency = this.dependency as MavenDependencyImpl
    return dependency.variants.filter { variant ->
        attributeMatcher(variant.attributes)
    }.flatMap { variant ->
        variant.files.map { file ->
            MultiplatformLibraryArtifact(
                sha256checksum = file.sha256,
                groupId = group.orUnspecified(),
                artifactId = module,
                version = resolvedVersion().orUnspecified(),
                urls = urlsFor(file.url, repositoryUrls),
            )
        }
    }.distinctBy { artifact ->
        artifact.cacheKey()
    }
}

private fun MavenDependencyNode.urlsFor(fileUrl: String, repositoryUrls: List<String>): List<String> = artifactUrls(
    group = group,
    module = module,
    version = resolvedVersion().orUnspecified(),
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

private fun String.toMavenNode(context: Context): MavenDependencyNodeWithContext {
    val isBom = startsWith("bom:")
    val parts = removePrefix("bom:").trim().split(":")
    val group = parts[0]
    val module = parts[1]
    val version = when {
        parts.size > 2 -> parts[2]
        else -> null
    }
    return context.toMavenDependencyNode(
        coordinates = MavenCoordinates(
            groupId = group,
            artifactId = module,
            version = version,
        ),
        isBom = isBom,
    )
}

internal data class ResolvableLibrary(
    val mavenCoordinates: String,
) : Comparable<ResolvableLibrary> {
    override fun compareTo(other: ResolvableLibrary): Int = mavenCoordinates.compareTo(other.mavenCoordinates)
}
