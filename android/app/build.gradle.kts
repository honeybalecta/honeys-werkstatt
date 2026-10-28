import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val hwVersion = providers.environmentVariable("HW_VERSION").getOrElse("1.0.0").removePrefix("v")
val hwCode = providers.environmentVariable("HW_VERSION_CODE").getOrElse("1").toInt()

android {
    namespace = "de.honeybalecta.werkstatt"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.honeybalecta.werkstatt"
        minSdk = 29
        targetSdk = 36
        versionCode = hwCode
        versionName = hwVersion
    }

    // Fester Schlüssel im Repo (PKCS12, passwortgeschützt); das Passwort kommt aus dem Secret
    // ANDROID_KEYSTORE_PASSWORD. Nur so lassen sich Updates über die installierte Version spielen.
    signingConfigs {
        create("release") {
            val pw = providers.environmentVariable("HW_KEYSTORE_PASSWORD").orNull
            if (pw != null) {
                storeFile = file("../keystore/release.p12")
                storeType = "pkcs12"
                storePassword = pw
                keyAlias = "werkstatt"
                keyPassword = pw
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources { excludes += listOf("META-INF/*.kotlin_module", "META-INF/*.version") }
    }
    lint {
        // Seitenlade-App ohne Play Store: QUERY_ALL_PACKAGES und REQUEST_INSTALL_PACKAGES sind gewollt
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
