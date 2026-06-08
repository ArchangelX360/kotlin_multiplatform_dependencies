load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
load("@bazel_tools//tools/build_defs/repo:utils.bzl", "get_auth")

_DEFAULT_REPOSITORIES = [
    "https://repo1.maven.org/maven2",
]

_NETRC_ENV = "NETRC"
_REPOSITORY_CREDENTIALS_FILE = "repository-credentials.json"
_RESOLUTION_FACTS_VERSION = "resolution.v17"
_RESOLVER_REPOSITORY_NAME = "kmp_resolver"
_RESOLVER_LABEL = "@%s//:resolver" % _RESOLVER_REPOSITORY_NAME
_RESOLVER_VERSION = "0.0.1"
_RESOLVER_SHA256 = "0000000000000000000000000000000000000000000000000000000000000000"
_RESOLVER_URLS = [
    "https://github.com/ArchangelX360/kotlin_multiplatform_dependencies/releases/download/resolver-v%s/kmp-resolver-%s.tar.gz" % (_RESOLVER_VERSION, _RESOLVER_VERSION),
]

_EMPTY_RESOLUTION_JSON = json.encode({
    "askedCoordinates": [],
    "askedRepositories": [],
    "libraries": {},
})

def _kmp_deps_repository_impl(repository_ctx):
    repository_ctx.file("BUILD.bazel", repository_ctx.attr.build_file_content)

_kmp_deps_repository = repository_rule(
    implementation = _kmp_deps_repository_impl,
    attrs = {
        "build_file_content": attr.string(
            mandatory = True,
            doc = "Generated BUILD file content.",
        ),
    },
)

def _materialize_resolution(resolution):
    libraries = _manifest_libraries(resolution)
    target_names = _library_target_names(libraries)

    materialized_targets = []
    for library_id in sorted(libraries.keys()):
        library = _validated_library(library_id, libraries[library_id])
        klib = _required_library_artifact(library, "klib")
        source_jar = library.get("sourceJar")
        materialized_targets.append({
            "coordinate": library_id,
            "name": target_names[library_id],
            "klib": _artifact_label(klib),
            "source_jar": None if source_jar == None else _artifact_label(source_jar),
            "deps": _dependency_labels(library, "dependencies", target_names),
            "exported_deps": _dependency_labels(library, "exportedDependencies", target_names),
        })

    return struct(
        aliases = _library_aliases(libraries, target_names),
        targets = materialized_targets,
    )

def _validated_library(library_id, library):
    if type(library) != "dict":
        fail("Unexpected library entry for %s: expected dict, got %s." % (library_id, type(library)))

    declared_id = library.get("id")
    if type(declared_id) != "string" or not declared_id:
        fail("Library entry for %s is missing id." % library_id)
    if declared_id != library_id:
        fail("Library key does not match library id: key=%s id=%s" % (library_id, declared_id))

    return library

def _required_library_artifact(library, field):
    artifact = library.get(field)
    if type(artifact) != "dict":
        fail("Library %s is missing %s artifact." % (library.get("id", "<unknown>"), field))
    return artifact

def _manifest_libraries(resolution):
    libraries = resolution.get("libraries", {})
    if type(libraries) != "dict":
        fail("Unexpected resolution shape: expected libraries dict.")
    return libraries

def _library_target_names(libraries):
    target_names = {}
    used_names = {}
    for library_id in sorted(libraries.keys()):
        library = _validated_library(library_id, libraries[library_id])
        variant_id = _library_variant_id(library_id, library)
        target_name = _build_target_name(variant_id)
        if target_name in used_names:
            fail("Library target name collision for '%s' and '%s': %s" % (
                used_names[target_name],
                library_id,
                target_name,
            ))
        used_names[target_name] = library_id
        target_names[library_id] = target_name
        target_names[variant_id] = target_name
    return target_names

def _library_aliases(libraries, target_names):
    real_names = {}
    for target_name in target_names.values():
        real_names[target_name] = True

    aliases = {}
    for library_id in sorted(libraries.keys()):
        library = _validated_library(library_id, libraries[library_id])
        variant_id = _library_variant_id(library_id, library)
        target_name = target_names[library_id]

        _add_alias(aliases, real_names, _versionless_target_name(variant_id), target_name, variant_id)

        if library_id != variant_id:
            _add_alias(aliases, real_names, _build_target_name(library_id), target_name, library_id)
            _add_alias(aliases, real_names, _versionless_target_name(library_id), target_name, library_id)

    return [
        {
            "actual": ":%s" % aliases[name],
            "name": name,
        }
        for name in sorted(aliases.keys())
    ]

def _add_alias(aliases, real_names, alias_name, target_name, coordinate):
    if alias_name == target_name:
        return
    if alias_name in real_names:
        fail("Alias target name for %s collides with a real generated target: %s" % (coordinate, alias_name))

    existing = aliases.get(alias_name)
    if existing != None and existing != target_name:
        fail("Alias target name for %s is ambiguous: %s points to both %s and %s" % (
            coordinate,
            alias_name,
            existing,
            target_name,
        ))
    aliases[alias_name] = target_name

def _library_variant_id(library_id, library):
    variant_id = library.get("variantId")
    if type(variant_id) != "string" or not variant_id:
        fail("Library %s is missing variantId." % library_id)
    return variant_id

def _versionless_target_name(coordinate):
    parts = _maven_coordinate_parts(coordinate)
    return _build_target_name("%s:%s" % (parts[0], parts[1]))

def _maven_coordinate_parts(coordinate):
    parts = coordinate.split(":")
    if len(parts) != 3 or not parts[0] or not parts[1] or not parts[2]:
        fail("Expected Maven coordinate group:artifact:version, got: %s" % coordinate)
    return parts

def _dependency_labels(library, field, target_names):
    labels = []
    for dependency_id in library.get(field, []):
        target_name = target_names.get(dependency_id)
        if target_name == None:
            fail("Library %s references unknown %s dependency: %s" % (
                library.get("id", "<unknown>"),
                field,
                dependency_id,
            ))
        labels.append(":%s" % target_name)
    return _dedupe(labels)

def _artifact_label(artifact):
    return "@%s//file" % _artifact_repository_name(artifact)

def _artifact_key(artifact):
    if type(artifact) != "dict":
        fail("Unexpected artifact entry: expected dict, got %s." % type(artifact))
    return "%s|%s|%s|%s" % (
        _required_artifact_string(artifact, "groupId"),
        _required_artifact_string(artifact, "artifactId"),
        _required_artifact_string(artifact, "version"),
        _artifact_basename(artifact),
    )

def _required_artifact_string(artifact, field):
    value = artifact.get(field)
    if type(value) != "string" or not value:
        fail("Resolved artifact is missing %s: %s" % (field, artifact))
    return value

def _artifact_basename(artifact):
    urls = artifact.get("urls", [])
    if type(urls) != "list" or not urls:
        fail("Resolved artifact does not contain any URLs: %s" % artifact)
    return _basename_from_url(urls[0])

def _basename_from_url(url):
    stripped = url.split("?", 1)[0].split("#", 1)[0]
    return stripped.rsplit("/", 1)[-1]

def _collect_artifacts(resolution):
    artifacts = {}
    libraries = _manifest_libraries(resolution)
    for library_id in sorted(libraries.keys()):
        library = _validated_library(library_id, libraries[library_id])
        _add_artifact(artifacts, _required_library_artifact(library, "klib"))
        source_jar = library.get("sourceJar")
        if source_jar != None:
            _add_artifact(artifacts, source_jar)
    return artifacts

def _add_artifact(artifacts, artifact):
    artifact_key = _artifact_key(artifact)
    existing = artifacts.get(artifact_key)
    if existing != None:
        if existing.get("sha256checksum") != artifact.get("sha256checksum"):
            fail("Artifact checksum collision for %s" % artifact_key)
        return
    artifacts[artifact_key] = artifact

def _artifact_repository_name(artifact):
    artifact_id = _required_artifact_string(artifact, "artifactId")
    version = _required_artifact_string(artifact, "version")
    classifier = _artifact_classifier(artifact, artifact_id, version)
    classifier_suffix = "" if not classifier else "-%s" % _repository_name_part(classifier)
    return "%s-%s-%s%s_http" % (
        _repository_name_part(_required_artifact_string(artifact, "groupId")),
        _repository_name_part(artifact_id),
        _repository_name_part(version),
        classifier_suffix,
    )

def _artifact_classifier(artifact, artifact_id, version):
    stem = _strip_file_extension(_artifact_basename(artifact))
    prefix = "%s-%s" % (artifact_id, version)
    if stem == prefix:
        return ""
    if stem.startswith(prefix + "-"):
        return stem[len(prefix) + 1:]
    return stem

def _strip_file_extension(basename):
    if "." not in basename:
        return basename
    return basename.rsplit(".", 1)[0]

def _repository_name_part(value):
    allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
    chars = []
    previous_was_separator = False
    for i in range(len(value)):
        c = value[i]
        if c in allowed:
            chars.append(c)
            previous_was_separator = False
        elif c == "." or not previous_was_separator:
            chars.append("_")
            previous_was_separator = True
    result = "".join(chars).strip("_")
    return result or "artifact"

def _dedupe(items):
    seen = {}
    result = []
    for item in items:
        if item in seen:
            continue
        seen[item] = True
        result.append(item)
    return result

def _render_build_file(materialized):
    return "\n".join([
        "load(\"@kmp//kmp:wasmjs.bzl\", \"kmp_wasmjs_import\")",
        "",
        "package(default_visibility = [\"//visibility:public\"])",
        "",
        _render_targets_block(materialized.targets),
        "",
        _render_aliases_block(materialized.aliases),
        "",
    ])

def _render_targets_block(targets):
    blocks = []
    for target in targets:
        lines = [
            "# %s" % target["coordinate"],
        ]
        lines.extend(_render_wasmjs_import(target))
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)

def _render_aliases_block(aliases):
    blocks = []
    for alias in aliases:
        blocks.append("\n".join([
            "alias(",
            "    name = %s," % _quote(alias["name"]),
            "    actual = %s," % _quote(alias["actual"]),
            ")",
        ]))
    return "\n\n".join(blocks)

def _render_wasmjs_import(target):
    lines = [
        "kmp_wasmjs_import(",
        "    name = %s," % _quote(target["name"]),
        "    klib = %s," % _quote(target["klib"]),
    ]
    if target["source_jar"] != None:
        lines.append("    source_jar = %s," % _quote(target["source_jar"]))
    lines.extend(_render_label_list_attr("deps", target["deps"]))
    lines.extend(_render_label_list_attr("exported_deps", target["exported_deps"]))
    lines.append(")")
    return lines

def _render_label_list_attr(name, values):
    if not values:
        return []
    lines = [
        "    %s = [" % name,
    ]
    for value in values:
        lines.append("        %s," % _quote(value))
    lines.append(
        "    ],",
    )
    return lines

def _quote(value):
    return "\"%s\"" % value.replace("\\", "\\\\").replace("\"", "\\\"")

def _build_target_name(value):
    allowed = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
    chars = []
    previous_was_separator = False
    for i in range(len(value)):
        c = value[i]
        if c in allowed:
            chars.append(c.lower())
            previous_was_separator = False
        elif not previous_was_separator:
            chars.append("_")
            previous_was_separator = True
    result = "".join(chars).strip("_")
    return result or "library"

def _read_configure_tag(module_ctx):
    root_tags = []
    non_root_tags = []
    for mod in module_ctx.modules:
        if mod.is_root:
            root_tags.extend(mod.tags.configure)
        else:
            non_root_tags.extend(mod.tags.configure)

    if len(root_tags) > 1:
        fail("Only one kmp.configure(...) tag is supported in the root module.")
    if root_tags:
        return root_tags[0]
    if non_root_tags:
        return non_root_tags[0]
    return struct(
        deps = [],
        repositories = _DEFAULT_REPOSITORIES,
    )

def _resolve_with_facts(module_ctx, config):
    if not config.deps:
        return _EMPTY_RESOLUTION_JSON

    fact_key = _resolution_fact_key(config)
    if fact_key in module_ctx.facts:
        return module_ctx.facts[fact_key]

    return _resolve_fresh(module_ctx, config)

def _resolve_fresh(module_ctx, config):
    resolution_path = "resolution.json"
    module_ctx.file(resolution_path, "")
    resolver = module_ctx.path(Label(_RESOLVER_LABEL))
    module_ctx.watch(resolver)

    args = [
        resolver,
        "--output-manifest-file",
        module_ctx.path(resolution_path),
    ]
    for dep in config.deps:
        args.extend(["--coordinate", dep])
    for repository in config.repositories:
        args.extend(["--repository", repository])

    repository_credentials = _repository_credentials(module_ctx, config.repositories)
    if repository_credentials:
        module_ctx.file(_REPOSITORY_CREDENTIALS_FILE, json.encode(repository_credentials), executable = False)
        args.extend(["--repository-credentials-file", module_ctx.path(_REPOSITORY_CREDENTIALS_FILE)])

    result = module_ctx.execute(
        args,
        quiet = True,
        timeout = 600,
    )
    if result.return_code:
        fail("KMP resolver failed with exit code %s.\nstdout:\n%s\nstderr:\n%s" % (
            result.return_code,
            result.stdout,
            result.stderr,
        ))

    return module_ctx.read(resolution_path)

def _repository_credentials(module_ctx, repositories):
    auth = get_auth(_auth_context(module_ctx), repositories)
    credentials = []
    for repository in repositories:
        repository_auth = auth.get(repository)
        if repository_auth == None:
            continue

        auth_type = repository_auth.get("type")
        login = repository_auth.get("login")
        password = repository_auth.get("password")
        if auth_type != "basic" or not login or not password:
            fail("KMP resolver supports only basic repository auth for %s, but get_auth returned %s auth." % (
                repository,
                auth_type,
            ))

        credentials.append({
            "repositoryUrl": repository,
            "username": login,
            "password": password,
        })
    return credentials

def _auth_context(module_ctx):
    return struct(
        attr = struct(
            auth_patterns = {},
            netrc = module_ctx.getenv(_NETRC_ENV) or "",
        ),
        os = module_ctx.os,
        path = module_ctx.path,
        read = module_ctx.read,
    )

def _resolution_fact_key(config):
    return "%s|deps=%s|repositories=%s" % (
        _RESOLUTION_FACTS_VERSION,
        _list_key(config.deps),
        _list_key(config.repositories),
    )

def _list_key(values):
    return ",".join(["%d:%s" % (len(value), value) for value in values])

def _register_artifact_repositories(resolution):
    artifacts = _collect_artifacts(resolution)

    used_names = {}
    for artifact_key in sorted(artifacts.keys()):
        artifact = artifacts[artifact_key]
        urls = artifact.get("urls", [])
        repo_name = _artifact_repository_name(artifact)
        if repo_name in used_names and used_names[repo_name] != artifact_key:
            fail("Artifact repository name collision for '%s' and '%s': %s" % (
                used_names[repo_name],
                artifact_key,
                repo_name,
            ))
        used_names[repo_name] = artifact_key
        http_file(
            name = repo_name,
            downloaded_file_path = _basename_from_url(urls[0]),
            sha256 = artifact.get("sha256checksum") or "",
            urls = urls,
        )

def _kmp_extension_impl(module_ctx):
    config = _read_configure_tag(module_ctx)
    resolution_json = _resolve_with_facts(module_ctx, config)
    resolution = json.decode(resolution_json)
    _register_artifact_repositories(resolution)

    repository_name = "kmp_deps"
    _kmp_deps_repository(
        name = repository_name,
        build_file_content = _render_build_file(_materialize_resolution(resolution)),
    )

    facts = {_resolution_fact_key(config): resolution_json} if config.deps else {}
    if module_ctx.root_module_has_non_dev_dependency:
        return module_ctx.extension_metadata(
            root_module_direct_deps = [repository_name],
            root_module_direct_dev_deps = [],
            facts = facts,
        )
    else:
        return module_ctx.extension_metadata(
            root_module_direct_deps = [],
            root_module_direct_dev_deps = [repository_name],
            facts = facts,
        )

def _resolver_extension_impl(module_ctx):
    http_archive(
        name = _RESOLVER_REPOSITORY_NAME,
        sha256 = _RESOLVER_SHA256,
        urls = _RESOLVER_URLS,
    )

kmp = module_extension(
    implementation = _kmp_extension_impl,
    tag_classes = {
        "configure": tag_class(attrs = {
            "deps": attr.string_list(
                default = [],
                doc = "List of `group:artifact:version` dependencies to resolve.",
            ),
            "repositories": attr.string_list(
                default = _DEFAULT_REPOSITORIES,
                doc = "Maven repository URLs forwarded to the resolver.",
            ),
        }),
    },
    doc = "Kotlin Multiplatform dependency extension.",
)

resolver = module_extension(
    implementation = _resolver_extension_impl,
    doc = "Provides the private resolver executable repository used by the kmp extension.",
)
