import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "org.kavach"
    compileSdk = 34

    androidResources {
        noCompress += "mapdata"
    }

    // The statewide route archive is a separately distributed release artifact (~297 MB).
    // Set KAVACH_RELEASE_DATA_DIR to its directory to embed the signed, hash-pinned archive.
    val routeDataDir = providers.environmentVariable("KAVACH_RELEASE_DATA_DIR").orNull
        ?: rootProject.file("../release-data").absolutePath
    sourceSets.getByName("main").assets.srcDir(file(routeDataDir))

    defaultConfig {
        applicationId = "org.kavach"
        minSdk = 26          // Android 8.0; the dossier's 2 GB Go-class target still runs this
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0-prototype"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "deployment"
    productFlavors {
        create("demo") {
            dimension = "deployment"
            buildConfigField("boolean", "DEMO_MODE", "true")
            buildConfigField("String", "PACK_SIGNER_PUBLIC_KEY_HEX", "\"\"")
            buildConfigField("String", "RESPONDER_KEY_ID", "\"demo-responder-01\"")
        }
        create("production") {
            dimension = "deployment"
            buildConfigField("boolean", "DEMO_MODE", "false")
            buildConfigField("String", "PACK_SIGNER_PUBLIC_KEY_HEX", "\"\"")
            buildConfigField("String", "RESPONDER_KEY_ID", "\"\"")
        }
    }

    buildTypes {
        getByName("release") {
            val signingFile = providers.gradleProperty("releaseStoreFile").orNull ?: System.getenv("RELEASE_STORE_FILE")
            val signingPassword = providers.gradleProperty("releaseStorePassword").orNull ?: System.getenv("RELEASE_STORE_PASSWORD")
            val alias = providers.gradleProperty("releaseKeyAlias").orNull ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPassword = providers.gradleProperty("releaseKeyPassword").orNull ?: System.getenv("RELEASE_KEY_PASSWORD")
            val signerHex = providers.gradleProperty("productionPackSignerPublicKeyHex").orNull
                ?: System.getenv("PRODUCTION_PACK_SIGNER_PUBLIC_KEY_HEX")
            val responderKeyId = providers.gradleProperty("productionResponderKeyId").orNull
                ?: System.getenv("PRODUCTION_RESPONDER_KEY_ID")
            if (signingFile != null && signingPassword != null && alias != null && keyPassword != null && signerHex != null && responderKeyId != null) {
                require(file(signingFile).isFile) { "RELEASE_STORE_FILE does not point to an existing keystore file" }
                require(signerHex.matches(Regex("[0-9a-fA-F]{64}"))) {
                    "productionPackSignerPublicKeyHex must be a 32-byte Ed25519 public key in hex"
                }
                require(!signerHex.equals("01fe925f426cee5b2ed4730c8b45403553c8ffd15740ad0eb2317719235c4d37", ignoreCase = true)) {
                    "Production cannot use the publicly derivable demo pack signer"
                }
                signingConfig = signingConfigs.create("production") {
                    storeFile = file(signingFile)
                    storePassword = signingPassword
                    keyAlias = alias
                    this.keyPassword = keyPassword
                }
                buildConfigField("String", "PACK_SIGNER_PUBLIC_KEY_HEX", "\"${signerHex.lowercase()}\"")
                require(responderKeyId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "productionResponderKeyId must contain 1-80 letters, digits, dots, underscores, or hyphens" }
                require(responderKeyId != "demo-responder-01") { "Production cannot use the demo responder identity" }
                @Suppress("UNCHECKED_CAST")
                val trust = JsonSlurper().parse(file("src/main/assets/pack/trust.json")) as Map<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val trustEntries = trust["entries"] as List<Map<String, Any?>>
                require(trustEntries.any { it["key_id"] == responderKeyId && it["role"] == "field_responder" }) {
                    "Production responder key ID must be enrolled as a field_responder in src/main/assets/pack/trust.json"
                }
                buildConfigField("String", "RESPONDER_KEY_ID", "\"$responderKeyId\"")
            } else {
                // Allow the IDE to configure debug variants; never package a release without real credentials.
                signingConfig = null
            }
            tasks.matching { it.name == "preProductionReleaseBuild" }.configureEach {
                doFirst {
                    error(if (signingFile == null || signingPassword == null || alias == null || keyPassword == null || signerHex == null || responderKeyId == null) {
                        "Production release requires RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD, PRODUCTION_PACK_SIGNER_PUBLIC_KEY_HEX, and PRODUCTION_RESPONDER_KEY_ID (or equivalent Gradle properties)"
                    } else {
                        "Production release is disabled until responder signing and trusted-device enrollment are implemented and validated"
                    })
                }
            }
        }
    }

    buildTypes {
        // -PsideBySide installs a debug build next to an existing install without touching its data.
        if (providers.gradleProperty("sideBySide").isPresent) {
            getByName("debug") {
                applicationIdSuffix = ".atlas"
                ndk { abiFilters += "arm64-v8a" } // the test phone; keeps wireless installs small
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.bouncycastle)
    implementation(libs.play.nearby)
    implementation(libs.maplibre)

    testImplementation(libs.junit)
}
