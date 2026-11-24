plugins {
    alias(libs.plugins.android.application)
}

val androidEnvKeys = setOf("APP_MODE", "ANDROID_BACKEND_URL", "STRIPE_PUBLISHABLE_KEY", "MAPS_API_KEY")
val androidEnv = rootProject.file(".env").let { envFile ->
    if (!envFile.isFile) emptyMap() else envFile.useLines { lines ->
        lines.mapNotNull { line ->
            val key = line.substringBefore('=').trim()
            if (key !in androidEnvKeys || '=' !in line) null else {
                val raw = line.substringAfter('=').trim()
                val value = when {
                    raw.startsWith('"') -> raw.drop(1).substringBefore('"')
                    raw.startsWith('\'') -> raw.drop(1).substringBefore('\'')
                    else -> raw.substringBefore('#').trim()
                }
                key to value
            }
        }.toMap()
    }
}
val firebaseDemo = providers.gradleProperty("firebaseDemo").orNull?.let { it == "true" }
    ?: (androidEnv["APP_MODE"] == "emulator")
if (!firebaseDemo) {
    check(file("google-services.json").exists()) {
        "Connected Android builds require app/google-services.json"
    }
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.example.final_term"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.final_term"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        val configuredBackendUrl = providers.gradleProperty("backendBaseUrl").orNull
            ?: androidEnv["ANDROID_BACKEND_URL"]?.takeIf {
                it.isNotEmpty() && (firebaseDemo == (androidEnv["APP_MODE"] == "emulator"))
            }
        val stripePublishableKey = providers.gradleProperty("stripePublishableKey").orNull
            ?: androidEnv["STRIPE_PUBLISHABLE_KEY"].orEmpty()
        check(stripePublishableKey.isEmpty() || stripePublishableKey.startsWith("pk_test_")) {
            "Stripe checkout requires a test publishable key (pk_test_...)"
        }
        val simulatorBackendUrl = configuredBackendUrl
            ?: if (firebaseDemo) "http://10.0.2.2:3198" else "http://10.0.2.2:3001"
        val releaseRequested = gradle.startParameter.taskNames.any {
            it.contains("release", ignoreCase = true)
        }
        check(!firebaseDemo || !releaseRequested) {
            "Firebase Emulator is available for debug builds only"
        }
        if (releaseRequested && configuredBackendUrl == null) {
            error(
                "Release Android build requires ANDROID_BACKEND_URL=https://... "
                    + "in .env or -PbackendBaseUrl=https://..."
            )
        }
        check(simulatorBackendUrl.startsWith("https://")
                || (!releaseRequested && (simulatorBackendUrl.startsWith("http://10.0.2.2:")
                    || simulatorBackendUrl.startsWith("http://127.0.0.1:")))) {
            "Backend URL must use HTTPS; debug builds also support emulator or ADB reverse loopback"
        }
        buildConfigField("boolean", "FIREBASE_DEMO", firebaseDemo.toString())
        buildConfigField("String", "BACKEND_BASE_URL", "\"$simulatorBackendUrl\"")
        buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"$stripePublishableKey\"")

        manifestPlaceholders["bankAppLabel"] = if (firebaseDemo) "Nimbus Bank (Local)" else "@string/app_name"
        manifestPlaceholders["bankAllowBackup"] = (!firebaseDemo).toString()
        manifestPlaceholders["MAPS_API_KEY"] = if (firebaseDemo) "" else androidEnv["MAPS_API_KEY"].orEmpty()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            if (firebaseDemo) applicationIdSuffix = ".localreview"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.okhttp)
    implementation(libs.stripe.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
