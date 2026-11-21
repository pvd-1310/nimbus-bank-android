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
        minSdk = 37
        targetSdk = 37
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
                || simulatorBackendUrl.startsWith("http://10.0.2.2:")) {
            "Backend URL must use HTTPS, or http://10.0.2.2:<port> for the Android Simulator"
        }
        buildConfigField("String", "BACKEND_BASE_URL", "\"$simulatorBackendUrl\"")

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
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
