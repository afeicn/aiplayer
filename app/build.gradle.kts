plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.afeicn.aiplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.afeicn.aiplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-ui"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")

    // 远程海报图片加载（搜索结果 vod_pic / 离线条目 cover URL）。
    // coil-core 不带网络引擎，必须配 coil-network-okhttp 才能拉 http 图片，
    // 顺带拿到 Headers API，可给图片请求伪装浏览器 UA（影视图床常拒绝默认 UA）。
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // HLS（.m3u8 点播）：DefaultMediaSourceFactory 反射加载 HlsMediaSource.Factory。
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-database:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
