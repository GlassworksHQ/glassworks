// The Bluetooth proxy engine for Android: BLE scanning for an ESPHome Bluetooth proxy (esphome-api).
// No Pane API: the app's bluetooth-proxy pane wraps it (#121, #152). See README.md in this directory.
plugins {
    id("com.android.library")
    id("glassworks.android")
}

android {
    namespace = "dev.glassworks.esphome.bluetoothproxy"
    defaultConfig {
        minSdk = 23
    }
}

dependencies {
    api(project(":esphome-api"))
    // @ChecksSdkIntAtLeast for lint; class-retention, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // AndroidBleScannerTest: the Android glue on Android 10 (sdk 29), as the kiosk's own Robolectric tests.
    testImplementation("org.robolectric:robolectric:4.17")
}

// BluetoothProxyHomeAssistantTest runs esphome-api's GATT script (shared, not copied): a change to it reruns the tests.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("libs/esphome-api/src/test/resources/esphome_bluetooth_connections.py"))
        .withPropertyName("sharedEsphomeScript").withPathSensitivity(PathSensitivity.RELATIVE)
}
