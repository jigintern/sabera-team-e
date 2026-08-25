package jp.jig.glasses.sample.kmp.sky

/**
 * スマホから**選ぶだけ**で空を切り替えるための候補（#45）。
 *
 * **使う人は天文の初心者で、「紀元前 3000 年 8 月 24 日 20:30」を打ちたいわけではない。**
 * 数字を打つ欄は「細かく指定」を開いたときだけ出し、ふだんはここから選ばせる。
 *
 * **選んだものは日本語の一文へ組み立てて [SkyCommandParser] へ渡す。**
 * 解釈を 2 つ持つと、片方だけ直したときに「スマホでは出せるのに声では出せない」が起きる。
 */

/** 選択肢 1 つ。[phrase] は組み立てる一文へそのまま挟む断片（空なら何も足さない） */
data class SkyPreset(val label: String, val phrase: String)

object SkyPresets {

    /**
     * 時代。**幅は「見て違いが分かるか」で選んでいる。**
     *
     * 歳差は 25,772 年で 1 周なので、100 年では 1.4°（ほぼ気づかない）、
     * 1,000 年で 14°、1 万年で 140° になる。**刻みを細かくしても見分けられない。**
     */
    val eras: List<SkyPreset> = listOf(
        SkyPreset("今夜", ""),
        SkyPreset("100年前", "100年前"),
        SkyPreset("1000年前", "1000年前"),
        SkyPreset("5000年前", "5000年前"),
        SkyPreset("1万年前", "1万年前"),
        SkyPreset("1000年後", "1000年後"),
        SkyPreset("5000年後", "5000年後"),
        SkyPreset("1万年後", "1万年後"),
    )

    /**
     * 時刻。**「何時か」ではなく「夜のどのあたりか」で選ばせる。**
     *
     * 分まで選ばせても、星の位置は 4 分で 1° しか動かない。
     */
    val times: List<SkyPreset> = listOf(
        SkyPreset("日暮れ", "19時"),
        SkyPreset("夜", "21時"),
        SkyPreset("真夜中", "0時"),
        SkyPreset("明け方", "4時"),
        SkyPreset("今と同じ時刻", "今と同じ時刻"),
    )

    /** 場所。**同梱の都市に「現在地」を足しただけ**（任意の地名検索はしない） */
    val places: List<SkyPreset> =
        listOf(SkyPreset("現在地", "")) + CityCatalog.cities.map { SkyPreset(it.nameJa, it.nameJa) }

    /** 既定の選択。**開いた直後に押しても何か出る**ようにしておく */
    val defaultEra: SkyPreset = eras.first { it.label == "1万年前" }
    val defaultTime: SkyPreset = times.first { it.label == "夜" }
    val defaultPlace: SkyPreset = places.first()

    /**
     * 選んだものを、声で言うのと同じ一文へ組み立てる。
     *
     * 「今夜」を選んだときは時代の断片が空になるので、**時刻だけの指定**になる
     * （その日の指定した時刻へ飛ぶ）。
     */
    fun phraseOf(place: SkyPreset, era: SkyPreset, time: SkyPreset): String = buildString {
        if (place.phrase.isNotEmpty()) append(place.phrase).append("の")
        if (era.phrase.isNotEmpty()) append(era.phrase).append("の")
        append(time.phrase).append("の空を見せて")
    }
}
