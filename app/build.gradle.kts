@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.FilterConfiguration
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    kotlin("kapt")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization")
}
apply(plugin = "dagger.hilt.android.plugin")

sealed class Version(
    open val versionMajor: Int,
    val versionMinor: Int,
    val versionPatch: Int,
    val versionBuild: Int = 0
) {
    abstract fun toVersionName(): String
    class Alpha(versionMajor: Int, versionMinor: Int, versionPatch: Int, versionBuild: Int) :
        Version(versionMajor, versionMinor, versionPatch, versionBuild) {
        override fun toVersionName(): String =
            "${versionMajor}.${versionMinor}.${versionPatch}-alpha.$versionBuild"
    }

    class Beta(versionMajor: Int, versionMinor: Int, versionPatch: Int, versionBuild: Int) :
        Version(versionMajor, versionMinor, versionPatch, versionBuild) {
        override fun toVersionName(): String =
            "${versionMajor}.${versionMinor}.${versionPatch}-beta.$versionBuild"
    }

    class Stable(versionMajor: Int, versionMinor: Int, versionPatch: Int) :
        Version(versionMajor, versionMinor, versionPatch) {
        override fun toVersionName(): String =
            "${versionMajor}.${versionMinor}.${versionPatch}"
    }

    class ReleaseCandidate(
        versionMajor: Int,
        versionMinor: Int,
        versionPatch: Int,
        versionBuild: Int
    ) :
        Version(versionMajor, versionMinor, versionPatch, versionBuild) {
        override fun toVersionName(): String =
            "${versionMajor}.${versionMinor}.${versionPatch}-rc.$versionBuild"
    }
}

val currentVersion: Version = Version.Stable(
    versionMajor = 1,
    versionMinor = 16,
    versionPatch = 0,
)

val keystorePropertiesFile: File = rootProject.file("keystore.properties")

val splitApks = !project.hasProperty("noSplits")

val abiFilterList = (properties["ABI_FILTERS"] as String).split(';')


android {
    if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties()
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
        signingConfigs {
            // A dedicated release config. This used to overwrite the "debug" config, which
            // meant release builds were signed as debug: the in-app updater can only
            // install an APK signed with the same key as the installed app, so the release
            // key has to be its own config that the release build type actually selects.
            create("release") {
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
                storeFile = file(keystoreProperties["storeFile"]!!)
                storePassword = keystoreProperties["storePassword"].toString()
            }
        }
    }

    compileSdk = 34



    defaultConfig {
        applicationId = "com.junkfood.seal"
        minSdk = 21
        targetSdk = 34
        versionCode = 11600

        if (splitApks) {
            splits {
                abi {
                    isEnable = true
                    reset()
                    include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                    isUniversalApk = true
                }
            }
        }

        versionName = currentVersion.toVersionName().run {
            if (!splitApks) "$this-(F-Droid)"
            else this
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ksp {
            arg(RoomSchemaArgProvider(File(projectDir, "schemas")))
            arg("room.incremental", "true")
        }
        if (!splitApks) {
            ndk {
                abiFilters.addAll(abiFilterList)
            }
        }
    }
    val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

    androidComponents {
        onVariants { variant ->

            variant.outputs.forEach { output ->
                val name =
                    if (splitApks) {
                        output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
                    } else {
                        abiFilterList.firstOrNull()
                    }

                val baseAbiCode = abiCodes[name]

                if (baseAbiCode != null) {
                    output.versionCode.set(baseAbiCode + (output.versionCode.get() ?: 0))
                }

            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // The static shortcut's <intent> has to name the package explicitly, and a
            // literal in the XML is wrong for any variant whose applicationId is suffixed:
            // a debug install's shortcut pointed at the release package and did nothing.
            resValue("string", "shortcut_target_package", defaultConfig.applicationId!!)
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "SealSync Debug")
            resValue(
                "string",
                "shortcut_target_package",
                "${defaultConfig.applicationId}$applicationIdSuffix"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        disable.addAll(
            listOf(
                "MissingTranslation",
                "ExtraTranslation",
                "MissingQuantity",
                "DuplicatePlatformClasses"
            )
        )
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "SealSync-${defaultConfig.versionName}-${name}.apk"
        }
    }

    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.androidxComposeCompiler.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
        }
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        generateLocaleConfig = true
    }

    namespace = "com.junkfood.seal"
}

kotlin {
    jvmToolchain(11)
}

dependencies {

    implementation(project(":color"))

    //Core libs for the app
    implementation(libs.bundles.core)

    //Lifecycle support for Jetpack Compose
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewModelCompose)

    //Material UI, Accompanist...
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.androidxCompose)
    implementation(libs.bundles.accompanist)

    //Coil (For Jetpack Compose)
    implementation(libs.coil.kt.compose)

    //Serialization
    implementation(libs.kotlinx.serialization.json)

    //DI (Dependency Injection - Hilt)
    implementation(libs.androidx.hilt.navigation.compose)
    kapt(libs.hilt.ext.compiler)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    //Database powered by Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network requests (OkHttp)
    implementation(libs.okhttp)

    //YouTube Data API v3 (for playlist metadata)
    implementation("com.google.apis:google-api-services-youtube:v3-rev20240213-2.0.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    //YoutubeDl for Android (youtubedl-android)
    implementation(libs.bundles.youtubedlAndroid)

    //SVG implementation (AndroidSVG by Caverock)
    implementation(libs.caverock.androidsvg)

    //MMKV (Ultrafast Key-Value storage)
    implementation(libs.mmkv)

    //WorkManager, for the scheduled background sync
    implementation(libs.androidx.work.runtime)

    //Unit testing libraries
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
//  androidTestImplementation(libs.androidx.compose.ui.test)

    // The Compose layout inspector. Debug-only: it exists to be attached to from Android
    // Studio, and as a plain `implementation` it was compiled into release builds too,
    // carrying its tooling classes into the shipped APK for nothing. The `@Preview`
    // annotation itself comes from ui-tooling-preview, which stays in the main bundle.
    debugImplementation(libs.androidx.compose.ui.tooling)

}

class RoomSchemaArgProvider(
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val schemaDir: File
) : CommandLineArgumentProvider {

    override fun asArguments(): Iterable<String> {
        return listOf("room.schemaLocation=${schemaDir.path}")
    }
}
