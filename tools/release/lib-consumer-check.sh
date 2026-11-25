#!/usr/bin/env bash
# Builds fresh projects against the libraries in mavenLocal, as someone would use them from Maven
# Central (dev.glassworks:esphome-api, esphome-api-android, improv, sendspin, voice and ha-webrtc, at the versions in
# libs/<name>/gradle.properties). Run it after `./gradlew publishToMavenLocal` (pr.yml, libs-publish).
#   - two Android apps at minSdk 23 on the repo's AGP: one at compileSdk 37.2, one at compileSdk 23,
#     the AARs' minCompileSdk (the root build.gradle.kts)
#   - a JVM app on Kotlin 2.0.21, the oldest Kotlin the READMEs promise
# Writes only temporary projects, which it deletes; reads ~/.m2. Needs the android-23 and android-37.2
# SDK platforms.
#   tools/release/lib-consumer-check.sh
set -euo pipefail
repo=$(cd "$(dirname "$0")/../.." && pwd)
version() { sed -n 's/^version=//p' "$repo/libs/$1/gradle.properties"; }
agp=$(sed -n 's/.*id("com.android.application") version "\([^"]*\)".*/\1/p' "$repo/build.gradle.kts")
[ -n "$agp" ] || { echo "no AGP version in build.gradle.kts" >&2; exit 1; }
kotlin_min=2.0.21

dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT

# Google's mirror of Maven Central before Central itself, as settings.gradle.kts has it: Central answers CI's bursts
# with 429, and a dependency that no cache holds yet (ha-webrtc's libwebrtc) is fetched here.
repositories='repositories {
        mavenLocal { content { includeGroup("dev.glassworks") } }
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }'

# Android: :latest and :oldest differ only in compileSdk.
mkdir -p "$dir/android"
cat > "$dir/android/settings.gradle.kts" <<EOF
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    $repositories
}
rootProject.name = "consumer-android"
include(":latest", ":oldest")
EOF
echo "plugins { id(\"com.android.application\") version \"$agp\" apply false }" > "$dir/android/build.gradle.kts"
android_app() { # <module> <compileSdk block>
  local m="$dir/android/$1"
  mkdir -p "$m/src/main/kotlin/example/consumer"
  cat > "$m/build.gradle.kts" <<EOF
plugins { id("com.android.application") }
android {
    namespace = "example.consumer"
    $2
    defaultConfig { minSdk = 23 }
}
dependencies {
    implementation("dev.glassworks:esphome-api:$(version esphome-api)")
    implementation("dev.glassworks:esphome-api-android:$(version esphome-api-android)")
    implementation("dev.glassworks:improv:$(version improv)")
    implementation("dev.glassworks:sendspin:$(version sendspin)")
    implementation("dev.glassworks:voice:$(version voice)")
    implementation("dev.glassworks:ha-webrtc:$(version ha-webrtc)")
}
EOF
  echo '<manifest xmlns:android="http://schemas.android.com/apk/res/android" />' > "$m/src/main/AndroidManifest.xml"
  cat > "$m/src/main/kotlin/example/consumer/Use.kt" <<'EOF'
package example.consumer

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.DeviceIdentity
import dev.glassworks.esphome.MemoryKeyStore
import dev.glassworks.esphome.NoiseKeys
import dev.glassworks.esphome.SwitchEntity
import dev.glassworks.esphome.android.EspHomeMdns
import dev.glassworks.hawebrtc.HaCommandChannel
import dev.glassworks.hawebrtc.HaError
import dev.glassworks.hawebrtc.HaWebRtcPlayer
import dev.glassworks.improv.ImprovBleServer
import dev.glassworks.improv.SerialPacketReader
import dev.glassworks.improv.WifiProvisioner
import dev.glassworks.sendspin.MemoryStore
import dev.glassworks.sendspin.SendspinPlayer
import dev.glassworks.voice.VoiceConfig
import dev.glassworks.voice.VoiceEngine
import dev.glassworks.voice.WakeWords
import org.json.JSONObject

fun use(context: Context) {
    val identity = DeviceIdentity(
        name = "consumer", friendlyName = "Consumer", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "2026-11-15 12:00:00",
        manufacturer = "Example", model = "Consumer check", voiceFeatures = 0,
    )
    var on = false
    val server = ApiServer(ApiServer.DEFAULT_PORT, { identity }, listOf(SwitchEntity("lamp", "Lamp", get = { on }, set = { on = it })), NoiseKeys(MemoryKeyStore()))
    EspHomeMdns(context).start(identity, ApiServer.DEFAULT_PORT, encrypted = false, provisionable = false)
    val main = Handler(Looper.getMainLooper())
    WifiProvisioner(context, main).scan()
    ImprovBleServer(context, main, object : ImprovBleServer.Listener {
        override fun onRpcWrite(chunk: ByteArray) {}
        override fun onClientConnected(address: String) {}
        override fun onClientDisconnected(address: String) {}
        override fun onAdvertising(ok: Boolean, detail: String) {}
    }).maxNotify()
    SerialPacketReader()
    server.start()
    val settings = object : SendspinPlayer.Settings {
        override val server = SendspinPlayer.AUTO
        override val flac = true
        override val unpairedAccess = false
    }
    SendspinPlayer(context, MemoryStore(), SendspinPlayer.Identity("Consumer", "Consumer check", "Example", "Consumer 1.0"), settings).start()
    VoiceEngine(context, VoiceConfig(), WakeWords.DEFAULT) {}.start(muted = true)
    val channel = object : HaCommandChannel {
        override fun call(command: JSONObject, onResult: (Any?) -> Unit, onError: (HaError) -> Unit) = onError(HaError(HaError.NOT_CONNECTED, ""))
        override fun subscribe(command: JSONObject, onEvent: (JSONObject) -> Unit, onError: (HaError) -> Unit) =
            HaCommandChannel.Subscription { }
    }
    HaWebRtcPlayer(context, channel, "camera.front_door").start()
}
EOF
}
android_app latest 'compileSdk { version = release(37) { minorApiLevel = 2 } }'
# Below compileSdk 30, AGP compiles only Java 8 source, so an app there targets Java 8.
android_app oldest 'compileSdk = 23
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }'

# JVM on the oldest Kotlin the READMEs promise.
mkdir -p "$dir/jvm/src/main/kotlin"
cat > "$dir/jvm/settings.gradle.kts" <<EOF
pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    $repositories
}
rootProject.name = "consumer-jvm"
EOF
cat > "$dir/jvm/build.gradle.kts" <<EOF
plugins { id("org.jetbrains.kotlin.jvm") version "$kotlin_min" }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation("dev.glassworks:esphome-api:$(version esphome-api)") }
EOF
echo "kotlin.compiler.execution.strategy=in-process" > "$dir/jvm/gradle.properties"
cat > "$dir/jvm/src/main/kotlin/Use.kt" <<'EOF'
import dev.glassworks.esphome.ApiServer
import dev.glassworks.esphome.SensorEntity

fun main() {
    val uptime = SensorEntity("uptime", "Uptime", unit = "s", get = { 42f })
    println("${ApiServer.DEFAULT_PORT} ${uptime.objectId}")
}
EOF

"$repo/gradlew" -p "$dir/android" --no-daemon --quiet :latest:assembleDebug :oldest:assembleDebug
"$repo/gradlew" -p "$dir/jvm" --no-daemon --quiet compileKotlin
echo "Built against esphome-api $(version esphome-api), esphome-api-android $(version esphome-api-android), improv $(version improv),"
echo "sendspin $(version sendspin), voice $(version voice) and ha-webrtc $(version ha-webrtc):"
echo "minSdk 23 apps at compileSdk 37.2 and 23, and a Kotlin $kotlin_min JVM app."
