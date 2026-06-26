plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Auto-version based on build timestamp: "yy.MMdd.HHmm" (e.g. "26.0626.1415")
val autoVersionName: String = providers.exec {
    commandLine("date", "+%y.%m%d.%H%M")
}.standardOutput.asText.get().trim()

// Seconds since epoch / 10 — fits in Int, always increasing
val autoVersionCode: Int = (System.currentTimeMillis() / 10000).toInt()

android {
    namespace = "com.findmycar.app"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.findmycar.app"
        minSdk = 26
        targetSdk = 34
        versionCode = autoVersionCode
        versionName = autoVersionName

        // APP_ENV: "DEV" shows debug tab + bottom nav, "PROD" shows single screen
        val appEnv = (project.findProperty("APP_ENV") as String?) ?: "DEV"
        buildConfigField("String", "APP_ENV", "\"$appEnv\"")
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
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.play.services.location)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.select.tf.ops)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
