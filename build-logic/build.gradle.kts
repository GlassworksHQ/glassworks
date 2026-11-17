// The conventions every module build file applies (#282): glassworks.android and glassworks.jvm, in src/main/kotlin.
plugins {
    `kotlin-dsl`
}

// The root build.gradle.kts puts AGP and KGP on the build's classpath (`apply false`), and the conventions run
// against those, so they compile against the versions it names there (as tools/release/lib-consumer-check.sh reads them).
val rootBuild = file("../build.gradle.kts").readText()
fun pluginVersion(id: String): String = Regex("""id\("${Regex.escape(id)}"\) version "([^"]+)"""").find(rootBuild)?.groupValues?.get(1)
    ?: throw GradleException("no version for $id in the root build.gradle.kts")

dependencies {
    compileOnly("com.android.tools.build:gradle:${pluginVersion("com.android.application")}")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:${pluginVersion("org.jetbrains.kotlin.jvm")}")
    testImplementation("junit:junit:4.13.2")
}
