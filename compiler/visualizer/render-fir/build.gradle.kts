plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    api(project(":compiler:fir:raw-fir:psi2fir"))
    api(project(":compiler:fir:resolve"))
    api(project(":compiler:visualizer:common"))
    compileOnly(intellijCoreDep()) { includeJars("intellij-core") }
}

sourceSets {
    "main" { projectDefault() }
    "test" { }
}
