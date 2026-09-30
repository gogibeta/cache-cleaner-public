plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cachecleaner.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.cachecleaner.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-diag"

        // Keep only English strings: drops all other locales' resources.
        resourceConfigurations += "en"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    lint {
        // Offline builds can't fetch lint-gradle; skip the vital check for
        // the diag build type (debug doesn't run it either).
        checkReleaseBuilds = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("diag") {
            // Diagnostic build: not debuggable (so it can be signed with the
            // release key), but not minified/obfuscated for troubleshooting.
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            // Keep debuggable builds unobfuscated for easier troubleshooting.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configurations.all {
    // androidx.collection:collection-ktx:1.2.0 duplicates classes in the
    // KMP androidx.collection:collection artifacts; the KTX artifact is legacy.
    exclude(group = "androidx.collection", module = "collection-ktx")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // @Preview only — no usages in main source, so debug-only.
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // NB: material-icons-extended is intentionally NOT used: it adds ~1 MB of
    // icon classes. Only material-icons-core icons + two tiny local vector
    // drawables (ic_memory, ic_stop) are needed.

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
