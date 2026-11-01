package com.build.mythos

/** Generator proyek Android minimal yang dapat dibangun di GitHub Actions tanpa repo template eksternal. */
internal object StudioProject {
    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_-]{1,39}$")
    private val PKG = Regex("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){1,5}$")

    fun safeName(raw: String): String? = raw.trim().takeIf { NAME.matches(it) }
    fun safePackage(raw: String): String? = raw.trim().takeIf { PKG.matches(it) }
    fun safeFolder(raw: String): String? = BuildApk.safeFolder(raw)

    private fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    fun setupCommand(folder: String, appName: String, packageName: String, workspaceLabel: String = "~/MYTHOS"): String {
        val root = "${appName.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-studio"
        val files = linkedMapOf(
            "settings.gradle.kts" to """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "$root"
include(":app")
""",
            "build.gradle.kts" to """plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
""",
            "gradle.properties" to """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
""",
            "app/build.gradle.kts" to """plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "$packageName"
    compileSdk = 34
    defaultConfig {
        applicationId = "$packageName"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
""",
            "app/src/main/AndroidManifest.xml" to """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:theme="@style/AppTheme" android:label="$appName" android:allowBackup="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""",
            "app/src/main/res/values/styles.xml" to """<resources>
    <style name="AppTheme" parent="Theme.MaterialComponents.DayNight.DarkActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:colorAccent">#6750A4</item>
    </style>
</resources>
""",
            "app/src/main/java/${packageName.replace('.', '/')}/MainActivity.kt" to """package $packageName

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "$appName\n\nProyek dibuat oleh AI Software Engineering Studio."
            textSize = 20f
            setPadding(48, 48, 48, 48)
        })
    }
}
""",
            "app/src/test/java/${packageName.replace('.', '/')}/SmokeTest.kt" to """package $packageName

import org.junit.Assert.assertTrue
import org.junit.Test

class SmokeTest {
    @Test fun proyekDasarAktif() { assertTrue(true) }
}
""",
            ".github/workflows/build-apk.yml" to """name: Build APK
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: android-actions/setup-android@v3
        with: { packages: 'platforms;android-34 build-tools;34.0.0 platform-tools' }
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: '8.9' }
      - run: gradle assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with: { name: agentapp-debug, path: app/build/outputs/apk/debug/*.apk }
      - run: gradle testDebugUnitTest --no-daemon
""",
            "README.md" to """# $appName

Proyek ini dibuat oleh AI Software Engineering Studio.

## Struktur
- app/src/main/java/${packageName.replace('.', '/')}/ : source Kotlin (kelas PascalCase)
- app/src/test/java/ : unit test
- .github/workflows/build-apk.yml : build APK debug di GitHub Actions
- info.json : metadata proyek (nama, package, dependensi utama)

## Alur kerja
Ubah source -> jalankan test -> build APK debug.

Catatan: kegagalan alat agen dicatat di $workspaceLabel/logs/error_summary.log.
""",
            "info.json" to """{
  "name": "$appName",
  "package": "$packageName",
  "type": "android-app",
  "language": "kotlin",
  "build": "github-actions (gradle assembleDebug)",
  "main_dependencies": ["com.android.application 8.5.2", "kotlin-android 1.9.24"],
  "folders": { "source": "app/src/main/java", "tests": "app/src/test/java", "workflow": ".github/workflows" }
}
"""
        )
        val commands = StringBuilder("mkdir -p ").append(shq(folder)).append(" && cd ").append(shq(folder)).append(" && mkdir -p app/src/main/res/values app/src/main/java app/src/test/java .github/workflows && ")
        for ((path, content) in files) {
            commands.append("mkdir -p \$(dirname ").append(shq(path)).append(") && printf %s '").append(b64(content)).append("' | base64 -d > ").append(shq(path)).append(" && ")
        }
        commands.append("printf '%s\\n' 'STUDIO_PROJECT=ok' 'STUDIO_NEXT=build_apk' ")
        return commands.toString()
    }

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
