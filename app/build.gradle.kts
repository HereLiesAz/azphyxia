import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // Apply the Compose Compiler plugin
    alias(libs.plugins.kotlin.compose)

    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)

}

// Read ACRA config from local.properties (keeps secrets out of version control)
val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) load(localPropsFile.inputStream())
}
val acraUrl: String = localProperties.getProperty("acra.url", "")
val acraToken: String = localProperties.getProperty("acra.token", "")
val tmdbApiKey: String = localProperties.getProperty("tmdb.api_key", "")
val traktClientId: String = localProperties.getProperty("TRAKT_CLIENT_ID", "")
val traktClientSecret: String = localProperties.getProperty("TRAKT_CLIENT_SECRET", "")

// Release signing. Prefers local.properties (local dev) then falls back to environment
// variables (CI, see .github/workflows/release.yml) — never committed either way.
fun releaseSigningProp(propKey: String, envKey: String): String =
    localProperties.getProperty(propKey) ?: System.getenv(envKey) ?: ""

val releaseStoreFile = releaseSigningProp("release.storeFile", "RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProp("release.storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProp("release.keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProp("release.keyPassword", "RELEASE_KEY_PASSWORD")
val hasReleaseKeystore = releaseStoreFile.isNotBlank() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank() &&
    rootProject.file(releaseStoreFile).exists()

// Fallback used only when no real release keystore is configured (see ci/README.md).
// Deliberately NOT AGP's implicit `debug` signingConfig: that keystore is generated
// per-machine on first use, so every ephemeral CI runner would get a different one —
// breaking Android's same-signer upgrade requirement (and AppUpdateManager's signature
// check) between consecutive releases. This fixed, checked-in keystore keeps every
// debug-signed CI build on the same signing identity until a real keystore is added.
val ciDebugKeystore = rootProject.file("ci/ci-debug.keystore")
val ciDebugKeystorePassword = "azphyxia-ci-debug"
val ciDebugKeystoreAlias = "azphyxia-ci-debug"

android {
    namespace = "com.lumera.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.azphyxia.app"
        minSdk = 26
        targetSdk = 34
        // The release workflow overrides these from the pushed tag (-PversionNameOverride)
        // and the GitHub Actions run number (-PversionCodeOverride) so a published release
        // actually reports the version it was tagged as, and versionCode keeps increasing —
        // Android's package installer rejects an upgrade whose versionCode doesn't increase.
        versionCode = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 9
        versionName = (project.findProperty("versionNameOverride") as String?) ?: "0.1.8-beta"

        // GitHub repository for auto-update system
        buildConfigField("String", "GITHUB_OWNER", "\"HereLiesAz\"")
        buildConfigField("String", "GITHUB_REPO", "\"Azphyxia\"")

        // ACRA crash reporting (loaded from local.properties)
        buildConfigField("String", "ACRA_URL", "\"$acraUrl\"")
        buildConfigField("String", "ACRA_TOKEN", "\"$acraToken\"")

        // TMDB API (loaded from local.properties)
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")

        // Trakt API (loaded from local.properties)
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"$traktClientId\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"$traktClientSecret\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            } else {
                storeFile = ciDebugKeystore
                storePassword = ciDebugKeystorePassword
                keyAlias = ciDebugKeystoreAlias
                keyPassword = ciDebugKeystorePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            resValue("string", "app_name", "Azphyxia Test")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real keystore when RELEASE_STORE_FILE etc. are configured (see
            // ci/README.md), otherwise the fixed ci/ci-debug.keystore fallback so
            // `assembleRelease` still produces an installable, consistently-signed
            // APK without secrets.
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

// Compose Compiler configuration for optimal performance.
// Strong skipping mode and intrinsic remember are enabled by default in current
// Compose Compiler versions, so those flags (removed from the DSL) no longer apply.
composeCompiler {
    // Stability configuration: tells the compiler which classes are effectively immutable
    // so it can skip recomposition when their instances haven't changed
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability_config.conf"))
}

dependencies {
    // 0. ASS/SSA subtitle renderer
    implementation(project(":assrender"))

    // 1. Android TV UI (Compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)


    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.tv.material)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // 2. Networking
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    // Pin explicitly: older converter-gson releases transitively pulled a very old Gson
    // (2.8.5, which predates JsonParser.parseString and other APIs this project uses
    // directly) since nothing else in the graph forced a newer version.
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.5.0")

    // 4. Image Loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 5. Database
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation(libs.androidx.compose.animation.core)
    ksp("androidx.room:room-compiler:2.8.4")

    // 6. Dependency Injection
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")

    // 7. Video Player
    implementation(project(":playbackcore"))
    implementation(files("../playbackcore/libs/lib-exoplayer-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-av1-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-ffmpeg-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-iamf-release.aar"))
    implementation(files("../playbackcore/libs/lib-decoder-mpegh-release.aar"))

    // 8. Testing & Debugging
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended")

    // OkHttp is already available via Retrofit, but declare explicitly for TorrServer API
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    // --- LOCAL WEB SERVER (used by remote input hub) ---
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // --- QR CODE GENERATION ---
    implementation("com.google.zxing:core:3.5.4")

    // --- ENCRYPTED SHARED PREFERENCES ---
    implementation("androidx.security:security-crypto:1.1.0")

    // --- CRASH REPORTING (ACRA) ---
    implementation("ch.acra:acra-http:5.13.1")
    implementation("ch.acra:acra-toast:5.13.1")

}
