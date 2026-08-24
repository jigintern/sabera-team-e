package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTarget

/** 台本を 1 本作った結果。**同梱へ落ちたときは、なぜ落ちたかを添える** */
data class GuideDraft(val guide: StarGuide, val fellBackReason: String?)

/**
 * 即興ガイドを 1 本作る（toC）。**AI と同梱の切り替えはここだけ。**
 *
 * ```
 * 端末が候補の星座を選ぶ            ← ImpromptuGuide.candidates（AI に選ばせない）
 *    ↓
 * 電波があって鍵もある → AI が文を書く
 *    ↓ 失敗・空・検査落ち
 * 同梱の 88 星座で組む              ← 必ずここへ落ちてくる
 * ```
 *
 * **「作れませんでした」で終わらせない。** 圏外でも鍵が無くても、同梱だけで 1 本できる
 * （星が空に出てさえいれば）。台本には出どころ（[GuideOrigin]）を残すので、
 * あとから「なぜこの文なのか」を辿れる。
 *
 * [writer] を関数で受けているのは、**この package から通信を見せない**ため
 * （`openai/` 側が `guide/` を読む向きだけにしておく）。
 * おかげで**退路そのものを JVM テストで固定できる**。
 */
class GuideMaker(
    private val lore: (String) -> String?,
    private val brightestMagnitude: (String) -> Double? = { null },
    /** 通信して文を書かせる口。**使えないなら null**（そのまま同梱で組む） */
    private val writer: (suspend (GuideTheme, List<GuidanceTarget>, Long, String) -> StarGuide?)? = null,
) {

    /** 作れなければ null（候補の星座が 1 つも空に出ていないとき） */
    suspend fun make(
        theme: GuideTheme,
        targets: List<GuidanceTarget>,
        createdAtMillis: Long,
        id: String,
    ): GuideDraft? {
        val picked = ImpromptuGuide.candidates(
            targets = targets,
            theme = theme,
            lore = lore,
            brightestMagnitude = brightestMagnitude,
        ).filter { lore(it.nameJa) != null }
        // 同梱の本文が無い星座は、案内できても喋ることが無い
        if (picked.isEmpty()) return null

        val bundled = ImpromptuGuide.compose(theme, picked, lore, createdAtMillis, id)
            ?: return null

        val write = writer ?: return GuideDraft(bundled, "通信を使わない設定です")
        val reason = runCatching { write(theme, picked, createdAtMillis, id) }
            .fold(
                onSuccess = { written -> if (written != null) return GuideDraft(written, null) else "AI の返事を使えませんでした" },
                onFailure = { error -> error.message ?: "通信できませんでした" },
            )
        return GuideDraft(bundled, reason)
    }
}
