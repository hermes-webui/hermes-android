import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

val appVersionName = "0.1.8"
val githubReleaseArtifactName = "hermes-webui-v$appVersionName-github"

val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

fun signingValue(environmentVariable: String, propertyName: String): String? {
    return providers.environmentVariable(environmentVariable).orNull
        ?.takeIf { it.isMeaningfulSigningValue() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isMeaningfulSigningValue() }
}

fun String.isMeaningfulSigningValue(): Boolean {
    val normalized = trim()
    return normalized.isNotEmpty() && normalized.lowercase() !in setOf("change-me", "replace-me")
}

fun resolveSigningFile(path: String): File {
    val candidate = File(path)
    return if (candidate.isAbsolute) candidate else rootProject.file(path)
}

val releaseStoreFilePath = signingValue("ANDROID_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val releaseStoreFile = releaseStoreFilePath?.let(::resolveSigningFile)
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails fast when release signing credentials are missing or invalid."

    doLast {
        if (!releaseSigningConfigured) {
            error(
                """
                Release signing is not configured.
                Provide either:
                - ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
                - or a root keystore.properties file with storeFile, storePassword, keyAlias, keyPassword
                """.trimIndent()
            )
        }

        val signingFile = requireNotNull(releaseStoreFile)
        check(signingFile.exists()) {
            "Release keystore file does not exist: ${signingFile.path}"
        }
        check(signingFile.isFile) {
            "Release keystore path is not a file: ${signingFile.path}"
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Use the product name, version, and GitHub channel in generated APK artifacts.
base {
    archivesName.set(githubReleaseArtifactName)
}

extensions.configure<ApplicationExtension>("android") {
    namespace = "com.hermeswebui.android"
    compileSdk = 37

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.hermeswebui.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 9
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material)
    implementation(libs.google.material)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

fun copyFirstExistingArtifact(candidates: List<File>, target: File) {
    val source = candidates.firstOrNull { it.exists() }
        ?: error("No artifact found. Checked: ${candidates.joinToString { it.path }}")
    target.parentFile.mkdirs()
    source.copyTo(target, overwrite = true)
    logger.lifecycle("Wrote ${target.path}")
}

tasks.register("stageGithubReleaseApk") {
    group = "distribution"
    description = "Builds the release APK and stages it as build/release/$githubReleaseArtifactName.apk."
    dependsOn(verifyReleaseSigning)
    dependsOn("assembleRelease")

    doLast {
        copyFirstExistingArtifact(
            candidates = listOf(
                layout.buildDirectory.file("outputs/apk/release/$githubReleaseArtifactName-release.apk").get().asFile,
                layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
            ),
            target = rootProject.layout.buildDirectory.file("release/$githubReleaseArtifactName.apk").get().asFile
        )
    }
}

tasks.register("stageReleaseArtifacts") {
    group = "distribution"
    description = "Builds and stages the GitHub release APK with a product/version filename."
    dependsOn("stageGithubReleaseApk")
}

tasks.register("printReleaseVersionName") {
    group = "help"
    description = "Prints the Android release versionName used by GitHub release automation."

    doLast {
        println(appVersionName)
    }
}
