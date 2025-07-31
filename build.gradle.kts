plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "org.syarkavy"
version = "0.0.1"

repositories {
    mavenCentral()
}

kotlin {
    /*val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" && isArm64 -> macosArm64("native")
        hostOs == "Mac OS X" && !isArm64 -> macosX64("native")
        hostOs == "Linux" && isArm64 -> linuxArm64("native")
        hostOs == "Linux" && !isArm64 -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }*/

    val nativeTarget = mingwX64()
    nativeTarget.apply {
        binaries {
            executable(listOf(RELEASE)) {
                baseName = "${project.name}-${project.version}-${buildType.name.lowercase()}"
                entryPoint = "main"
                debuggable = false
                runTaskProvider?.configure {
                    args("--profile", "BOTH_DRYRUN", "--lmode", "FULL", "-item", "\\\"11\\\"", "-item", "22")
                }
                //args("AAC_VOLUME", "-1", "myfile.m4a")
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
            implementation( libs.cliktNative)
            //implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
        }
    }
}
/*dependencies {
   commonMainImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}*/
