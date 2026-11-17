import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkModulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val repo by lazy { tmp.newFolder("repo") }

    private fun publish(module: String, version: String, vararg deps: String) {
        val dir = File(repo, "org/example/$module/$version").apply { mkdirs() }
        val depXml = deps.joinToString("") {
            val (g, a, v) = it.split(':')
            "<dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></dependency>"
        }
        File(dir, "$module-$version.pom").writeText(
            """<project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>$module</artifactId>""" +
                """<version>$version</version><packaging>pom</packaging><dependencies>$depXml</dependencies></project>""")
    }

    private fun modules(vararg deps: String): List<String> {
        val project: Project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("app")).build()
        project.repositories.maven { url = repo.toURI() }
        val classpath = project.configurations.register("releaseRuntimeClasspath")
        deps.forEach { project.dependencies.add("releaseRuntimeClasspath", it) }
        return apkModules(classpath).get()
    }

    @Test fun `lists every library once, at the version conflict resolution picked`() {
        publish("annotation", "1.6.0")
        publish("annotation", "1.11.0")
        publish("media", "1.0", "org.example:annotation:1.6.0")
        publish("engine", "1.0", "org.example:annotation:1.11.0")

        assertEquals(
            listOf("org.example:annotation:1.11.0", "org.example:engine:1.0", "org.example:media:1.0"),
            modules("org.example:media:1.0", "org.example:engine:1.0"))
    }

    // 2026-11-17: GeckoView didn't resolve on CI, and the check asked for notices of the older AndroidX annotation
    // versions GeckoView overrides instead of naming GeckoView.
    @Test fun `a library that doesn't resolve fails, naming it, instead of leaving the versions it overrides`() {
        publish("annotation", "1.6.0")
        publish("media", "1.0", "org.example:annotation:1.6.0")

        try {
            val listed = modules("org.example:media:1.0", "org.example:engine:1.0")
            fail("listed $listed without org.example:engine:1.0")
        } catch (e: GradleException) {
            assertTrue(e.message, e.message!!.contains("releaseRuntimeClasspath: could not resolve org.example:engine:1.0"))
        }
    }
}
