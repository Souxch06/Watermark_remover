import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. A STABLE key is what lets Android install an update over the previous version
// instead of demanding an uninstall. The key is NEVER in the repository: it is restored by the
// Release workflow from the KEYSTORE_* secrets, or provided by hand for a local build.
//   1. `keystore.properties` at the repo root (git-ignored), written by CI or by the developer.
//   2. `signing/keystore.properties` + the keystore it points at (git-ignored) – for a developer
//      who keeps the key outside the repository and only the properties file here.
//   3. Debug key: `assembleRelease` still builds (CI needs it) but a *publish* is refused: run
//      `./gradlew publishReleaseApk` or pass -PrequireReleaseSigning=true (the release workflow
//      does). The workflow verifies the certificate too.
val keystorePropsFile: File? = listOf(
    rootProject.file("keystore.properties"),
    rootProject.file("signing/keystore.properties"),
).firstOrNull { it.exists() }
val keystoreProps = Properties().apply {
    keystorePropsFile?.inputStream()?.use { load(it) }
}
val keystoreFile: File? = keystoreProps.getProperty("storeFile")?.let { path ->
    val direct = File(path)
    when {
        direct.isAbsolute -> direct
        File(keystorePropsFile!!.parentFile, path).exists() -> File(keystorePropsFile.parentFile, path)
        else -> rootProject.file(path)
    }
}
val hasReleaseKeystore = keystoreFile?.exists() == true

android {
    namespace = "com.souxch.watermarkremover"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.souxch.watermarkremover"
        // Media3 Transformer needs API 21+; MediaStore "IS_PENDING" export flow used here needs 29+
        // but we fall back gracefully below that (see VideoSaver).
        minSdk = 24
        targetSdk = 35
        // Overridable from CI: -PversionCode=42 -PversionName=1.3.0
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 3
        versionName = (project.findProperty("versionName") as String?) ?: "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // No keystore configured: still build (so the code compiles), but with the debug key,
            // which must never be published. A local `assembleRelease` therefore needs the filter
            // below to be bypassed explicitly (-PallowDebugSigning=true).
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME is used by the in-app update check
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // fr (default) and en are kept in sync manually.
        disable += "MissingTranslation"
        // Media3 Transformer/Effect are @UnstableApi; we accept that (pinned version in the catalog).
        disable += "UnsafeOptInUsageError"
        textReport = true
        textOutput = file("build/reports/lint-results.txt")
        htmlReport = false
        xmlReport = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Human-friendly artifact names: WatermarkRemover-1.2.0-release.apk
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "WatermarkRemover-${variant.versionName}-${variant.buildType.name}.apk"
            }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Video processing (hardware accelerated decode -> GL effect -> encode)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.transformer)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json) // real org.json for JVM tests (android.jar only has stubs)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
// Guard rail: an APK signed with the debug key cannot update an installed release, so it must never
// be *published*. The check therefore only arms itself when a publish is actually requested — the
// plain CI job (assembleDebug assembleRelease) must keep working without any keystore, otherwise
// every build without secrets would turn red.
//   * `publishReleaseApk`   : opt-in marker task, used by whoever wants the guard locally.
//   * `-PrequireReleaseSigning=true` : arms it for one invocation (the release workflow does this).
val requireReleaseSigning = project.hasProperty("requireReleaseSigning")

/** Marker task: running `./gradlew publishReleaseApk` refuses to continue without a real keystore. */
tasks.register("publishReleaseApk") {
    group = "publishing"
    description = "Checks that a release keystore is available before a release APK is published."
    doFirst {
        if (!hasReleaseKeystore) {
            throw GradleException(
                "No release keystore found: refusing to publish an APK that would be signed with the " +
                    "debug key.\nProvide keystore.properties + the keystore file (see README).",
            )
        }
    }
}

gradle.taskGraph.whenReady {
    val publishingRelease = requireReleaseSigning || allTasks.any { it.name == "publishReleaseApk" }
    if (publishingRelease && !hasReleaseKeystore) {
        throw GradleException(
            "No release keystore found: refusing to publish an APK signed with the debug key.\n" +
                "Provide keystore.properties + the keystore file, or drop -PrequireReleaseSigning.",
        )
    }
}
