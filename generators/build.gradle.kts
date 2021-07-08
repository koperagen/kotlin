plugins {
    kotlin("jvm")
    id("jps-compatible")
}

sourceSets {
    "main" { java.srcDirs("main") }
    "test" { projectDefault() }
}

fun extraSourceSet(name: String, extendMain: Boolean = true): Pair<SourceSet, Configuration> {
    val sourceSet = sourceSets.create(name) {
        java.srcDir(name)
    }
    val api = configurations[sourceSet.apiConfigurationName]
    if (extendMain) {
        dependencies { api(mainSourceSet.output) }
        configurations[sourceSet.runtimeOnlyConfigurationName]
            .extendsFrom(configurations.runtimeClasspath.get())
    }
    return sourceSet to api
}

val (builtinsSourceSet, builtinsApi) = extraSourceSet("builtins", extendMain = false)
val (evaluateSourceSet, evaluateApi) = extraSourceSet("evaluate")
val (interpreterSourceSet, interpreterApi) = extraSourceSet("interpreter")
val (protobufSourceSet, protobufApi) = extraSourceSet("protobuf")
val (protobufCompareSourceSet, protobufCompareApi) = extraSourceSet("protobufCompare")
val (wasmSourceSet, wasmApi) = extraSourceSet("wasm")

dependencies {
    // for GeneratorsFileUtil
    api(kotlinStdlib("jdk8"))
    api(intellijDep()) { includeJars("util") }

    builtinsApi("org.jetbrains.kotlin:kotlin-stdlib:$bootstrapKotlinVersion") { isTransitive = false }
    evaluateApi(project(":core:deserialization"))
    wasmApi(project(":wasm:wasm.ir"))
    wasmApi(kotlinStdlib())
    interpreterApi(project(":compiler:ir.tree"))
    interpreterApi(project(":compiler:ir.tree.impl"))
    interpreterApi(project(":compiler:ir.psi2ir"))
    protobufApi(kotlinStdlib())
    protobufCompareApi(projectTests(":kotlin-build-common"))

    testApi(builtinsSourceSet.output)
    testApi(evaluateSourceSet.output)
    testApi(interpreterSourceSet.output)
    testApi(protobufSourceSet.output)
    testApi(protobufCompareSourceSet.output)

    testApi(projectTests(":compiler:cli"))
    testApi(projectTests(":plugins:jvm-abi-gen"))
    testApi(projectTests(":plugins:android-extensions-compiler"))
    testApi(projectTests(":plugins:parcelize:parcelize-compiler"))
    testApi(projectTests(":kotlin-annotation-processing"))
    testApi(projectTests(":kotlin-annotation-processing-cli"))
    testApi(projectTests(":kotlin-allopen-compiler-plugin"))
    testApi(projectTests(":kotlin-noarg-compiler-plugin"))
    testApi(projectTests(":plugins:lombok:lombok-compiler-plugin"))
    testApi(projectTests(":kotlin-sam-with-receiver-compiler-plugin"))
    testApi(projectTests(":kotlinx-serialization-compiler-plugin"))
    testApi(projectTests(":plugins:fir:fir-plugin-prototype"))
    testApi(projectTests(":generators:test-generator"))
    testCompileOnly(project(":kotlin-reflect-api"))
    testImplementation(intellijDep()) { includeJars("idea_rt") }
    testImplementation(project(":kotlin-reflect"))

    if (Ide.IJ()) {
        testCompileOnly(jpsBuildTest())
        testApi(jpsBuildTest())
    }
}


projectTest(parallel = true) {
    workingDir = rootDir
}

val generateTests by generator("org.jetbrains.kotlin.generators.tests.GenerateTestsKt") {
    if (kotlinBuildProperties.getOrNull("attachedIntellijVersion") == null &&
        !kotlinBuildProperties.getBoolean("disableKotlinPluginModules", false)) {
        dependsOn(":generators:idea-generator:generateIdeaTests")
    }
    dependsOn(":generators:frontend-api-generator:generateFrontendApiTests")
}

val generateProtoBuf by generator("org.jetbrains.kotlin.generators.protobuf.GenerateProtoBufKt", protobufSourceSet)
val generateProtoBufCompare by generator("org.jetbrains.kotlin.generators.protobuf.GenerateProtoBufCompare", protobufCompareSourceSet)

val generateGradleOptions by generator("org.jetbrains.kotlin.generators.arguments.GenerateGradleOptionsKt")
val generateKeywordStrings by generator("org.jetbrains.kotlin.generators.frontend.GenerateKeywordStrings")

val generateBuiltins by generator("org.jetbrains.kotlin.generators.builtins.generateBuiltIns.GenerateBuiltInsKt", builtinsSourceSet)
val generateOperationsMap by generator("org.jetbrains.kotlin.generators.evaluate.GenerateOperationsMapKt", evaluateSourceSet)
val generateInterpreterMap by generator("org.jetbrains.kotlin.generators.interpreter.GenerateInterpreterMapKt", interpreterSourceSet)
val generateWasmIntrinsics by generator("org.jetbrains.kotlin.generators.wasm.WasmIntrinsicGeneratorKt", wasmSourceSet)

testsJar()
