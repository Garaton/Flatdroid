import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Android ABI -> Alpine/Flathub architecture name. */
val architectures = mapOf("arm64-v8a" to "aarch64", "x86_64" to "x86_64")

val alpine = Properties().apply {
    rootProject.file("native/alpine.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.garathon.flatdroid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.garathon.flatdroid"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.6"

        buildConfigField("String", "ALPINE_BRANCH", "\"${alpine.getProperty("branch")}\"")
        buildConfigField("String", "ALPINE_VERSION", "\"${alpine.getProperty("version")}\"")
        for (arch in architectures.values) {
            buildConfigField(
                "String", "ALPINE_SHA256_${arch.uppercase()}", "\"${alpine.getProperty("sha256.$arch")}\""
            )
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per ABI plus a universal one; each carries its own proot.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
            versionCode = 10 * defaultConfig.versionCode!! + 1
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
            versionCode = 10 * defaultConfig.versionCode!! + 2
        }
        create("universal") {
            dimension = "abi"
            ndk { abiFilters += architectures.keys }
            versionCode = 10 * defaultConfig.versionCode!!
        }
    }

    packaging { jniLibs { useLegacyPackaging = true } }
    ndkVersion = "30.0.16248370"

    // Release signing reads FLATDROID_RELEASE_* from ~/.gradle/gradle.properties; without them,
    // release builds are unsigned.
    signingConfigs {
        val storeFile = providers.gradleProperty("FLATDROID_RELEASE_STORE_FILE").orNull
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("FLATDROID_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("FLATDROID_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("FLATDROID_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

/** Runs native/build-proot.sh for one ABI; output is a jniLibs root (<dir>/<abi>/lib*.so). */
abstract class BuildProotTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val abi: Property<String>
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val tallocConfig: RegularFileProperty
    @get:InputDirectory abstract val patches: DirectoryProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Input val ndkPath: Provider<String> get() = ndkDirectory.map { it.asFile.absolutePath }
    @get:Internal abstract val workDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDirectory.get().asFile.also { it.deleteRecursively() }
        exec.exec {
            commandLine(
                script.get().asFile.absolutePath, abi.get(), ndkPath.get(),
                workDirectory.get().asFile.absolutePath, File(out, abi.get()).absolutePath,
            )
        }
    }
}

/** Runs native/build-fdsec.sh for one ABI; output is a jniLibs root (<dir>/<abi>/libfdsec.so). */
abstract class BuildFdsecTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val abi: Property<String>
    @get:InputFile abstract val script: RegularFileProperty
    @get:InputFile abstract val source: RegularFileProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Input val ndkPath: Provider<String> get() = ndkDirectory.map { it.asFile.absolutePath }
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDirectory.get().asFile.also { it.deleteRecursively() }
        exec.exec {
            commandLine(script.get().asFile.absolutePath, abi.get(), ndkPath.get(), File(out, abi.get()).absolutePath)
        }
    }
}

val fdsecTasks = architectures.keys.associateWith { abi ->
    tasks.register<BuildFdsecTask>("buildFdsec_$abi") {
        this.abi = abi
        script = rootProject.file("native/build-fdsec.sh")
        source = rootProject.file("native/fdsec.c")
        ndkDirectory = androidComponents.sdkComponents.ndkDirectory
        outputDirectory = layout.buildDirectory.dir("generated/fdsec/$abi")
    }
}

val prootTasks = architectures.keys.associateWith { abi ->
    tasks.register<BuildProotTask>("buildProot_$abi") {
        this.abi = abi
        script = rootProject.file("native/build-proot.sh")
        tallocConfig = rootProject.file("native/talloc-config.h")
        patches = rootProject.layout.projectDirectory.dir("native/patches")
        ndkDirectory = androidComponents.sdkComponents.ndkDirectory
        workDirectory = layout.buildDirectory.dir("native-work")
        outputDirectory = layout.buildDirectory.dir("generated/proot/$abi")
    }
}

androidComponents {
    onVariants { variant ->
        val abis = when (variant.flavorName) {
            "arm64" -> listOf("arm64-v8a")
            "x86_64" -> listOf("x86_64")
            else -> architectures.keys.toList()
        }
        for (abi in abis) {
            variant.sources.jniLibs?.addGeneratedSourceDirectory(prootTasks.getValue(abi), BuildProotTask::outputDirectory)
            variant.sources.jniLibs?.addGeneratedSourceDirectory(fdsecTasks.getValue(abi), BuildFdsecTask::outputDirectory)
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // Icons the core set lacks (import). Large; only the icons used remain once release builds shrink code.
    implementation(libs.androidx.compose.material.icons.extended)
    // App icons that Flatpaks export only as SVG.
    implementation(libs.androidsvg)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
