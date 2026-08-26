package jp.jig.glasses.sample.kmp.ui.starmap

import jp.jig.glasses.sample.kmp.sky.SkyPreset
import jp.jig.glasses.sample.kmp.sky.SkyPresets

/**
 * スマホの再現フォームで打たれた欄を、**声とまったく同じ 1 文**にまとめる。
 *
 * 解釈を 2 つ持つと、片方だけ直したときに「スマホでは出せるのに声では出せない」が起きる。
 * ここで文にしてしまえば、あとは `SkyCommandParser` が 1 か所で解釈する。
 */
internal fun simulationPhrase(
    place: SkyPreset,
    era: SkyPreset,
    time: SkyPreset,
    detailed: Boolean,
    cityText: String,
    eraText: String,
    dateText: String,
    timeText: String,
): String {
    if (!detailed) return SkyPresets.phraseOf(place, era, time)
    // **打った欄を優先する。** わざわざ開いて入れた指定を、選んだものが黙って上書きしない
    val city = cityText.trim().ifEmpty { place.phrase }
    val eraPhrase = eraText.trim().ifEmpty {
        if (dateText.isBlank()) era.phrase else ""
    }
    val date = dateText.trim()
    val timePhrase = timeText.trim().ifEmpty { time.phrase }
    return buildString {
        if (city.isNotEmpty()) append(city).append("の")
        // 時代を入れたら日付より優先する（パーサが「何年前」を先に見る）
        if (eraPhrase.isNotEmpty()) {
            append(eraPhrase).append(' ')
        } else if (date.isNotEmpty()) {
            append(date).append(' ')
        }
        append(timePhrase).append("の空を表示して")
    }
}
