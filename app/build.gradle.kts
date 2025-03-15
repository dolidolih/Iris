plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "party.qwer.iris"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            multiDexEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }
}

android.applicationVariants.all {
    val variant = this
    val outputPath = "${rootProject.rootDir.path}/output"

    variant.assembleProvider.configure {
        doLast {
            copy {
                variant.outputs.forEach { output ->
                    val file = output.outputFile

                    from(file)
                    into(outputPath)
                    rename { fileName ->
                        fileName.replace(file.name, "Iris-${output.name}.apk")
                    }
                }
            }
        }
    }
}


dependencies {
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
}