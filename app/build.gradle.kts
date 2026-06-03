plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun quoteForBuildConfig(value: String): String {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.findmycar.app"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.findmycar.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val azureStorageAccount = (project.findProperty("AZURE_STORAGE_ACCOUNT") as String?) ?: ""
        val azureModelsContainer = (project.findProperty("AZURE_MODELS_CONTAINER") as String?) ?: ""
        val tokenBrokerBaseUrl = (project.findProperty("TOKEN_BROKER_BASE_URL") as String?) ?: ""
        val tokenBrokerApiKey = (project.findProperty("TOKEN_BROKER_API_KEY") as String?) ?: ""

        buildConfigField("String", "AZURE_STORAGE_ACCOUNT", "\"${quoteForBuildConfig(azureStorageAccount)}\"")
        buildConfigField("String", "AZURE_MODELS_CONTAINER", "\"${quoteForBuildConfig(azureModelsContainer)}\"")
        buildConfigField("String", "TOKEN_BROKER_BASE_URL", "\"${quoteForBuildConfig(tokenBrokerBaseUrl)}\"")
        buildConfigField("String", "TOKEN_BROKER_API_KEY", "\"${quoteForBuildConfig(tokenBrokerApiKey)}\"")
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
