import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.notifyring.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.notifyring.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.telecom)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val verifyMergedManifestPrivacy = tasks.register("verifyMergedManifestPrivacy") {
    group = "verification"
    description = "Audits merged AndroidManifest.xml files to enforce zero forbidden permissions."
    dependsOn("processDebugManifest")

    val debugManifestsDir = layout.buildDirectory.dir("intermediates/merged_manifests/debug")
    val releaseManifestsDir = layout.buildDirectory.dir("intermediates/merged_manifests/release")

    doLast {
        val forbiddenPermissions = setOf(
            "android.permission.INTERNET",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        )

        fun collectManifests(rootDir: java.io.File): List<java.io.File> {
            if (!rootDir.exists()) return emptyList()
            return rootDir.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .sortedBy { it.path }
                .toList()
        }

        val debugManifests = collectManifests(debugManifestsDir.get().asFile)
        if (debugManifests.isEmpty()) {
            throw GradleException(
                "verifyMergedManifestPrivacy: No debug merged AndroidManifest.xml found under ${debugManifestsDir.get().asFile}"
            )
        }
        val releaseManifests = collectManifests(releaseManifestsDir.get().asFile)
        val manifestsToAudit = debugManifests + releaseManifests

        val androidNs = "http://schemas.android.com/apk/res/android"
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val builder = factory.newDocumentBuilder()
        val violations = mutableListOf<String>()

        manifestsToAudit.forEach { manifestFile ->
            val doc = builder.parse(manifestFile)
            listOf("uses-permission", "uses-permission-sdk-23").forEach { tagName ->
                val nodes = doc.getElementsByTagName(tagName)
                for (index in 0 until nodes.length) {
                    val element = nodes.item(index) as? Element ?: continue
                    val permissionName = element.getAttributeNS(androidNs, "name")
                        .ifEmpty { element.getAttribute("android:name") }
                        .trim()
                    if (permissionName in forbiddenPermissions) {
                        violations += "${manifestFile.path}: forbidden <$tagName android:name=\"$permissionName\"/>"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Merged manifest privacy audit FAILED (${violations.size} violation(s)):\n" +
                    violations.joinToString(separator = "\n") { "  - $it" }
            )
        }

        logger.lifecycle(
            "verifyMergedManifestPrivacy PASSED: verified ${manifestsToAudit.size} merged manifest(s) " +
                "with 0 forbidden permissions:"
        )
        manifestsToAudit.forEach { manifestFile ->
            logger.lifecycle("  - ${manifestFile.path}")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyMergedManifestPrivacy)
}
