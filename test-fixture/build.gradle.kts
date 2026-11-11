plugins { id("com.android.application") }
android {
    namespace = "dev.orbit.fixture"
    compileSdk = 35
    defaultConfig { applicationId = "dev.orbit.fixture"; minSdk = 26; targetSdk = 35 }
    flavorDimensions += "version"
    productFlavors {
        create("old") { dimension = "version"; versionCode = 1; versionName = "1.0" }
        create("newer") { dimension = "version"; versionCode = 2; versionName = "2.0" }
    }
}
