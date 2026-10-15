plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.arbelonson.ozen"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.arbelonson.ozen"
        minSdk = 26
        targetSdk = 36
        // A release takes the build number of the iPhone build it ships
        // beside (release.yml), so one number names both.
        versionCode = providers.gradleProperty("ozenVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("ozenVersionName").orNull ?: "0.1.0"
        testInstrumentationRunner = "com.arbelonson.ozen.OzenTestRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
            }
        }
    }

    ndkVersion = "30.0.16248370"

    // The release key lives outside the repository (CI reads it from its
    // secrets). Every update has to be signed with the same key or Android
    // refuses to install it over the app, so without it a release build
    // comes out unsigned rather than signed with some other key.
    val keystore = providers.environmentVariable("OZEN_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("OZEN_KEYSTORE_PASSWORD").get()
                keyAlias = "ozen"
                keyPassword = providers.environmentVariable("OZEN_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    androidResources {
        noCompress += "bin"
    }

    sourceSets {
        getByName("androidTest") {
            assets.srcDir("../../Tests/OzenPlatformTests/Fixtures")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libparakeet.so"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)
    implementation(libs.litert)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
