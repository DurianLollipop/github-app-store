plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Every published version (including RCs) must increase this code.
val orbitSourceVersionCode = 7
val orbitSourceVersionName = "0.3.1-dev"
val releaseSigning = listOf("ORBIT_KEYSTORE_FILE", "ORBIT_KEYSTORE_PASSWORD", "ORBIT_KEY_ALIAS", "ORBIT_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull?.takeIf(String::isNotBlank) }
val requireSigning = providers.environmentVariable("ORBIT_REQUIRE_RELEASE_SIGNING").orNull == "true"
val hasSigning = releaseSigning.values.all { it != null }
if (requireSigning || releaseSigning.values.any { it != null }) {
    require(hasSigning) { "Release 签名配置不完整，需要 ORBIT_KEYSTORE_FILE / PASSWORD、ORBIT_KEY_ALIAS / PASSWORD" }
    require(file(releaseSigning.getValue("ORBIT_KEYSTORE_FILE")!!).isFile) { "Release 签名文件不存在" }
}

android {
    namespace = "dev.githubstore"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.githubstore"
        minSdk = 26
        targetSdk = 35
        versionCode = orbitSourceVersionCode
        versionName = providers.gradleProperty("orbitVersionName").orElse(orbitSourceVersionName).get()
        require(versionName!!.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?"))) { "版本名称格式不正确" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        fun quotedProperty(name: String) = "\"" + providers.gradleProperty(name).orElse("").get().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        buildConfigField("String", "GITHUB_CLIENT_ID", quotedProperty("orbitGitHubClientId"))
        buildConfigField("String", "CATALOG_URL", quotedProperty("orbitCatalogUrl"))
        buildConfigField("String", "CATALOG_PUBLIC_KEY", quotedProperty("orbitCatalogPublicKey"))
    }
    signingConfigs {
        if (hasSigning) create("release") {
            storeFile = file(releaseSigning.getValue("ORBIT_KEYSTORE_FILE")!!)
            storePassword = releaseSigning.getValue("ORBIT_KEYSTORE_PASSWORD")
            keyAlias = releaseSigning.getValue("ORBIT_KEY_ALIAS")
            keyPassword = releaseSigning.getValue("ORBIT_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

tasks.register("writeCiMetadata") {
    description = "Write source version for CI validation without parsing Kotlin source."
    doLast {
        val destination = layout.buildDirectory.file("ci/source-version.json").get().asFile
        destination.parentFile.mkdirs()
        destination.writeText("""{"versionCode":$orbitSourceVersionCode,"versionName":"$orbitSourceVersionName"}""" + "\n")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
