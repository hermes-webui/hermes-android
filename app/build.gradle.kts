import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

val appVersionName = "1.2.2"
val appVersionCode = run {
    val semver = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")
        .matchEntire(appVersionName)
        ?: error("appVersionName must use semantic version format x.y.z")
    val (major, minor, patch) = semver.destructured
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}
val distributionArtifactName = "hermes-webui-v$appVersionName"
val githubReleaseArtifactName = "$distributionArtifactName-github"

// Repository (owner/name) whose GitHub Releases the "github" build type checks.
// Defaults to upstream so local/upstream builds are unchanged; release workflows
// override it with the repository running the workflow so fork builds check fork releases.
val defaultGithubReleaseRepo = "hermes-webui/hermes-android"
val githubReleaseRepo: String = run {
    val candidate = providers.gradleProperty("githubReleaseRepo").orNull
        ?: providers.environmentVariable("GITHUB_RELEASES_REPO").orNull
    candidate?.trim()?.takeIf { it.isNotEmpty() && it.contains('/') }
        ?: defaultGithubReleaseRepo
}

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

// Use the product name and version in generated release artifacts.
base {
    archivesName.set(distributionArtifactName)
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
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // F-Droid builds `assembleRelease` from source and signs it with its own key, so
        // `release` is the FOSS store build: no Google Play libraries (see `src/foss`) and
        // no in-app updater because F-Droid delivers updates itself.
        release {
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "UPDATE_CHANNEL", "\"none\"")
            buildConfigField("String", "GITHUB_RELEASES_API_URL", "\"\"")
            buildConfigField("String", "GITHUB_RELEASES_PAGE_URL", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = null
            buildConfigField("String", "UPDATE_CHANNEL", "\"play\"")
        }
        create("github") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".github"
            versionNameSuffix = "-github"
            buildConfigField("String", "UPDATE_CHANNEL", "\"github\"")
            buildConfigField(
                "String",
                "GITHUB_RELEASES_API_URL",
                "\"https://api.github.com/repos/$githubReleaseRepo/releases/latest\""
            )
            buildConfigField(
                "String",
                "GITHUB_RELEASES_PAGE_URL",
                "\"https://github.com/$githubReleaseRepo/releases/latest\""
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "UPDATE_CHANNEL", "\"none\"")
            buildConfigField("String", "GITHUB_RELEASES_API_URL", "\"\"")
            buildConfigField("String", "GITHUB_RELEASES_PAGE_URL", "\"\"")
        }
    }

    sourceSets {
        // Every build type except `play` gets the Play-free update client.
        listOf("release", "github", "debug").forEach { buildType ->
            getByName(buildType).kotlin.directories.add("src/foss/java")
        }
    }

    // F-Droid asks for no dependency-metadata block in APKs; Play still receives it in the AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
}

tasks.matching {
    it.name == "assemblePlay" ||
        it.name == "bundlePlay" ||
        it.name == "assembleGithub"
}.configureEach {
    dependsOn(verifyReleaseSigning)
}

// Proprietary dependency groups that must never reach the F-Droid (`release`) or GitHub APKs.
val nonFreeDependencyGroups = listOf("com.google.android.play", "com.google.android.gms", "com.google.firebase")
val fossRuntimeClasspaths = listOf("releaseRuntimeClasspath", "githubRuntimeClasspath")

tasks.register("verifyFossDependencies") {
    group = "verification"
    description = "Fails if variants resolve forbidden Play dependencies or omit shared WebView Credential Manager support."
    val resolvedRoots = fossRuntimeClasspaths.map { name ->
        name to configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
    }
    val blockedGroups = nonFreeDependencyGroups

    doLast {
        val runtimeClasspaths = fossRuntimeClasspaths + "playRuntimeClasspath"
        val credentialArtifactsByClasspath = runtimeClasspaths
            .associateWith { classpath ->
                configurations.getByName(classpath).incoming.resolutionResult.allComponents
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .map { "${it.group}:${it.module}" }
                    .toSet()
            }
        val credentialsArtifact = "androidx.credentials:credentials"
        val playCredentialsAdapter = "androidx.credentials:credentials-play-services-auth"
        check(runtimeClasspaths.all {
            credentialsArtifact in credentialArtifactsByClasspath.getValue(it)
        }) {
            "$credentialsArtifact must be present in every runtime classpath."
        }
        val includedAdapters = runtimeClasspaths.filter {
            playCredentialsAdapter in credentialArtifactsByClasspath.getValue(it)
        }
        check(includedAdapters.isEmpty()) {
            "$playCredentialsAdapter must not be present in any runtime classpath: ${includedAdapters.joinToString()}"
        }

        val violations = resolvedRoots.flatMap { (classpath, root) ->
            val seen = mutableSetOf<ResolvedComponentResult>()
            val pending = ArrayDeque(listOf(root.get()))
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (!seen.add(component)) continue
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { pending.add(it.selected) }
            }
            seen.mapNotNull { it.id as? ModuleComponentIdentifier }
                .filter { id -> blockedGroups.any { id.group == it || id.group.startsWith("$it.") } }
                .map { "$classpath: ${it.displayName}" }
        }
        check(violations.isEmpty()) {
            "Proprietary dependencies found in FOSS builds:\n" + violations.joinToString("\n")
        }
        logger.lifecycle("FOSS builds are free of ${blockedGroups.joinToString()}: ${fossRuntimeClasspaths.joinToString()}")
    }
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
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.google.material)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.security.crypto)
    "playImplementation"(libs.play.app.update)
    "playImplementation"(libs.play.app.update.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
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
    dependsOn("assembleGithub")

    doLast {
        copyFirstExistingArtifact(
            candidates = listOf(
                layout.buildDirectory.file("outputs/apk/github/$githubReleaseArtifactName.apk").get().asFile,
                layout.buildDirectory.file("outputs/apk/github/$distributionArtifactName-github.apk").get().asFile,
                layout.buildDirectory.file("outputs/apk/github/app-github.apk").get().asFile
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

// The contract tests (VoiceCaptureOwnershipContractTest, WebViewSecurityContractTest) read
// AndroidManifest.xml and the Kotlin sources straight from disk. Declare them as inputs so Gradle's
// up-to-date check and the build cache never reuse a passing result after those files change.
tasks.withType<Test>().configureEach {
    inputs.dir("src/main")
        .withPropertyName("mainSourceSetForContractTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
