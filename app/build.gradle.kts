plugins {
    // AGP 9.x provides built-in Kotlin support. The org.jetbrains.kotlin.android plugin
    // must NOT be applied -- AGP rejects it outright. See docs/development.md.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Room 3 generates Kotlin and supports KSP only -- KAPT is not an option.
    alias(libs.plugins.ksp)
    // The androidx.room3 Gradle plugin owns the schema directory, so exported schemas land
    // in a source-controlled location rather than a build output.
    alias(libs.plugins.room)
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
        // Robolectric needs the Android resources and manifest of the app under test.
        unitTests.isIncludeAndroidResources = true
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
    /**
     * Raises kotlinx-serialization to a version Room can actually run against.
     *
     * Constraints, not dependencies: nothing here asks for kotlinx-serialization, and this
     * adds nothing to the APK that was not already in it. It only sets the version when
     * something else drags it in, which DataStore 1.2.1 does at 1.7.3.
     *
     * Re-measured for Room 3.0.2 rather than carried over from Room 2. Still required:
     * room3-migration-jvm:3.0.2 declares kotlinx-serialization-json-jvm 1.8.1, and with the
     * constraint removed the androidTest classpath resolves 1.7.3 instead, because AGP's
     * consistent resolution pins it to whatever the application runtime resolved. Room reads
     * its exported schemas through serializers generated against 1.8.x, which fail on 1.7.3
     * with `AbstractMethodError: typeParametersSerializers()`.
     *
     * That is also why the fix belongs on the *application* classpath when only the migration
     * tests need it. Remove once DataStore ships 1.8.x and the versions agree on their own.
     */
    constraints {
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.serialization.core)
    }

    implementation(libs.androidx.core.ktx)
    // Stores the access-mode preference. Nothing diagnostic is ever persisted.
    implementation(libs.androidx.datastore.preferences)
    // Room: the battery session domain, stored as explicit typed columns.
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.framework)
    ksp(libs.androidx.room3.compiler)
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
    // Robolectric lets the Room tests run on the JVM, so CI -- which has no emulator --
    // exercises the real database, real SQLite and the real schema rather than a fake.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room3.testing)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room3.testing)
    // Compose UI tests run on a device: they need a real composition, a real layout pass and
    // real semantics, none of which Robolectric's shadows reproduce faithfully enough to
    // trust for accessibility assertions.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * Exported Room schemas are migration evidence and belong in source control.
 *
 * Committed rather than generated into a build directory: a schema that only exists as build
 * output cannot be diffed in review, and a migration written against a schema nobody can see
 * is a migration nobody can check.
 */
room3 {
    schemaDirectory("$projectDir/schemas")
}

/** Points RealFixtureValidationTest at an out-of-tree archive of real device captures. */
val FIXTURE_ARCHIVE_PROPERTY = "battinsight.fixtures"
