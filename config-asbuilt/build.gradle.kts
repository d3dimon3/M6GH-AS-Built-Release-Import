plugins { id("com.android.application") }
android {
    namespace = "com.calibre90.astestmob"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.calibre90.asbuiltconfig"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("buildNumber")?.toString()?.toIntOrNull() ?: 1)
        versionName = "1.0." + (project.findProperty("buildNumber")?.toString()?.toIntOrNull() ?: 1).toString()
    }
    buildFeatures { buildConfig = true }
    val supabasePublicKey = System.getenv("SUPABASE_PUBLISHABLE_KEY") ?: ""
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"" + supabasePublicKey + "\"")
        }
        getByName("release") {
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"" + supabasePublicKey + "\"")
        }
    }
}
android.sourceSets.getByName("main").java.srcDir("../app/src/main/java")
android.sourceSets.getByName("main").res.srcDir("../app/src/main/res")
