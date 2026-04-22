KtWasmJsInfo = provider(
    doc = "Information required to compile Kotlin to Wasm/JS",
    fields = {
        "compile_klibs": "depset(File): klibs visible on the compiler classpath, added to the compile library path of *direct* dependents",
        "link_klibs": "depset(File): klibs that must be given to the Wasm/JS linker, propagated transitively",
        "klib": "File: klib of this module",
        "source_jar": "File: sources of this module",
    },
)

def _kmp_wasmjs_import_impl(ctx):
    compile_klibs = ctx.files.compile_klibs
    link_klibs = ctx.files.link_klibs
    source_jars = ctx.files.source_jars
    compile_dep_klibs = [dep[KtWasmJsInfo].compile_klibs for dep in ctx.attr.compile_deps]
    link_dep_klibs = [dep[KtWasmJsInfo].link_klibs for dep in ctx.attr.deps + ctx.attr.link_deps]
    compile_klibs_depset = depset(compile_klibs, transitive = compile_dep_klibs)
    link_klibs_depset = depset(link_klibs, transitive = link_dep_klibs)
    klib = compile_klibs[0] if compile_klibs else None
    if klib == None and link_klibs:
        klib = link_klibs[0]
    return [
        KtWasmJsInfo(
            compile_klibs = compile_klibs_depset,
            link_klibs = link_klibs_depset,
            klib = klib,
            source_jar = source_jars[0] if source_jars else None,
        ),
        DefaultInfo(
            files = depset(source_jars, transitive = [compile_klibs_depset, link_klibs_depset]),
        ),
    ]

kmp_wasmjs_import = rule(
    implementation = _kmp_wasmjs_import_impl,
    attrs = {
        "compile_klibs": attr.label_list(
            allow_files = True,
            doc = "KLIBs exposed on the compile classpath of direct dependents.",
        ),
        "link_klibs": attr.label_list(
            allow_files = True,
            doc = "KLIBs that must be given to the Wasm/JS linker.",
        ),
        "source_jars": attr.label_list(
            allow_files = True,
            doc = "Source jars exposed by this imported dependency.",
        ),
        "deps": attr.label_list(
            providers = [KtWasmJsInfo],
            doc = "Runtime dependencies of this imported dependency.",
        ),
        "compile_deps": attr.label_list(
            providers = [KtWasmJsInfo],
            doc = "Compile-time dependencies of this imported dependency.",
        ),
        "link_deps": attr.label_list(
            providers = [KtWasmJsInfo],
            doc = "Link-time dependencies of this imported dependency.",
        ),
    },
    provides = [KtWasmJsInfo],
)
