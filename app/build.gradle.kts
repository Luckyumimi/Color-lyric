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
        minSdk = 34
        targetSdk = 37
        versionCode = 10
        versionName = "1.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    androidResources {
        // Keep in sync with res/xml/locales_config.xml, including script qualifiers.
        localeFilters += listOf(
            "en", "b+zh+Hans", "b+zh+Hant+HK", "b+zh+Hant+TW",
            "es", "hi", "fr", "pt", "de", "ja", "ru", "ko",
            "it", "nl", "tr", "vi", "th", "in", "ms", "pl",
        )
    }

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
            isShrinkResources = true
            optimization {
                enable = true
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
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
