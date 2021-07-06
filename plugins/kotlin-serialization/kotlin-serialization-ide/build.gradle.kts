
description = "Kotlinx Serialization IDEA Plugin"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compileOnly(project(":js:js.translator"))

    compile(project(":kotlinx-serialization-compiler-plugin"))
    compile(project(":idea"))
    compile(project(":idea:idea-gradle"))
    compile(project(":idea:idea-maven"))
    compile(project(":plugins:annotation-based-compiler-plugins-ide-support"))
    compileOnly(intellijDep())
    compileOnly(intellijPluginDep("java"))
    excludeInAndroidStudio(rootProject) { compileOnly(intellijPluginDep("maven")) }
    compileOnly(intellijPluginDep("gradle"))

    testApi(toolsJar())
    testApi(projectTests(":idea"))
    testApi(projectTests(":compiler:tests-common"))
    testApi(projectTests(":idea:idea-test-framework"))
    testApi(project(":kotlin-test:kotlin-test-junit"))
    testApi(commonDep("junit:junit"))
    testApi(projectTests(":idea:idea-frontend-independent"))

    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.1.0")

    testRuntimeOnly(intellijCoreDep()) { includeJars("intellij-core") }

    testRuntime(project(":allopen-ide-plugin"))
    testRuntime(project(":plugins:parcelize:parcelize-ide"))
    testRuntime(project(":sam-with-receiver-ide-plugin"))
    testRuntime(project(":noarg-ide-plugin"))
    testRuntime(project(":plugins:lombok:lombok-ide-plugin"))
    testApi(intellijDep())
    testApi(intellijPluginDep("java"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

runtimeJar()
testsJar()

projectTest(parallel = true) {
    workingDir = rootDir
}
