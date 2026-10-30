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
        minSdk = 24
        targetSdk = 35
        versionCode = 129
        versionName = "8.9.1"
    }

    buildTypes {
        release {                               
            isMinifyEnabled = false
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

    // Google Play Services for OTP Auto-fill
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    // Block Store: keeps the profile/P2P-name backup in Play services, outside the app's data.
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")
    // Name server calls: some providers (e.g. Jio) block *.workers.dev in DNS, so look it up over
    // DNS-over-HTTPS (Cloudflare/Google) when the normal lookup fails.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

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
