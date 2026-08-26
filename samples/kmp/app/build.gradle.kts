plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

// **解説文の生成にはもう OpenAI を使わない。** 88 星座ぶんを端末が持っている
// （data/constellation-lore.json）。API キーは読み上げの声だけに使う
// 読み上げの声。gpt-4o-mini-tts は話し方まで指示できる（OpenAiSpeech.INSTRUCTIONS）。
// 声の好みは実機で聴かないと決まらないので、差し替えられるようにしてある
// 声の質問（#38）だけは通信が要る。**聞き取りと回答のモデル**
val openAiTranscribeModel =
    secret("OPENAI_TRANSCRIBE_MODEL", "openAiTranscribeModel").ifEmpty { "gpt-4o-mini-transcribe" }
val openAiAnswerModel = secret("OPENAI_ANSWER_MODEL", "openAiAnswerModel").ifEmpty { "gpt-4o-mini" }

val openAiTtsModel = secret("OPENAI_TTS_MODEL", "openAiTtsModel").ifEmpty { "gpt-4o-mini-tts" }
val openAiTtsVoice = secret("OPENAI_TTS_VOICE", "openAiTtsVoice").ifEmpty { "alloy" }

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
        buildConfigField("String", "OPENAI_TRANSCRIBE_MODEL", "\"$openAiTranscribeModel\"")
        buildConfigField("String", "OPENAI_ANSWER_MODEL", "\"$openAiAnswerModel\"")
        buildConfigField("String", "OPENAI_TTS_MODEL", "\"$openAiTtsModel\"")
        buildConfigField("String", "OPENAI_TTS_VOICE", "\"$openAiTtsVoice\"")
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
    implementation(libs.sabera.app.core)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    // 設定パネルのアイコン。**暗い屋外では文字より形のほうが速く見つかる**。
    // core には音量・明るさ・衛星が無いので extended を入れる（未使用ぶんは R8 が落とす）
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    // 画面の状態は ViewModel が持つ（回転や再コンポーズで消えない・JVM テストから触れる）
    implementation(libs.lifecycle.viewmodel.compose)

    // 台本を QR で配る（toB）。core は純 Java なので生成も解読も同じ 1 個で足りる。
    // **ZXing Android Embedded は使わない**（独自 Activity を持ち込み、画面の向きの
    // 縦固定と衝突する）。**ML Kit も使わない**（Play Services をその場で落とすので、
    // オフラインで完結するという土台が崩れる）
    implementation(libs.zxing.core)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // 座標変換は実機に載せる前に手元で検算する
    testImplementation(libs.junit)
    // 進行係（Runner）は仮想時間で回して検算する
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar の org.json はスタブで例外を投げるので、テストでは本物を先に読ませる
    testImplementation(libs.org.json)
}
