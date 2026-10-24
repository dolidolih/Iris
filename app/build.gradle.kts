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
        // SQLCipher 네이티브 라이브러리(sqlcipher-android) 의 ABI 는 이 기기/에뮬레이터
        // 계열만 싣는다 — arm64(실기기) 와 x86_64(redroid/emu). APK 를 /data/local/tmp 에
        // push 해서 app_process 로 돌리기 때문에, 설치 시 native lib 추출 없이
        // APK 안에서 직접 lib/<abi>/libsqlcipher.so 를 뽑아낸다 (SqlCipherNative).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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

    // SQLCipher — crypto_database / crypto_user_database (카톡의 암호화된 DB) 를 읽는 데 쓴다.
    // native lib 는 APK 에 함께 들어가고, app_process 환경에서는 SqlCipherNative 가 직접
    // unzip 해서 dlopen 한다.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
}