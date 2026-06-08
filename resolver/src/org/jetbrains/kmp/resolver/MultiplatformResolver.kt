package org.jetbrains.kmp.resolver

import kotlinx.serialization.Serializable
import org.jetbrains.amper.dependency.resolution.*
import org.jetbrains.amper.dependency.resolution.diagnostics.Message
import org.jetbrains.amper.dependency.resolution.diagnostics.Severity
import org.jetbrains.amper.dependency.resolution.diagnostics.detailedMessage
import java.net.URI
import java.nio.file.Path

internal typealias MultiplatformLibraryId = String

@Serializable
internal data class MultiplatformLibrary(
    val id: MultiplatformLibraryId,
    /**
     * ID of the resolved multiplatform library, usually [id] would point to the umbrella library like `kotlin-stdlib`,
     * while [variantId] would point to the variant library like `kotlin-stdlib-wasm-js`.
     */
    val variantId: MultiplatformLibraryId,

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
)

@Serializable
internal data class MultiplatformLibraryArtifact(
    val sha256checksum: String?,
    val groupId: String,
    val artifactId: String,
    val version: String,
    val urls: List<String>,
)

internal class MultiplatformResolver(
    cachePath: Path,
    private val repositories: List<MavenRepository>,
) {
    private val amperCachePath: Path = cachePath.resolve("amper-cache")

    internal suspend fun resolveMultiplatformComponentsOf(coordinatesBag: Collection<String>): List<MultiplatformLibrary> {
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
        Resolver().resolveDependencies(root = root, resolutionLevel = ResolutionLevel.NETWORK, transitive = true)
        val errors = root.resolutionErrors()
        return when {
            errors.isEmpty() -> {
                val repoUrls = repositories.map { repository -> repository.url }
                val resolved = mutableMapOf<String, MultiplatformLibrary>()
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
                            val errors = node.resolutionErrors()
                            when {
                                errors.isNotEmpty() -> {
                                    // TODO: throw probably?
                                    println(buildString {
                                        appendLine("WARN: resolution errors for node: ${node.idForBazel}")
                                        errors.forEach { appendLine("- ${it.detailedMessage}") }
                                    })
                                }

                                else -> {
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

                                            val (runtimeDeps, compileDeps) = actualChildren.partition {
                                                it.dependency.resolutionConfig.scope == ResolutionScope.RUNTIME
                                            }
                                            val initial by lazy {
                                                MultiplatformLibrary(
                                                    id = node.idForBazel,
                                                    variantId = actualNode.idForBazel,
                                                    klib = klib,
                                                    sourceJar = sourceJar,
                                                    dependencies = runtimeDeps.asBazelIds(),
                                                    exportedDependencies = compileDeps.asBazelIds(),
                                                )
                                            }
                                            val existing = resolved[node.idForBazel] ?: resolved[actualNode.idForBazel]
                                            val updated = existing?.let {
                                                val exportedDeps =
                                                    (existing.exportedDependencies + compileDeps.asBazelIds()).toSet()
                                                val deps = (existing.dependencies + runtimeDeps.asBazelIds()).toSet()
                                                    .minus(exportedDeps)
                                                it.copy(
                                                    dependencies = deps.sorted(),
                                                    exportedDependencies = exportedDeps.sorted(),
                                                )
                                            }

                                            // TODO: when supporting other targets than WasmJS, we may want the umbrella ID not to be considered the same as the variant ID
                                            // resolved[node.idForBazel] = updated ?: initial
                                            resolved[actualNode.idForBazel] = updated ?: initial
                                        }

                                        else -> error("expected at most one klib for dependency ${actualNode.idForBazel}, got: $klibs")
                                    }
                                }
                            }
                        }
                    }
                }

                resolved.values.sortedBy { it.id }
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

private fun List<MavenDependencyNode>.asBazelIds() = map { it.idForBazel }.sorted().distinct()

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

private fun DependencyNode.resolutionErrors(): List<Message> = messages.filter { it.severity >= Severity.ERROR }

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
    val availableAts = originalDep.variants.filter { attributeMatcher(it.attributes) }.map { it.`available-at` }.toSet()
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

private fun artifactUrls(
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
