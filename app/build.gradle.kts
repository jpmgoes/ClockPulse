plugins {
    id("com.android.application")

    id("kotlin-parcelize")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    alias(libs.plugins.kotlinCompose)
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.bnyro.clock"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.clock.pulse"
        minSdk = 24
        targetSdk = 37
        versionCode = 24
        versionName = "12.0"
        // Public OAuth audience used by Credential Manager. The Web client secret is never in Android.
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"751330270743-p1bs5efjhqtjp03mh555k96g0p9k3v32.apps.googleusercontent.com\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }


    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["msalSignatureHash"] = "XF5RjQkcsZgOEp6JdVzeGso4vDQ%3D"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            manifestPlaceholders["msalSignatureHash"] = "IoXFO8wW%2BJ92lmpdd42uQgL7JzE%3D"
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        disable += setOf("ExtraTranslation", "MissingDefaultResource")
    }
}

dependencies {

    // Core And UI
    implementation(libs.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.navigation.compose)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)

    // Material Theme
    implementation(libs.material3)
    implementation(libs.material)
    implementation(libs.material.icons.extended)

    implementation(libs.sdp.android)
    implementation(libs.ui.viewbinding)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Room DB
    ksp(libs.room.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.work.runtime.ktx)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.google.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.msal)
}
