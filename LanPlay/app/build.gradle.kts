import java.util.Properties

plugins {
    // AGP 9.0 起内置 Kotlin 支持，不再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

// Debug/IDE sync must not depend on a release secret. Signing is accepted only from
// environment variables or a properties file outside the workspace.
val releaseSigningSource = sequenceOf(
    System.getenv("LANPLAY_SIGNING_PROPERTIES")?.takeIf { it.isNotBlank() }?.let(::file),
    System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
        ?.let { file("$it/LanPlay/signing/keystore.properties") },
).filterNotNull().firstOrNull { it.isFile }
val releaseSigning = when {
    listOf(
        "LANPLAY_KEYSTORE_PATH",
        "LANPLAY_KEYSTORE_PASSWORD",
        "LANPLAY_KEY_ALIAS",
        "LANPLAY_KEY_PASSWORD",
    ).all { !System.getenv(it).isNullOrBlank() } -> Properties().apply {
        setProperty("storeFile", System.getenv("LANPLAY_KEYSTORE_PATH"))
        setProperty("storePassword", System.getenv("LANPLAY_KEYSTORE_PASSWORD"))
        setProperty("keyAlias", System.getenv("LANPLAY_KEY_ALIAS"))
        setProperty("keyPassword", System.getenv("LANPLAY_KEY_PASSWORD"))
    }
    releaseSigningSource != null -> Properties().apply {
        releaseSigningSource.inputStream().use(::load)
    }
    else -> null
}

android {
    namespace = "com.lanplay.player"
    // 最新 androidx 要求编译期 API 37；targetSdk 仍按需求 §5.1 保持 36（Android 16）
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lanplay.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
        buildConfigField("String", "BUILD_DATE", "\"2026-08-08\"")

        // libVLC 含原生库，只交付当前两台目标手机需要的 arm64，避免把四套架构
        // 一起塞进 APK。Media3 主内核本身不受影响。
        ndk {
            abiFilters += "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("lanplayRelease") {
            if (releaseSigning != null) {
                val configuredPath = releaseSigning.getProperty("storeFile")
                val configuredFile = File(configuredPath)
                storeFile = if (configuredFile.isAbsolute) {
                    configuredFile
                } else {
                    (releaseSigningSource?.parentFile ?: rootProject.projectDir)
                        .resolve(configuredPath)
                }
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            } else {
                // Debug/IDE sync 仍可工作；任何 release 打包会由 validateSigningRelease
                // 因这个明确不存在的工作区外占位文件而在产物生成前失败。
                storeFile = File(System.getProperty("java.io.tmpdir"), "lanplay-signing-not-configured.jks")
                storePassword = ""
                keyAlias = ""
                keyPassword = ""
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 测试钩子与 LANPLAY_METRIC 输出的总开关
            buildConfigField("boolean", "METRICS_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("lanplayRelease")
            buildConfigField("boolean", "METRICS_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle 与 mbassador 带签名与多版本目录，不排除会打包失败
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/versions/**",
                "META-INF/INDEX.LIST",
                "module-info.class"
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.media3.datasource)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.smbj)
    // 仅用于连接向导枚举主机上的共享；播放与文件 IO 仍由 SMBJ 承担
    implementation(libs.jcifs)
    implementation(libs.nanohttpd)
    implementation(libs.libvlc)

    // smbj 走 slf4j；debug 让它输出到 logcat 便于排查，release 静默
    debugImplementation(libs.slf4j.simple)
    releaseImplementation(libs.slf4j.nop)
    implementation(libs.profileinstaller)
    implementation(libs.biometric)
    implementation(libs.sqlcipher.android)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.test.ext.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
    "baselineProfile"(project(":baselineprofile"))
}

baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
}

// Gradle/JDK 在包含中文的 Windows 工程路径下会把 UnitTest classes 的 argfile
// classpath 错误转义，表现为“编译成功但 ClassNotFoundException”。把仅测试用的
// classes 暂存到 LOCALAPPDATA 的 ASCII 路径，生产 APK 内容不受影响。
val stagedUnitTestRoot = file(
    "${System.getenv("LOCALAPPDATA") ?: layout.buildDirectory.get().asFile}/" +
        "LanPlay/gradle-unit-tests/app"
)
val stageDebugUnitTestClasses = tasks.register<org.gradle.api.tasks.Sync>(
    "stageDebugUnitTestClassesForWindows",
) {
    dependsOn("transformDebugUnitTestClassesWithAsm", "bundleDebugClassesToRuntimeJar")
    from(layout.buildDirectory.dir("intermediates/classes/debugUnitTest/transformDebugUnitTestClassesWithAsm/dirs")) {
        into("test")
    }
    from(layout.buildDirectory.file("intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar")) {
        into("main")
    }
    into(stagedUnitTestRoot)
}

afterEvaluate {
    tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest") {
        dependsOn(stageDebugUnitTestClasses)
        testClassesDirs = files(File(stagedUnitTestRoot, "test"))
        val originalClasspath = classpath
        val buildPath = layout.buildDirectory.get().asFile.absolutePath
        classpath = files(
            File(stagedUnitTestRoot, "test"),
            File(stagedUnitTestRoot, "main/classes.jar"),
        ) + originalClasspath.filter { !it.absolutePath.startsWith(buildPath) }
    }
}
