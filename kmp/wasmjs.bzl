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
    # TODO: verify that A gets correctly B, C and D in its compile classpath
    # A.deps:
    #   - B
    #
    # B.deps:
    #   - C: exported
    #
    # C.deps:
    #   - D: exported

    klib = ctx.file.klib
    source_jar = ctx.file.source_jar
    compile_klibs_depset = depset([klib], transitive = [dep[KtWasmJsInfo].compile_klibs for dep in ctx.attr.exported_deps])
    link_klibs_depset = depset([klib], transitive = [dep[KtWasmJsInfo].link_klibs for dep in ctx.attr.deps + ctx.attr.exported_deps])
    source_jars = [] if source_jar == None else [source_jar]
    return [
        KtWasmJsInfo(
            compile_klibs = compile_klibs_depset,
            link_klibs = link_klibs_depset,
            klib = klib,
            source_jar = source_jar,
        ),
        DefaultInfo(
            files = depset(source_jars, transitive = [compile_klibs_depset, link_klibs_depset]),
        ),
    ]

kmp_wasmjs_import = rule(
    implementation = _kmp_wasmjs_import_impl,
    attrs = {
        "klib": attr.label(
            allow_single_file = True,
            doc = ".klib of this imported dependency, exposed to the compile library path of direct dependents.",
        ),
        "source_jar": attr.label(
            allow_single_file = True,
            doc = "Source jars exposed by this imported dependency.",
        ),
        "deps": attr.label_list(
            providers = [KtWasmJsInfo],
            doc = "Dependencies of this imported dependency, exposed to the link path of dependents transitively.",
        ),
        "exported_deps": attr.label_list(
            providers = [KtWasmJsInfo],
            doc = "Dependencies of this imported dependency, exposed to the link path of dependents transitively, and exposed to the compile library path of *direct* dependents.",
        ),
    },
    provides = [KtWasmJsInfo],
)
