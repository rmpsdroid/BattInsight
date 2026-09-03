plugins {
    // AGP 9.x provides built-in Kotlin support. The org.jetbrains.kotlin.android plugin
    // must NOT be applied -- AGP rejects it outright. See docs/development.md.
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rmpsdroid.battinsight"

    // compileSdk and targetSdk are deliberately different, and the distinction matters.
    //
    // compileSdk 37 = the API surface we compile against. Required by androidx.core 1.19.0,
    // supported by AGP 9.4, and installed as a stable platform (android-37.0, empty CodeName).
    // Raising it does not change runtime behaviour.
    //
    // targetSdk 36 = the runtime behaviour we opt into. Android 16 is the platform every
    // Phase 1A/1B measurement was taken on. We do not opt into Android 17 behaviour changes
    // we have not measured. Raise this only once Android 17 has been measured on real hardware.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rmpsdroid.battinsight"
        minSdk = 33      // Android 13. Phase 0 product floor. Do not raise without evidence.
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-foundation"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // No signing configuration exists. Release signing is Phase 2B+ and will never
            // use an inherited key. See docs/security-privacy.md.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Kotlin jvmTarget is supplied by AGP's built-in Kotlin support and follows
    // compileOptions. AGP 9.x removed the kotlinOptions block.

    lint {
        // Lint is a real gate. Do not set abortOnError = false.
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        checkReleaseBuilds = true
        // Report format flags are deprecated in AGP 9.x -- lint reports are always generated.
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    // Collection is I/O-bound and cancellable; established now so contracts can be suspend-shaped.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
