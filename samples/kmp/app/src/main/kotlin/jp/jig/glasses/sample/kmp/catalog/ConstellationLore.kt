package jp.jig.glasses.sample.kmp.catalog

import android.content.Context
import org.json.JSONObject

/**
 * 88 星座の解説文。**通信なしで喋るために端末が持つ。**
 *
 * 解説をその場で AI に作らせていたときは、**圏外だと一言も出せなかった**。
 * 星を見に行く場所ほど電波が届かないので、いちばん要るときに黙ることになる。
 * 神話も豆知識も星座ごとに決まっていて変わらないため、星表と同じように同梱する。
 *
 * 実体は `data/constellation-lore.json`（生成物）で、文面は
 * `tools/build-constellation-lore.py` が持っている。
 */
class ConstellationLore(private val byName: Map<String, String>) {

    /** 星座名で引く。**グラスに出しているラベルの文字列をそのまま渡す** */
    fun of(nameJa: String): String? = byName[nameJa]

    val size: Int get() = byName.size

    companion object {
        val empty = ConstellationLore(emptyMap())

        fun load(context: Context): ConstellationLore {
            val json = runCatching {
                JSONObject(
                    context.assets.open("constellation-lore.json").use {
                        it.readBytes().toString(Charsets.UTF_8)
                    },
                )
            }.getOrNull() ?: return empty
            val array = json.optJSONArray("lore") ?: return empty
            val byName = HashMap<String, String>(array.length())
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                byName[entry.getString("nameJa")] = entry.getString("text")
            }
            return ConstellationLore(byName)
        }
    }
}
