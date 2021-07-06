import org.jetbrains.kotlin.gradle.dsl.KotlinCompile

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    testImplementation(intellijDep())

    api(kotlinStdlib())
    api(project(":idea:idea-core"))
    api(project(":compiler:frontend"))
    api(project(":compiler:frontend.java"))
    api(project(":compiler:light-classes"))
    api(project(":compiler:util"))
    compileOnly(intellijCoreDep()) { includeJars("intellij-core") }

    testApi(project(":idea"))
    testApi(projectTests(":idea:idea-test-framework"))
    testApi(project(":compiler:light-classes"))
    testApi(project(":kotlin-test:kotlin-test-junit"))
    testApi(commonDep("junit:junit"))

    testCompileOnly(intellijDep())

    testCompileOnly(intellijPluginDep("java"))
    testRuntimeOnly(intellijPluginDep("java"))

    testApi(project(":idea:idea-native")) { isTransitive = false }
    testApi(project(":idea:idea-gradle-native")) { isTransitive = false }

    testRuntimeOnly(toolsJar())
    testImplementation(project(":native:frontend.native"))
    testImplementation(project(":plugins:kapt3-idea")) { isTransitive = false }
    testImplementation(project(":idea:idea-jvm"))
    testImplementation(project(":idea:idea-android"))
    testImplementation(project(":plugins:android-extensions-ide"))
    testImplementation(project(":sam-with-receiver-ide-plugin"))
    testImplementation(project(":allopen-ide-plugin"))
    testImplementation(project(":noarg-ide-plugin"))
    testImplementation(project(":kotlin-reflect"))
    testImplementation(project(":kotlin-scripting-idea"))
    testImplementation(project(":kotlinx-serialization-ide-plugin"))
    testImplementation(project(":plugins:parcelize:parcelize-ide"))
    testImplementation(project(":plugins:lombok:lombok-ide-plugin"))
    testImplementation(intellijPluginDep("properties"))
    testImplementation(intellijPluginDep("gradle"))
    testImplementation(intellijPluginDep("Groovy"))
    testImplementation(intellijPluginDep("coverage"))
    Ide.IJ {
        testImplementation(intellijPluginDep("maven"))
        testImplementation(intellijPluginDep("repository-search"))
    }
    testImplementation(intellijPluginDep("android"))
    testImplementation(intellijPluginDep("smali"))
    testImplementation(intellijPluginDep("junit"))
    testImplementation(intellijPluginDep("testng"))
    testImplementation(intellijPluginDep("IntelliLang"))
    testImplementation(intellijPluginDep("testng"))
    testImplementation(intellijPluginDep("copyright"))
    testImplementation(intellijPluginDep("properties"))
    testImplementation(intellijPluginDep("java-i18n"))
    testImplementation(intellijPluginDep("java-decompiler"))
    testImplementation(project(":plugins:kapt3-idea")) { isTransitive = false }

    Ide.AS {
        testImplementation(intellijPluginDep("android-layoutlib"))
        testImplementation(intellijPluginDep("platform-images"))
    }
}

sourceSets {
    "main" {
        projectDefault()
        java.srcDir("newSrc")
    }
    "test" { projectDefault() }
}

projectTest(parallel = true) {
    dependsOn(":dist")
    workingDir = rootDir
}

testsJar()

val testForWebDemo by task<Test> {
    include("**/*JavaToKotlinConverterForWebDemoTestGenerated*")
    classpath = testSourceSet.runtimeClasspath
    workingDir = rootDir
}

val test: Test by tasks
test.apply {
    exclude("**/*JavaToKotlinConverterForWebDemoTestGenerated*")
    //dependsOn(testForWebDemo)
}

configureFreeCompilerArg(true, "-Xeffect-system")
configureFreeCompilerArg(true, "-Xnew-inference")

fun configureFreeCompilerArg(isEnabled: Boolean, compilerArgument: String) {
    if (isEnabled) {
        allprojects {
            tasks.withType<KotlinCompile<*>> {
                kotlinOptions {
                    freeCompilerArgs += listOf(compilerArgument)
                }
            }
        }
    }
}
