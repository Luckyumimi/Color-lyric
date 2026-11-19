plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "shop.terramoor.github.io"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.luckyumimi.terramoor"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    flavorDimensions += "distribution"
    productFlavors {
        create("local") {
            dimension = "distribution"
            applicationId = "shop.terramoor.github.io"
        }
        create("public") {
            dimension = "distribution"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Keep one development build and one release build, with different package identities.
androidComponents.beforeVariants { variant ->
    val distribution = variant.productFlavors.single().second
    variant.enable = (distribution == "local" && variant.buildType == "debug") ||
        (distribution == "public" && variant.buildType == "release")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.coui.ui)
    implementation(libs.coui.preference)
    implementation(libs.coui.kit)
    implementation(libs.coui.kit.icons)
    compileOnly(libs.xposed.api)
    implementation(libs.xposed.service)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
