plugins {
    kotlin("jvm")
    id("jps-compatible")
}

project.updateJvmTarget("1.6")

dependencies {
    api(kotlinStdlib())
    api(project(":kotlin-scripting-common"))
    testApi(commonDep("org.jetbrains.kotlinx", "kotlinx-coroutines-core"))
    testApi(commonDep("junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

tasks.withType<org.jetbrains.kotlin.gradle.dsl.KotlinCompile<*>> {
    kotlinOptions.freeCompilerArgs += listOf(
        "-Xallow-kotlin-package", "-Xsuppress-deprecated-jvm-target-warning"
    )
}

publish()

runtimeJar()
sourcesJar()
javadocJar()
testsJar()
