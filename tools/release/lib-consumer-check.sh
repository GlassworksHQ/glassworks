#!/usr/bin/env bash
# Builds a fresh Android app at minSdk 23 against the libraries in mavenLocal, as someone would use
# them from Maven Central: dev.glassworks:esphome-api, esphome-api-android and improv, at the versions
# in libs/<name>/gradle.properties. Run it after `./gradlew publishToMavenLocal` (pr.yml, libs-publish).
# Writes only a temporary project, which it deletes; reads ~/.m2.
#   tools/release/lib-consumer-check.sh
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
version() { sed -n 's/^version=//p' "$repo/libs/$1/gradle.properties"; }
agp=$(sed -n 's/.*id("com.android.application") version "\([^"]*\)".*/\1/p' "$repo/build.gradle.kts")
[ -n "$agp" ] || { echo "no AGP version in build.gradle.kts" >&2; exit 1; }

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
mkdir -p "$dir/src/main/kotlin/example/consumer"

cat > "$dir/settings.gradle.kts" <<EOF
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("dev.glassworks") } }
        google()
        mavenCentral()
    }
}
rootProject.name = "consumer"
EOF

cat > "$dir/build.gradle.kts" <<EOF
plugins { id("com.android.application") version "$agp" }
android {
    namespace = "example.consumer"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    defaultConfig { minSdk = 23 }
}
dependencies {
    implementation("dev.glassworks:esphome-api:$(version esphome-api)")
    implementation("dev.glassworks:esphome-api-android:$(version esphome-api-android)")
    implementation("dev.glassworks:improv:$(version improv)")
}
EOF

cat > "$dir/src/main/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
EOF

cat > "$dir/src/main/kotlin/example/consumer/Use.kt" <<'EOF'
package example.consumer

import android.content.Context
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.MemoryKeyStore
import dev.glassworks.esphome.NoiseKeys
import dev.glassworks.esphome.SwitchEntity
import dev.glassworks.esphome.android.EspHomeMdns
import dev.glassworks.improv.SerialPacketReader

fun use(context: Context) {
    val identity = DeviceIdentity(
        name = "consumer", friendlyName = "Consumer", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "2026-11-15 12:00:00",
        manufacturer = "Example", model = "Consumer check", voiceFeatures = 0,
    )
    var on = false
    val server = ApiServer(ApiServer.DEFAULT_PORT, { identity }, listOf(SwitchEntity("lamp", "Lamp", get = { on }, set = { on = it })), NoiseKeys(MemoryKeyStore()))
    EspHomeMdns(context).start(identity, ApiServer.DEFAULT_PORT, encrypted = false, provisionable = false)
    SerialPacketReader()
    server.start()
}
EOF

"$repo/gradlew" -p "$dir" --no-daemon --quiet assembleDebug
echo "A minSdk 23 app built against esphome-api $(version esphome-api), esphome-api-android $(version esphome-api-android) and improv $(version improv) from mavenLocal."
