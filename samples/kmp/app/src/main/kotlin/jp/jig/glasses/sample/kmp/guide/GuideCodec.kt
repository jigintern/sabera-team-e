package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.glass.GlassTextPage
import java.util.zip.Deflater
import java.util.zip.Inflater

/** 受け取った台本の読み込み結果。**断る理由は人が読める文で持つ**（黙って消さない） */
sealed interface GuideImport {
    data class Ok(val guide: StarGuide) : GuideImport
    data class Rejected(val reason: String) : GuideImport
}

/**
 * 台本を配る／受け取る口。
 *
 * **QR とファイルで形を変える。**
 *
 * - **QR** = 圧縮した生バイト。1 枚（version 40・誤り訂正 L）に入るのは
 *   [QR_CAPACITY_BYTES] しかなく、Base64 を挟むと 4 割損して 5 段が限界になる
 * - **ファイル** = 素の JSON。容量の理由が無いので圧縮しない。
 *   旅行会社が PC で文面を直す逃げ道になる
 *
 * どちらも中身は [StarGuideJson] のまま。**入出力の口を増やさない。**
 *
 * **Android に触らない**（`java.util.zip` は JDK 側）ので JVM テストで往復を固定できる。
 */
object GuideCodec {

    /**
     * QR 1 枚に入るバイト数。**version 40・誤り訂正 L・バイトモード**の上限。
     *
     * 本文 200 字なら 10 段で埋まる（実測は docs/team-e/37_guide.md）。
     * **段数の固定上限は置かない。** 文の長さで入る段数が変わるので、実測して止める。
     */
    const val QR_CAPACITY_BYTES = 2_953

    /** 受け取れる段数の上限。**中身は見ないが、形は弾く** */
    const val MAX_STEPS = 30

    /** 1 段の本文。これを超えるぶんはグラスに出ない（[GlassTextPage.pagedChars] = 272 字） */
    val MAX_BODY_CHARS: Int get() = GlassTextPage.pagedChars

    /** 見出しと一行の上限。一覧の 1 行に収める */
    const val MAX_TITLE_CHARS = 40
    const val MAX_SUMMARY_CHARS = 120

    /**
     * 展開してよい上限。**圧縮爆弾で固まらせない。**
     *
     * QR は 2,953 バイトしか運べないが、Deflate は千倍に膨らませられる。
     * 上限を置かないと、悪意ある 1 枚で端末のメモリを食い潰せる。
     */
    const val MAX_INFLATED_BYTES = 128 * 1024

    /** 配るぶんを圧縮する。**外した段は入らない**（読めない段のために枠を食わない） */
    fun pack(guide: StarGuide): ByteArray = deflate(json(guide).toByteArray(Charsets.UTF_8))

    /** 配るぶんの JSON。ファイルへ書くのもこれ */
    fun json(guide: StarGuide): String = StarGuideJson.encode(guide.copy(steps = guide.enabledSteps))

    /** QR にあと何バイト置けるか。負なら入らない */
    fun qrRemainingBytes(guide: StarGuide): Int = QR_CAPACITY_BYTES - pack(guide).size

    fun fitsInQr(guide: StarGuide): Boolean = qrRemainingBytes(guide) >= 0

    /** 0.0〜1.0。残量バーに出す */
    fun qrUsedRatio(guide: StarGuide): Float =
        (pack(guide).size.toFloat() / QR_CAPACITY_BYTES).coerceIn(0f, 1f)

    /** QR から読んだ生バイトを台本にする */
    fun unpack(bytes: ByteArray): GuideImport {
        val text = runCatching { inflate(bytes) }.getOrNull()
            ?: return GuideImport.Rejected("この QR は星座ガイドではないようです。")
        return fromJson(text)
    }

    /** ファイルから読んだ JSON を台本にする */
    fun fromJson(text: String): GuideImport {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_INFLATED_BYTES) {
            return GuideImport.Rejected("台本が大きすぎます。")
        }
        val guide = StarGuideJson.decode(text)
            ?: return GuideImport.Rejected("台本として読めませんでした。")
        validate(guide)?.let { return GuideImport.Rejected(it) }
        // **受け取ったものだと分かるようにする。** どこから来た文かを辿れないと、
        // あとで「なぜこの文なのか」を説明できない（GuideOrigin を置いた理由）
        return GuideImport.Ok(guide.copy(origin = GuideOrigin.RECEIVED))
    }

    /**
     * 形だけ見る。**中身は見ない。**
     *
     * 記号を落としたり文を切ったりはしない。AI の答えと違って、これは
     * **旅行会社が責任を持って書いた文**で、勝手に壊すと意図した表記が消える。
     */
    private fun validate(guide: StarGuide): String? = when {
        guide.steps.size > MAX_STEPS -> "段が多すぎます（${guide.steps.size} 段）。"
        guide.title.length > MAX_TITLE_CHARS -> "見出しが長すぎます。"
        guide.summary.length > MAX_SUMMARY_CHARS -> "説明が長すぎます。"
        guide.steps.any { it.body.length > MAX_BODY_CHARS } ->
            "グラスに入らない長さの解説があります（1 段 $MAX_BODY_CHARS 字まで）。"
        else -> null
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream(raw.size)
            val buffer = ByteArray(BUFFER_BYTES)
            // **1 回で書き切れる前提にしない。** 圧縮が効かない入力では膨らむ
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer))
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(bytes: ByteArray): String {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val buffer = ByteArray(BUFFER_BYTES)
            // **バイトのまま溜めてから、最後に一度だけ文字にする。**
            // 区切りごとに String へ直すと、3 バイトの日本語が境界をまたいだとき壊れる
            val out = java.io.ByteArrayOutputStream()
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                // 入力を食い切っても終わらないなら、これ以上は伸びない（壊れた入力）
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                require(out.size() + read <= MAX_INFLATED_BYTES) { "展開が大きすぎる" }
                out.write(buffer, 0, read)
            }
            require(inflater.finished()) { "展開しきれなかった" }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            inflater.end()
        }
    }

    private const val BUFFER_BYTES = 4 * 1024
}
