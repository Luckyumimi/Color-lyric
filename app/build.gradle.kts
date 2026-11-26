plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
        versionCode = 23
        versionName = "1.9.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.named("main") {
        kotlin.directories.add(rootProject.file("third_party/hle-provider-host/src/main/kotlin").path)
        val imported = rootProject.file("third_party/coloros-lyrics-providers")
        listOf("parser-lrc", "parser-qrc", "parser-yrc", "parser-krc", "parser-ttml", "universal-api")
            .forEach { kotlin.directories.add(imported.resolve("$it/src/main/kotlin").path) }
        listOf("provider-core", "provider-runtime-diagnostics", "universal-runtime").forEach {
            kotlin.directories.add(imported.resolve("$it/main/kotlin").path)
            java.directories.add(imported.resolve("$it/main/java").path)
        }
    }

    androidResources {
        noCompress += "ttf"
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
            proguardFiles("hle-provider-rules.pro")
            isShrinkResources = true
            optimization {
                enable = true
            }
        }
    }
    // The upstream DexKit host loads the module’s extracted native library in player processes.
    packaging.jniLibs.useLegacyPackaging = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dexkit)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.lyricon.model)
    implementation(libs.lyricon.subscriber)
    implementation("io.github.proify.lyricon:provider:0.1.70")
    implementation(libs.superlyric.api)
    implementation(libs.okhttp.brotli)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
