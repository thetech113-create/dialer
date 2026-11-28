import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.caller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.caller"
        minSdk = 26
        targetSdk = 35
        versionCode = 255
        versionName = "8.10.117"
    }

    // Release signing key. Play Protect treats a sensitive app (SMS / call log / contacts /
    // install-packages) signed with the shared public Android debug certificate as a strong
    // "harmful app" signal, because that same key ships with every Android Studio and is reused
    // by throwaway builds and malware alike. A release build must be signed with a stable, private
    // key that is ours alone. The key is never committed: it is read from keystore.properties at
    // the repo root (see RELEASE_SIGNING.md) or from environment variables for CI. When neither is
    // configured, the build falls back to the debug key so a fresh checkout still assembles, and
    // prints a warning so a release is never shipped debug-signed by accident.
    // Stable debug signing key, committed so every machine and every CI run signs debug/test builds
    // with the SAME key. Without this each build gets a fresh per-machine debug key, and a new test
    // build cannot install over the previous one ("conflicting package"). A debug keystore is not a
    // secret (password "android"); it is only for throwaway test builds, never for release.
    signingConfigs.getByName("debug") {
        storeFile = file("caller-debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val releaseSigningConfig = when {
        keystorePropsFile.exists() -> {
            val props = Properties().apply { FileInputStream(keystorePropsFile).use { load(it) } }
            signingConfigs.create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
        System.getenv("CALLER_KEYSTORE") != null -> {
            signingConfigs.create("release") {
                storeFile = rootProject.file(System.getenv("CALLER_KEYSTORE"))
                storePassword = System.getenv("CALLER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CALLER_KEY_ALIAS")
                keyPassword = System.getenv("CALLER_KEY_PASSWORD")
            }
        }
        else -> null
    }

    buildTypes {
        debug {
            // Install test builds as a SEPARATE app (com.caller.test) next to the released com.caller,
            // so a differently-signed test APK never collides ("conflicting package") and no uninstall
            // is needed. Cross-app identifiers that would clash when both are installed (the backup
            // account type) are made applicationId-specific so the two coexist.
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigningConfig ?: signingConfigs.getByName("debug").also {
                project.logger.warn(
                    "WARNING: no release keystore configured (keystore.properties / CALLER_KEYSTORE) - " +
                    "falling back to the DEBUG key. Do not publish this APK: a debug-signed build is what " +
                    "Play Protect flags as harmful. See RELEASE_SIGNING.md."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
        suppressWarnings = true
    }
    
    // 1. Completely disable Android Studio Linter
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        ignoreWarnings = true
        quiet = true
    }
    
    buildFeatures {
        buildConfig = true
        compose = true
    }
    // Public GitHub repository used as the stable update channel.  Keep this out of
    // source control if the project is forked; configure it for a release build with:
    //   -PgithubReleaseRepository=owner/repository
    val githubReleaseRepository = providers.gradleProperty("githubReleaseRepository")
        .orElse("")
        .get()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    defaultConfig {
        buildConfigField("String", "GITHUB_RELEASE_REPOSITORY", "\"$githubReleaseRepository\"")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Block Store: keeps the profile/P2P-name backup in Play services, outside the app's data.
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")
    // Name server calls: some providers (e.g. Jio) block *.workers.dev in DNS, so look it up over
    // DNS-over-HTTPS (Cloudflare/Google) when the normal lookup fails.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // Where an unsaved number is from ("Bangalore, Karnataka", "India", "United States"), offline, like Google Phone.
    implementation("com.googlecode.libphonenumber:geocoder:3.44")

    // AndroidX SplashScreen library for WhatsApp-style animation
    implementation("androidx.core:core-splashscreen:1.0.1")
    
    // Compose dependencies
    implementation("androidx.compose.ui:ui:1.5.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.4")
    implementation("androidx.compose.material3:material3:1.1.2")
    implementation("androidx.compose.material:material-icons-extended:1.5.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// 2. Completely disable all Kotlin Compiler warnings
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        suppressWarnings = true
    }
}
