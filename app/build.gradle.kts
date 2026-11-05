plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lsfg.fork"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lsfg.fork"
        minSdk = 29
        targetSdk = 35
        versionCode = 36
        versionName = "1.36"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = "27.0.12077973"

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Host-side regression test for the pure-C++ shader DLL parser.
val exe = "build/dll_parser_test"
val runDllParserTest = tasks.register<Exec>("runDllParserTest") {
    workingDir = file("src/test/cpp")
    commandLine(
        "/bin/sh", "-c",
        "mkdir -p build && g++ -std=c++20 -O0 -o $exe main.cpp " +
            "../../main/cpp/framegen/library/dll.cpp -I ../../main/cpp/framegen " +
            "&& $exe ../../main/assets/lsfg-vk.dll"
    )
}
val runDisplayQueueTest = tasks.register<Exec>("runDisplayQueueTest") {
    workingDir = file("src/test/cpp")
    commandLine("/bin/sh", "-c", "mkdir -p build && g++ -std=c++20 -pthread -O0 -o build/display_queue_test " +
        "display_queue_test.cpp ../../main/cpp/lsfg_display.cpp -I stubs -I ../../main/cpp && build/display_queue_test && g++ -std=c++20 -O0 -o build/rgba_copy_test rgba_copy_test.cpp -I ../../main/cpp && build/rgba_copy_test")
}
val runOutputRingTest = tasks.register<Exec>("runOutputRingTest") {
    workingDir = file("src/test/cpp")
    commandLine("/bin/sh", "-c", "mkdir -p build && g++ -std=c++20 -O0 -DVK_USE_PLATFORM_ANDROID_KHR " +
        "-I stubs -I ../../main/cpp -I ../../main/cpp/framegen/thirdparty " +
        "ahb_output_ring_test.cpp ../../main/cpp/framegen/ahb_output_ring.cpp -o build/ahb_output_ring_test && build/ahb_output_ring_test")
}
tasks.matching { it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest" }
    .configureEach { dependsOn(runDllParserTest, runDisplayQueueTest, runOutputRingTest) }

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // libsu 5.x (com.topjohnwu.superuser API) for root shell execution
    implementation("com.github.topjohnwu.libsu:core:5.3.0")
    // libsu service: RootService binder for the ported RootCaptureEngine
    implementation("com.github.topjohnwu.libsu:service:5.3.0")
    // Shizuku (dev.rikka) API + provider for the ported ShizukuCaptureEngine
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
