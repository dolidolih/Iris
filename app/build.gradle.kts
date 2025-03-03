plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "party.qwer.Iris"
    compileSdk = 34

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
}

android.applicationVariants.all {
    val variant = this
    val outputPath = "${rootProject.rootDir.path}/output"

    variant.assembleProvider.configure {
        doLast {
            copy {
                variant.outputs.forEach { output ->
                    val file =
                        zipTree(file(output.outputFile)).matching { include("classes*.dex") }.singleFile

                    from(file)
                    into(outputPath)
                    rename { fileName ->
                        fileName.replace(file.name, "Iris-${output.name}.dex")
                    }
                }
            }
        }
    }
}

dependencies {
    compileOnly(files("libs/android-30.jar"))
}