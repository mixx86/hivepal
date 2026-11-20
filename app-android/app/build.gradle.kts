plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing: a Gradle property (-PHIVEPAL_KEYSTORE_FILE=…) or an environment
// variable of the same name. Without a keystore, release builds fall back to the debug
// key (local builds keep working); HIVEPAL_REQUIRE_RELEASE_KEY=1 turns that into an error.
fun signingValue(name: String): String? =
    (providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull)?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("HIVEPAL_KEYSTORE_FILE")
val requireReleaseKey = signingValue("HIVEPAL_REQUIRE_RELEASE_KEY") == "1"
val buildsRelease = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseKeystore == null && buildsRelease) {
    if (requireReleaseKey) {
        throw GradleException("HIVEPAL_KEYSTORE_FILE is not set and HIVEPAL_REQUIRE_RELEASE_KEY=1")
    }
    logger.warn(
        "WARNING: HIVEPAL_KEYSTORE_FILE is not set: the release APK is signed with the public " +
            "DEBUG key. Fine for local testing, never publish it.",
    )
}

// Android Auto (docs/android-auto.md). Google Play takes calling apps for cars only on its
// internal and closed testing tracks and rejects them on open testing and production, so
// release builds leave the car entry point out unless -PhivepalAndroidAuto=true (the
// GitHub APK, a closed-testing bundle). Debug builds always include it.
val androidAuto = providers.gradleProperty("hivepalAndroidAuto").orNull == "true"

android {
    namespace = "dev.agentring.app"
    compileSdk = 36

    defaultConfig {
        // Upstream's package name: existing installs keep their Firebase registration, widget and shortcuts.
        // -PhivepalApplicationId=your.own.id builds an app that can sit next to agentring from Google Play.
        applicationId = providers.gradleProperty("hivepalApplicationId").orNull ?: "dev.agentring.app"
        minSdk = 26
        targetSdk = 36
        // Release builds pass both: -PversionName=0.2.0 -PversionCode=20099 (see docs/release.md).
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("versionName").orNull ?: "0.2.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Hosted hivepal (docs/cloud.md), offered in the setup tutorial; -PhivepalCloudUrl= (empty) hides it.
        val cloudUrl = providers.gradleProperty("hivepalCloudUrl").orNull ?: ""  // Hivepal: no hosted service
        require(cloudUrl.isEmpty() || cloudUrl.startsWith("https://")) { "hivepalCloudUrl must be https://" }
        buildConfigField("String", "CLOUD_URL", "\"$cloudUrl\"")
    }

    signingConfigs {
        // Committed, well-known debug key so every CI build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("HIVEPAL_KEYSTORE_PASSWORD")
                    ?: throw GradleException("HIVEPAL_KEYSTORE_PASSWORD is not set")
                keyAlias = signingValue("HIVEPAL_KEY_ALIAS") ?: throw GradleException("HIVEPAL_KEY_ALIAS is not set")
                // PKCS12 keystores (keytool's default) use the store password for the key too.
                keyPassword = signingValue("HIVEPAL_KEY_PASSWORD") ?: storePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        animationsDisabled = true
    }

    lint {
        // Fatal in release builds, but a false positive here: registerForActivityResult is
        // only called on ComponentActivity (no Fragment), and androidx.fragment 1.1.0 is
        // only on the classpath through play-services-basement.
        disable += "InvalidFragmentVersionForActivityResult"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    // Espresso 3.7 needs 1.2.0; the app's own graph resolves 1.1.0 and Kotlin 2.4 makes the test classpath match it.
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.unifiedpush.connector) { exclude(group = "com.google.crypto.tink", module = "tink") }
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.services.code.scanner)
    implementation(libs.livekit.android)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.car.app)
    constraints {
        implementation(libs.guava) { because("car-app's Guava 31.1 has OSV advisories") }
    }
    debugImplementation(project(":auto"))
    if (androidAuto) releaseImplementation(project(":auto"))

    androidTestImplementation(platform(libs.compose.bom))
    testImplementation(libs.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
