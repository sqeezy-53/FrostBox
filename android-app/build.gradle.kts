plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "org.frostbox.app"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "org.frostbox.app"
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = if (providers.gradleProperty("withEmpireBay").orNull == "true") 20 else if (providers.gradleProperty("withWow64").orNull == "true") 17 else if (providers.gradleProperty("withHorizonUi").orNull == "true") 16 else if (providers.gradleProperty("withPhysicalRecovery").orNull == "true") 15 else if (providers.gradleProperty("withRemoteAudio").orNull == "true") 14 else if (providers.gradleProperty("withWineInput").orNull == "true") 13 else if (providers.gradleProperty("withSurfaceIpcProbe").orNull == "true") 12 else 10
        versionName = if (providers.gradleProperty("withEmpireBay").orNull == "true") "0.7.1-dev-game-experience" else if (providers.gradleProperty("withWow64").orNull == "true") "0.7.0-alpha-horizon-experimental" else if (providers.gradleProperty("withHorizonUi").orNull == "true") "0.7.0-alpha-ui" else if (providers.gradleProperty("withPhysicalRecovery").orNull == "true") "0.6.0-alpha-physical-recovery" else if (providers.gradleProperty("withRemoteAudio").orNull == "true") "0.6.0-alpha-remote-input-audio" else if (providers.gradleProperty("withWineInput").orNull == "true") "0.6.0-alpha-evolution" else if (providers.gradleProperty("withSurfaceIpcProbe").orNull == "true") "0.5.0-dev.4-titan-checkpoint" else "0.5.0-dev.2"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    externalNativeBuild { cmake { path = file("../runtime/native/CMakeLists.txt"); version = "3.22.1" } }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Executables must be installed in the APK's nativeLibraryDir, not writable filesDir.
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libbox64.so"
    }
    sourceSets.getByName("debug").assets.srcDir(rootProject.layout.buildDirectory.dir("launcher-fixtures/assets"))
    sourceSets.getByName("main") {
        jniLibs.srcDir(layout.buildDirectory.dir("generated/runtime/jniLibs"))
        assets.srcDir(layout.buildDirectory.dir("generated/runtime/assets"))
        if (providers.gradleProperty("withBox64Vulkan").orNull == "true") {
            assets.srcDir(rootProject.layout.buildDirectory.dir("vulkan-bridge/assets"))
            jniLibs.srcDir(rootProject.layout.buildDirectory.dir("box64-vulkan-package/jniLibs"))
            assets.srcDir(rootProject.layout.buildDirectory.dir("box64-vulkan-package/assets"))
        }
        if (providers.gradleProperty("withEmpireBay").orNull == "true") assets.srcDir(rootProject.layout.buildDirectory.dir("empire-package/assets"))
        if (providers.gradleProperty("withWow64").orNull == "true") assets.srcDir(rootProject.layout.buildDirectory.dir("wow64-package/assets"))
        if (providers.gradleProperty("withRemoteAudio").orNull == "true") assets.srcDir(rootProject.layout.buildDirectory.dir("remote-package/assets"))
        if (providers.gradleProperty("withWineInput").orNull == "true") assets.srcDir(rootProject.layout.buildDirectory.dir("wine-input-package/assets"))
        if (providers.gradleProperty("withD3d11Probe").orNull == "true") assets.srcDir(rootProject.layout.buildDirectory.dir("d3d11-package/assets"))
        if (providers.gradleProperty("withWineWsi").orNull == "true") assets.srcDir(rootProject.layout.buildDirectory.dir("wine-wsi-package/assets"))
        if (providers.gradleProperty("withWineVulkan").orNull == "true") {
            assets.srcDir(rootProject.layout.buildDirectory.dir("wine-vulkan-package/assets"))
            if (providers.gradleProperty("withSurfaceIpcProbe").orNull == "true") {
                assets.srcDir(rootProject.layout.buildDirectory.dir("wsi-capability/assets"))
                assets.srcDir(rootProject.layout.buildDirectory.dir("wsi-image-probe/assets"))
            }
        }
        if (providers.gradleProperty("withWineProbe").orNull == "true") {
            assets.srcDir(rootProject.layout.buildDirectory.dir("wine-probe-package/assets"))
            if (providers.gradleProperty("withWindowsDisplay").orNull == "true") {
                assets.srcDir(rootProject.layout.buildDirectory.dir("display-package/assets"))
            }
        }
        if (providers.gradleProperty("withBox64").orNull == "true") {
            jniLibs.srcDir(rootProject.layout.buildDirectory.dir("box64-package/jniLibs"))
            assets.srcDir(rootProject.layout.buildDirectory.dir("box64-package/assets"))
        }
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

val prepareRuntimeFixtures by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build-runtime-fixtures.py", "--output", layout.buildDirectory.dir("generated/runtime").get().asFile.absolutePath)
    inputs.files(rootProject.file("scripts/build-runtime-fixtures.py"), rootProject.file("runtime/process/probe.cpp"), rootProject.file("tests/guest/compute-x64.S"), rootProject.file("tests/guest/bionic-probe.c"))
    outputs.dir(layout.buildDirectory.dir("generated/runtime"))
}
tasks.named("preBuild") {
    dependsOn(prepareRuntimeFixtures)
    doFirst {
        if (providers.gradleProperty("withWow64").orNull == "true") {
            check(providers.gradleProperty("withWineProbe").orNull == "true") {"WoW64 diagnostics require the preserved Wine base"}
            check(rootProject.layout.buildDirectory.file("wow64-package/assets/wine-wow64/manifest.json").get().asFile.isFile) {"Package the separate WoW64 candidate first"}
        }
        if (providers.gradleProperty("withSurfaceIpcProbe").orNull == "true") {
            check(providers.gradleProperty("withWineVulkan").orNull == "true") { "Surface diagnostic package requires Wine Vulkan overlay" }
            check(rootProject.layout.buildDirectory.file("wsi-capability/assets/runtime/vulkan-wsi-capability-x64.exe.json").get().asFile.isFile) { "Build the separate WSI capability probe first" }
        }
        if (providers.gradleProperty("withWindowsDisplay").orNull == "true") {
            check(providers.gradleProperty("withWineProbe").orNull == "true") { "Display requires Wine" }
            check(rootProject.layout.buildDirectory.file("display-package/assets/display/manifest.json").get().asFile.isFile) { "Build the experimental display driver first" }
        }
        if (providers.gradleProperty("withWineProbe").orNull == "true") {
            check(providers.gradleProperty("withBox64").orNull == "true") { "Wine probe requires withBox64=true" }
        }
        if (providers.gradleProperty("withBox64").orNull == "true") {
            check(providers.gradleProperty("withWineProbe").orNull != "true" || rootProject.layout.buildDirectory.file("wine-probe-package/assets/runtime/wine-probe.json").get().asFile.isFile) { "Package the Wine candidate before withWineProbe" }
            val pkg = rootProject.layout.buildDirectory.dir("box64-package").get().asFile
            check(pkg.resolve("jniLibs/arm64-v8a/libbox64.so").isFile && pkg.resolve("assets/runtime/box64.json").isFile) {
                "Run scripts/build-box64.py before enabling withBox64"
            }
        }
    }
}

val verifyBox64Package by tasks.registering(Exec::class) {
    onlyIf { providers.gradleProperty("withBox64").orNull == "true" }
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/verify-box64-package.py")
}
tasks.named("preBuild") { dependsOn(verifyBox64Package) }

val verifyDisplayPackage by tasks.registering(Exec::class) {
    onlyIf { providers.gradleProperty("withWindowsDisplay").orNull == "true" }
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/verify-display-package.py")
}
tasks.named("preBuild") { dependsOn(verifyDisplayPackage) }

val verifyVulkanPackage by tasks.registering(Exec::class) {
    onlyIf { providers.gradleProperty("withBox64Vulkan").orNull == "true" }
    workingDir(rootProject.projectDir)
    commandLine(listOf("python3", "scripts/verify-vulkan-package.py") +
        (if (providers.gradleProperty("withWineVulkan").orNull == "true") listOf("--wine") else emptyList()) +
        (if (providers.gradleProperty("withSurfaceIpcProbe").orNull == "true") listOf("--wsi") else emptyList()))
}
tasks.named("preBuild") {
    dependsOn(verifyVulkanPackage)
    doFirst {
        if (providers.gradleProperty("withBox64Vulkan").orNull == "true")
            check(providers.gradleProperty("withBox64").orNull == "true") { "Vulkan diagnostic requires Box64" }
        if (providers.gradleProperty("withWineVulkan").orNull == "true")
            check(providers.gradleProperty("withBox64Vulkan").orNull == "true" && providers.gradleProperty("withWindowsDisplay").orNull == "true") { "Headless Wine experiment requires isolated Box64 and qualified display base" }
    }
}
