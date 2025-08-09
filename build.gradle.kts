import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.buildKonfigPlugin)
}

group = "org.sergy"
version = "0.0.4"
val applicationName: String by extra("pipeWrapper")

repositories {
    mavenCentral()
}

kotlin {
    val nativeTarget = mingwX64()
    nativeTarget.apply {
        binaries {
            executable(listOf(RELEASE)) {
                baseName = "${project.name}-${project.version}-${buildType.name.lowercase()}"
                entryPoint = "${group}.${applicationName.lowercase()}.main"
                debuggable = false
                    /* add run config if you need runTaskProvider?.configure {
                    args("-arg1", "param1", "--arg2", "param2"
                    */
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
            implementation(libs.cliktNative)
        }
    }
}

buildkonfig {
    packageName = "$group.${applicationName.lowercase()}"

    // default config is required
    defaultConfigs {
        buildConfigField (STRING, "version", version.toString())
        buildConfigField (STRING, "applicationName",applicationName.lowercase())
    }
}
