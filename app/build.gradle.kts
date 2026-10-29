plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.casadei.nirowatchdog"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.casadei.nirowatchdog"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0" // x-release-please-version
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Android Auto (Phone-Projection) Car App Library
    implementation(libs.car.app.projected)
    testImplementation(libs.car.app.testing)
    testImplementation(libs.junit)
}
