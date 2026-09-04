plugins {
    // AGP 9.x provides built-in Kotlin support. The org.jetbrains.kotlin.android plugin
    // must NOT be applied -- AGP rejects it outright. See docs/development.md.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

        // Instrumented tests are how the Shizuku backend is validated against a real
        // platform. Nothing about the capability architecture can be proven on the JVM
        // alone -- binder lifecycle, SELinux domain and the shell UID are runtime facts.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        compose = true
        // SetupAction fixes its target package at compile time; BuildConfig.APPLICATION_ID
        // is what a test compares it against, so a rename cannot silently repoint it.
        buildConfig = true
        // The Shizuku UserService contract is a typed Binder interface.
        aidl = true
    }

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
        // Real device captures are deliberately not in this repository -- they are ~15 MB
        // of the maintainer's own device state. RealFixtureValidationTest runs against them
        // when an archive is pointed at with -Dbattinsight.fixtures=..., and is skipped
        // otherwise, which is what CI does.
        unitTests.all { test ->
            providers.systemProperty(FIXTURE_ARCHIVE_PROPERTY).orNull?.let { path ->
                test.systemProperty(FIXTURE_ARCHIVE_PROPERTY, path)
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Stores the access-mode preference. Nothing diagnostic is ever persisted.
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime)
    // Collection is I/O-bound and cancellable; established now so contracts can be suspend-shaped.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

/** Points RealFixtureValidationTest at an out-of-tree archive of real device captures. */
val FIXTURE_ARCHIVE_PROPERTY = "battinsight.fixtures"
