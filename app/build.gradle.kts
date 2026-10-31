import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Penandatanganan rilis: data kunci dibaca dari keystore.properties di folder proyek (lihat keystore.properties.example).
// File itu dan keystore-nya TIDAK boleh masuk git. Tanpa file itu, build debug tetap jalan seperti biasa.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.build.mythos"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.build.mythos"
        minSdk = 26
        targetSdk = 34
        versionCode = 54
        versionName = "2.39"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug memakai keystore yang sama bila keystore.properties ada: tanpa ini tiap mesin (HP, GitHub Actions)
        // menandatangani dengan debug.keystore sendiri, dan Android menolak upgrade di atas versi dari mesin lain.
        debug {
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
            if (!hasReleaseKey) {
                logger.warn("keystore.properties tidak ditemukan: APK rilis akan TIDAK ditandatangani dan tidak bisa dipasang.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    // org.json asli untuk tes JVM: stub android.jar melempar "not mocked" pada JSONObject.
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
