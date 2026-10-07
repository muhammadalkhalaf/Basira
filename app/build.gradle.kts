import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Firebase (Analytics + Crashlytics). google-services.json is untracked (see .gitignore) and belongs in
 * app/. Without it the Google Services and Crashlytics plugins are not applied: the app still builds,
 * Firebase stays uninitialized at runtime, and nothing is collected.
 */
val firebaseConfigured = file("google-services.json").exists()
if (firebaseConfigured) {
    pluginManager.apply(libs.plugins.google.services.get().pluginId)
    pluginManager.apply(libs.plugins.firebase.crashlytics.get().pluginId)
}

/**
 * Build-time configuration, read from Gradle properties (-P or gradle.properties) first and from the
 * untracked local.properties second.
 *
 * EXPERIMENTAL PRIVATE BUILD: GEMINI_API_KEY is compiled into the APK as BuildConfig.GEMINI_API_KEY.
 * Anyone with the APK can extract it. This design is only for a few trusted testers; see SECURITY.md.
 */
val localProperties = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun config(name: String, default: String): String =
    (project.findProperty(name) as String?) ?: localProperties.getProperty(name) ?: default

// "fake" = bundled sample images (no glasses, no Meta AI), "mockdevicekit" = DAT MockDeviceKit,
// "real" = physical glasses through DAT. Release builds always use "real".
val debugGlassesMode = config("basira.glasses", "fake")
val geminiApiKey = config("GEMINI_API_KEY", "").trim()
val geminiModel = config("GEMINI_MODEL", "gemini-3.5-flash-lite").trim().ifEmpty { "gemini-3.5-flash-lite" }

// Firebase collection (Analytics + Crashlytics) is always on in release and off in debug unless
// basira.firebase.debugCollection=true, so development sessions do not pollute tester data.
val debugFirebaseCollection = config("basira.firebase.debugCollection", "false").toBoolean()

// "fake" = canned Arabic descriptions (never calls Gemini), "remote" = Gemini API directly.
val debugVisionMode = config("basira.vision", if (geminiApiKey.isBlank()) "fake" else "remote")

/**
 * Encodes [value] as a Java string literal for buildConfigField so that quotes, backslashes, control
 * characters, or non-ASCII characters in local.properties cannot break or inject generated code.
 */
fun javaStringLiteral(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when {
            ch == '\\' -> append("\\\\")
            ch == '"' -> append("\\\"")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            // Octal, not \\uXXXX: javac expands unicode escapes before lexing, so \\u000a would
            // still terminate the literal.
            ch.code < 0x20 || ch.code == 0x7F -> append("\\%03o".format(ch.code))
            ch.code > 0x7F -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
    }
    append('"')
}

android {
    namespace = "com.basira.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.basira.app"
        // DAT 1.0.0 declares minSdk 29. Keeping 29 maximizes support for older phones.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Meta Wearables Device Access Toolkit: "0" is accepted in Developer Mode. Production builds
        // must use the APPLICATION_ID and CLIENT_TOKEN from Wearables Developer Center.
        manifestPlaceholders["mwdat_application_id"] = config("mwdat_application_id", "0")
        manifestPlaceholders["mwdat_client_token"] = config("mwdat_client_token", "0")

        buildConfigField("String", "GEMINI_API_KEY", javaStringLiteral(geminiApiKey))
        buildConfigField("String", "GEMINI_MODEL", javaStringLiteral(geminiModel))
    }

    buildTypes {
        debug {
            buildConfigField("String", "GLASSES_MODE", "\"$debugGlassesMode\"")
            buildConfigField("String", "VISION_MODE", "\"$debugVisionMode\"")
            manifestPlaceholders["firebaseCollectionEnabled"] = debugFirebaseCollection.toString()
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "GLASSES_MODE", "\"real\"")
            buildConfigField("String", "VISION_MODE", "\"remote\"")
            manifestPlaceholders["firebaseCollectionEnabled"] = "true"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    // The language can be switched inside the app (Settings > Language), so Play language splits must
    // not strip values-ar or the English resources from devices set to another language.
    bundle {
        language { enableSplit = false }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// A release without a key still builds; at runtime it reports a typed configuration error instead
// of calling Gemini. The key value itself is never printed.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (geminiApiKey.isBlank()) logger.warn("GEMINI_API_KEY is not set: this release cannot describe images.")
        if (!firebaseConfigured) logger.warn("app/google-services.json is missing: this release has no Analytics or Crashlytics.")
    }
}

dependencies {
    implementation(project(":domain"))
    testImplementation(testFixtures(project(":domain")))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.core)
    implementation(libs.okhttp.logging)

    // Meta Wearables Device Access Toolkit. MockDeviceKit ships only in debug builds.
    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    debugImplementation(libs.mwdat.mockdevice)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mwdat.mockdevice)
}
