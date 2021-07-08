plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    api(project(":kotlin-annotations-jvm"))
    api(project(":compiler:backend"))
    api(project(":compiler:ir.tree"))
    api(project(":compiler:ir.backend.common"))
    api(project(":compiler:backend.common.jvm"))
    compileOnly(project(":compiler:ir.tree.impl"))
    compileOnly(intellijCoreDep()) { includeJars("intellij-core", "asm-all", "guava", rootProject = rootProject) }
    compileOnly(intellijDep()) { includeJars("trove4j", rootProject = rootProject) }
}

sourceSets {
    "main" {
        projectDefault()
    }
    "test" {}
}
