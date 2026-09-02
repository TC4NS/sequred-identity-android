import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load the release signing config from a local keystore.properties file that
// is NOT in version control. Falls back to environment variables so CI can
// sign without writing a file. If neither is present, the release build still
// assembles but is unsigned — useful for debug-style smoke tests of R8.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(FileInputStream(f))
}
fun signingProp(key: String, env: String): String? =
    keystoreProperties.getProperty(key) ?: System.getenv(env)

android {
    namespace = "co.sequred.identity"
    compileSdk = 35

    defaultConfig {
        applicationId = "co.sequred.identity"
        minSdk = 26          // Android 8.0 — Argon2id is fast enough on any 64-bit phone of this era.
        targetSdk = 35
        // versionCode scheme: MMmmpp  (M = major, m = minor, p = patch).
        // 0.1.0 = 10000, 0.1.1 = 10001, 0.2.0 = 10200, 1.0.0 = 100000.
        // Lets us ship hotfixes between minor releases without colliding.
        versionCode = 10200
        versionName = "0.2.0"

        // Only ship the 64-bit ABIs we cross-compile the Rust core for. Play
        // Store has required 64-bit since 2019, and bundling armv7 means
        // building a separate libsequred_core.so we don't actually use.
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        create("release") {
            val storePath = signingProp("storeFile", "SQ_STORE_FILE")
            val storePass = signingProp("storePassword", "SQ_STORE_PASSWORD")
            val keyAlias_ = signingProp("keyAlias", "SQ_KEY_ALIAS")
            val keyPass = signingProp("keyPassword", "SQ_KEY_PASSWORD")
            // Only wire up if all four are present. Otherwise gradle would
            // fail at configuration time even for non-release tasks.
            if (storePath != null && storePass != null && keyAlias_ != null && keyPass != null) {
                storeFile = rootProject.file(storePath)
                storePassword = storePass
                keyAlias = keyAlias_
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign only if signing keystore is configured. Allows ./gradlew
            // assembleRelease to dry-run R8 even without a key.
            val cfg = signingConfigs.getByName("release")
            if (cfg.storeFile != null) signingConfig = cfg
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // Compose BOM keeps the constellation of compose libs in sync.
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Force a modern fragment. The 1.2.5 pulled transitively by zxing-embedded
    // still asserts requestCode <= 16 bits, which crashes ZXing under the
    // androidx ActivityResultRegistry (which generates 32-bit request codes).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.core:core-splashscreen:1.0.1")
    // ZXing — fully offline QR scanner (no Google Play Services dependency,
    // important for GrapheneOS where Play is opt-in).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = true }
    implementation("com.google.zxing:core:3.5.3")

    // JSON wire format with the Rust core.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // UniFFI bindings rely on JNA at runtime.
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
