
plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":kotlin-scripting-jvm"))
    api(project(":kotlin-scripting-dependencies"))
    api(project(":kotlin-scripting-dependencies-maven"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { }
}
