import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.artifact.SingleArtifact
import java.io.RandomAccessFile
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

@DisableCachingByDefault(because = "Each release build reserves a new persistent version")
abstract class ReserveReleaseVersion : DefaultTask() {
    @get:Internal abstract val counterFile: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun reserve() {
        val counter = counterFile.get().asFile
        check(counter.isFile) { "Missing release counter: restore release-version.txt before building" }
        val next = RandomAccessFile(counter, "rw").use { file ->
            file.channel.lock().use {
                val previous = file.readLine()?.toIntOrNull()
                check(previous != null && previous in 0..2_099_999_998) { "Invalid release counter" }
                val value = previous + 1
                file.seek(0)
                file.writeBytes("$value\n")
                file.setLength(file.filePointer)
                file.channel.force(true)
                value
            }
        }
        outputFile.get().asFile.apply { parentFile.mkdirs(); writeText(next.toString()) }
        logger.lifecycle("Reserved release f.${next.toString().padStart(3, '0')} (versionCode ${next + 1})")
    }
}
val releaseSigningVariables = listOf(
    "BUILDLEDGER_KEYSTORE_PATH", "BUILDLEDGER_KEY_ALIAS",
    "BUILDLEDGER_STORE_PASSWORD", "BUILDLEDGER_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.map { System.getenv(it) }
val hasReleaseSigning = releaseSigningValues.any { !it.isNullOrBlank() }
val verificationApplication = providers.gradleProperty("verificationApplication").orNull == "true"
tasks.configureEach {
    if (!verificationApplication && name.startsWith("connected") && name.endsWith("AndroidTest")) {
        doFirst { error("Device tests require -PverificationApplication=true to protect the ordinary installation") }
    }
}
val selectedTestClass = providers.gradleProperty("android.testInstrumentationRunnerArguments.class").orNull
require(selectedTestClass == null || selectedTestClass.isNotBlank() && !selectedTestClass.contains(',')) {
    "Select one nonempty test class or class#method per run; AGP 9.4.1 truncates comma-valued runner arguments"
}
if (hasReleaseSigning) {
    val missing = releaseSigningVariables.filterIndexed { index, _ -> releaseSigningValues[index].isNullOrBlank() }
    require(missing.isEmpty()) { "Missing release signing environment variables: ${missing.joinToString()}" }
    require(file(releaseSigningValues[0]!!).isFile) { "BUILDLEDGER_KEYSTORE_PATH must reference an existing file" }
}
android {
    namespace = "com.constructioncost.nativeapp"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = if (verificationApplication)
            "com.constructioncost.nativeapp.verification" else "com.constructioncost.nativeapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (selectedTestClass == null) {
            // AGP 9.4.1's test engine splits comma-valued runner arguments, so filter one annotation.
            testInstrumentationRunnerArguments["notAnnotation"] = "com.constructioncost.nativeapp.SeparateVerification"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (hasReleaseSigning) {
        signingConfigs {
            create("production") {
                storeFile = file(releaseSigningValues[0]!!)
                keyAlias = releaseSigningValues[1]
                storePassword = releaseSigningValues[2]
                keyPassword = releaseSigningValues[3]
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (verificationApplication) signingConfig = signingConfigs.getByName("debug")
            else if (hasReleaseSigning) signingConfig = signingConfigs.getByName("production")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(libs.core)
    implementation(libs.exif)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.navigation)
    implementation(libs.datastore)
    implementation(libs.coroutines)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
    // Room's migration helper requires the 1.8.1 serializer interface in the tested app.
    debugImplementation(libs.serialization.json)
}
androidComponents.onVariants { variant ->
    if (variant.buildType == "release" && !verificationApplication) {
        val releaseVersion = tasks.register<ReserveReleaseVersion>("reserveReleaseVersion") {
            counterFile.set(layout.projectDirectory.file("../release-version.txt"))
            outputFile.set(layout.buildDirectory.file("generated/release-version/sequence.txt"))
        }
        val sequence = releaseVersion.flatMap { it.outputFile }.map { it.asFile.readText().toInt() }
        variant.outputs.forEach { output ->
            output.versionCode.set(sequence.map { it + 1 })
            output.versionName.set(sequence.map { "f.${it.toString().padStart(3, '0')}" })
        }
    }
    val name = variant.name.replaceFirstChar { it.uppercase() }
    val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    val verifyOffline = tasks.register("verify${name}OfflineManifest") {
        inputs.file(manifest)
        doLast {
            val text = manifest.get().asFile.readText()
            check(text.contains("android.permission.INTERNET") && text.contains("android.permission.REQUEST_INSTALL_PACKAGES") && !text.contains("android.permission.ACCESS_NETWORK_STATE")) {
                "Only update networking and user-approved installation permissions are expected"
            }
        }
    }
    tasks.matching { it.name in listOf("process${name}Resources", "package$name", "assemble$name") }
        .configureEach { dependsOn(verifyOffline) }
}
