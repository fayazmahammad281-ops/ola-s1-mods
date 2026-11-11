plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.asjohn720.s1mods"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

        defaultConfig {
        applicationId = "com.fayazmahammad281.s1mods.moveos403"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.2-moveos403"

        vectorDrawables.useSupportLibrary = true

        // GitHub repo that holds the public Releases used for update checks.
        buildConfigField("String", "GIT_OWNER", "\"fayazmahammad281-ops\"")
        buildConfigField("String", "GIT_REPO", "\"ola-s1-mods\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Embedded Pure Kotlin ADB Client
    implementation(libs.dadb)

    // Root gRPC Client for Scooter Port 50005
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.stub)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
