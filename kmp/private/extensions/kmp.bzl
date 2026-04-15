def _kmp_deps_repository_impl(ctx, deps):
    return struct()

_kmp_deps_repository = repository_rule(
    implementation = _kmp_deps_repository_impl,
    attrs = {
        "deps": attr.string_list(
            default = [],
            doc = "List of `group:artifact:version` dependencies to resolve.",
        ),
    },
)

def _read_configure_tag(module_ctx):
    root_tags = []
    non_root_tags = []
    for mod in module_ctx.modules:
        if mod.is_root:
            root_tags.extend(mod.tags.configure)
        else:
            non_root_tags.extend(mod.tags.configure)

    if len(root_tags) > 1:
        fail("Only one windows_sdk.configure(...) tag is supported in the root module.")
    if root_tags:
        return root_tags[0]
    if non_root_tags:
        return non_root_tags[0]
    return struct(
        deps = [],
    )

def _kmp_extension_impl(module_ctx):
    config = _read_configure_tag(module_ctx)

    repository_name = "kmp_deps"
    some_return_thing = _kmp_deps_repository(
        name = repository_name,
        deps = config.deps,
    )

    return module_ctx.extension_metadata(
        # TODO: facts = {_INSTALLER_MANIFEST_FACTS_KEY: installer_manifest},
        root_module_direct_deps = [repository_name],
        root_module_direct_dev_deps = [],
    )

kmp = module_extension(
    implementation = _kmp_extension_impl,
    tag_classes = {
        "configure": tag_class(attrs = {
            "deps": attr.string_list(
                default = [],
                doc = "List of `group:artifact:version` dependencies to resolve.",
            ),
        }),
    },
    doc = "MSVC runtime extension.",
)
