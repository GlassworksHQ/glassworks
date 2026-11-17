import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.Provider

/**
 * The libraries an APK carries, as sorted `group:name:version`, from its runtime classpath's resolved graph. The apps'
 * checkThirdPartyNotices tasks compare them with THIRD-PARTY-NOTICES.md.
 *
 * A dependency that didn't resolve fails with Gradle's error. Skipping it would leave the older versions it overrides,
 * and the check would ask for notices of libraries the APK doesn't ship.
 */
fun apkModules(runtimeClasspath: Provider<Configuration>): Provider<List<String>> = runtimeClasspath.flatMap { c ->
    c.incoming.resolutionResult.rootComponent.map { root ->
        val seen = sortedSetOf<String>()
        val todo = ArrayDeque(listOf(root))
        val visited = mutableSetOf<ResolvedComponentResult>()
        while (todo.isNotEmpty()) {
            val comp = todo.removeFirst()
            if (!visited.add(comp)) continue
            (comp.id as? ModuleComponentIdentifier)?.let { seen += "${it.group}:${it.module}:${it.version}" }
            for (d in comp.dependencies) when (d) {
                is ResolvedDependencyResult -> todo += d.selected
                is UnresolvedDependencyResult ->
                    throw GradleException("${c.name}: could not resolve ${d.attempted.displayName}", d.failure)
            }
        }
        seen.toList()
    }
}
