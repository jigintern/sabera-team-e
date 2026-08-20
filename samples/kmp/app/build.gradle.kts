plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * 秘密情報を探す。リポジトリ直下の .env が正で、置き場所を変えたい人のために後ろ 3 つも見る。
 *
 * どれも無ければ空文字を返してビルドは通す。キーが無いだけでビルドが落ちると、
 * AI を使わない人（星図だけ直す人）まで巻き添えになる。
 */
fun secret(envName: String, gradleName: String): String {
    fun dotenv(file: File): String? = file.takeIf { it.isFile }
        ?.readLines()
        ?.firstNotNullOfOrNull { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || "=" !in trimmed) return@firstNotNullOfOrNull null
            val (name, value) = trimmed.split("=", limit = 2)
            if (name.trim() != envName) null else value.trim().trim('"', '\'')
        }
        ?.takeIf { it.isNotEmpty() }

    return dotenv(rootProject.file("../../.env"))
        ?: dotenv(rootProject.file("local.properties"))
        ?: (project.findProperty(gradleName) as String?)?.takeIf { it.isNotEmpty() }
        ?: System.getenv(envName)?.takeIf { it.isNotEmpty() }
        ?: ""
}

val openAiApiKey = secret("OPENAI_API_KEY", "openAiApiKey")
val openAiModel = secret("OPENAI_MODEL", "openAiModel").ifEmpty { "gpt-4o" }

// 推論の強さ。空なら送らない（推論を持たないモデルに送ると 400 で弾かれる）。
// 送らないと既定の強さで推論が走り、待たされたうえに出力枠を食い潰して本文が空になる
val openAiReasoningEffort = secret("OPENAI_REASONING_EFFORT", "openAiReasoningEffort")

android {
    namespace = "jp.jig.glasses.sample.kmp"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.jig.sabera.app.sample.kmp"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // キーは APK に埋まる。逆コンパイルすれば読めるので、配布せず手元の実機で動かす前提
        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        buildConfigField("String", "OPENAI_MODEL", "\"$openAiModel\"")
        buildConfigField("String", "OPENAI_REASONING_EFFORT", "\"$openAiReasoningEffort\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xskip-prerelease-check")
        }
    }

    sourceSets {
        // 星表はリポジトリ直下の data/ が正。コピーを置くと二重管理になるのでここから読む
        getByName("main").assets.srcDir(rootProject.file("../../data"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // Sabera App SDK
    implementation("jp.jig.sabera.app.sdk:sabera-app-core:0.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // AndroidX
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // 座標変換は実機に載せる前に手元で検算する
    testImplementation("junit:junit:4.13.2")
    // android.jar の org.json はスタブで例外を投げるので、テストでは本物を先に読ませる
    testImplementation("org.json:json:20240303")
}
