plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "app.quotadeck"
 compileSdk = 35
 defaultConfig { applicationId = "app.quotadeck"; minSdk = 26; targetSdk = 35; versionCode = 44; versionName = "1.0.43" }
 buildFeatures { compose = true; buildConfig = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.compose.ui:ui-tooling-preview")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 debugImplementation("androidx.compose.ui:ui-tooling")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
