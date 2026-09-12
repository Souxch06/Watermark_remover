import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. A STABLE key is what lets Android install an update over the previous
// version instead of demanding an uninstall. Lookup order:
//   1. `keystore.properties` at the repo root (git-ignored) – private key, written by the
//      Release workflow when the KEYSTORE_* secrets are configured, or by hand locally.
//   2. `signing/keystore.properties` + `signing/release.jks` – public key committed in the repo,
//      so builds from GitHub Actions are always consistently signed with zero configuration.
//   3. Debug key (local `assembleRelease` only, never on CI).
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
        // Overridable from CI: -PversionCode=42 -PversionName=1.2.0
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"

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

    // Human-friendly artifact names: WatermarkRemover-1.0.0-release.apk
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
