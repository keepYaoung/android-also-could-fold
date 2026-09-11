plugins { id("com.android.library") }
android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.84")
    implementation("org.bouncycastle:bctls-jdk15to18:1.84")
}
