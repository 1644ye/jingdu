plugins { id("com.android.application") }
android {
    namespace = "org.quietreader"
    compileSdk = 36
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    useLibrary("android.test.mock")
    defaultConfig {
        applicationId = "org.quietreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = false } }
    lint {
        // AGP 9.1 OverdrawDetector crashes in Kotlin FIR ("Unexpected owner function: null").
        // Keep all other lint checks enabled; see ACCEPTANCE.md for this tool limitation.
        disable += "Overdraw"
    }
}
dependencies {
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
}
