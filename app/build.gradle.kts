plugins {
    id("com.android.application")
}

android {
    namespace = "dev.joycon2.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.joycon2.bridge"
        minSdk = 31
        targetSdk = 36
        versionCode = 15
        versionName = "1.4.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

    }

    ndkVersion = "21.4.7075529"

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    sourceSets.getByName("main").jniLibs.directories.add(
        layout.buildDirectory.dir("generated/joyconJni").get().asFile.absolutePath
    )

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

val compileJoyConNative by tasks.registering(Exec::class) {
    val sdkRoot = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK")
    val hostTag = if (System.getProperty("os.name").lowercase().contains("windows")) {
        "windows-x86_64"
    } else {
        "linux-x86_64"
    }
    val executableName = if (hostTag.startsWith("windows")) "clang++.exe" else "clang++"
    val toolchain = file("$sdkRoot/ndk/21.4.7075529/toolchains/llvm/prebuilt/$hostTag")
    val compiler = file("$toolchain/bin/$executableName")
    val source = file("src/main/cpp/joycon_evdev.cpp")
    val output = layout.buildDirectory.file(
        "generated/joyconJni/arm64-v8a/libjoycon_evdev.so"
    )

    inputs.file(source)
    outputs.file(output)
    doFirst {
        output.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        compiler.absolutePath,
        "--target=aarch64-none-linux-android30",
        "--gcc-toolchain=${toolchain.absolutePath}",
        "--sysroot=${file("$toolchain/sysroot").absolutePath}",
        "-fPIC",
        "-shared",
        "-std=c++17",
        "-Wall",
        "-Wextra",
        "-Werror",
        "-Wl,--no-undefined",
        "-Wl,--gc-sections",
        "-static-libstdc++",
        source.absolutePath,
        "-llog",
        "-latomic",
        "-lm",
        "-o",
        output.get().asFile.absolutePath
    )
}

tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(compileJoyConNative)
}

dependencies {
    val shizukuVersion = "13.1.5"
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")
    testImplementation("junit:junit:4.13.2")
}
