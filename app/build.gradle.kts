import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

check(file("google-services.json").exists()) {
    "Production Android build requires app/google-services.json"
}
apply(plugin = "com.google.gms.google-services")

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
        val localProperties = Properties().apply {
            val localPropertiesFile = rootProject.file("local.properties")
            if (localPropertiesFile.isFile) {
                localPropertiesFile.inputStream().use { load(it) }
            }
        }
        val configuredBackendUrl = providers.gradleProperty("backendBaseUrl").orNull
            ?: localProperties.getProperty("backendBaseUrl")
        val stripePublishableKey = providers.gradleProperty("stripePublishableKey").orNull
            ?: localProperties.getProperty("STRIPE_PUBLISHABLE_KEY", "")
        check(stripePublishableKey.isEmpty() || stripePublishableKey.startsWith("pk_test_")) {
            "Debug Stripe checkout requires a test publishable key (pk_test_...)"
        }
        val simulatorBackendUrl = configuredBackendUrl ?: "http://10.0.2.2:3001"
        val releaseRequested = gradle.startParameter.taskNames.any {
            it.contains("release", ignoreCase = true)
        }
        if (releaseRequested && configuredBackendUrl == null) {
            error(
                "Release Android build requires backendBaseUrl=https://... "
                    + "in local.properties or -PbackendBaseUrl=https://..."
            )
        }
        check(simulatorBackendUrl.startsWith("https://")
                || (!releaseRequested && (simulatorBackendUrl.startsWith("http://10.0.2.2:")
                    || simulatorBackendUrl.startsWith("http://127.0.0.1:")))) {
            "Backend URL must use HTTPS; debug builds also support emulator or ADB reverse loopback"
        }
        buildConfigField("String", "BACKEND_BASE_URL", "\"$simulatorBackendUrl\"")
        buildConfigField("String", "STRIPE_PUBLISHABLE_KEY", "\"$stripePublishableKey\"")

        manifestPlaceholders["MAPS_API_KEY"] = localProperties.getProperty("MAPS_API_KEY", "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
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
