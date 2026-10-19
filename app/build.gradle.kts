plugins { id("com.android.application") }
android {
    namespace = "com.calibre90.astestmob"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.calibre90.astestmob"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("buildNumber")?.toString()?.toIntOrNull() ?: 1)
        versionName = "1.0." + (project.findProperty("buildNumber")?.toString()?.toIntOrNull() ?: 1).toString()
    }
    buildFeatures { buildConfig = true }
    // Publishable key is public, but must be provided to the test build explicitly.
    val supabasePublicKey = System.getenv("SUPABASE_PUBLISHABLE_KEY") ?: ""
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"" + supabasePublicKey + "\"")
        }
        getByName("release") {
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"" + supabasePublicKey + "\"")
        }
    }
    // One stable signing key is required for Android in-place upgrades.
    // CI falls back to its ephemeral debug key until the owner configures secrets.
    val signingFile = System.getenv("MAZDA_SIGNING_STORE_FILE")
    val signingPassword = System.getenv("MAZDA_SIGNING_STORE_PASSWORD")
    val signingAlias = System.getenv("MAZDA_SIGNING_KEY_ALIAS")
    val keyPassword = System.getenv("MAZDA_SIGNING_KEY_PASSWORD")
    if (!signingFile.isNullOrBlank() && !signingPassword.isNullOrBlank()
        && !signingAlias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
        signingConfigs {
            create("stableUpdates") {
                storeFile = file(signingFile)
                storePassword = signingPassword
                keyAlias = signingAlias
                this.keyPassword = keyPassword
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("stableUpdates")
            }
        }
    }
}
