package jp.jig.glasses.sample.kmp.sky

import java.time.ZoneId
import java.util.Locale

/** 音声とスマホから選べる、圏外でも引ける都市。 */
data class City(
    val id: String,
    val nameJa: String,
    val site: Site,
    val zoneId: ZoneId,
    val aliases: Set<String>,
)

/**
 * MVPで扱う都市の一覧。
 *
 * 任意の地名検索は通信と曖昧性を持ち込むので行わない。日本の主要都市と、時差・南北半球を
 * 試せる代表都市だけを端末へ同梱する。別名もここに閉じ、聞き取った文字から座標を推測しない。
 */
object CityCatalog {
    val cities: List<City> = listOf(
        city("sapporo", "札幌", 43.0618, 141.3545, "Asia/Tokyo", "さっぽろ", "sapporo"),
        city("tokyo", "東京", 35.6762, 139.6503, "Asia/Tokyo", "とうきょう", "tokyo"),
        city("sabae", "鯖江", 35.9432, 136.1846, "Asia/Tokyo", "さばえ", "sabae"),
        city("nagoya", "名古屋", 35.1815, 136.9066, "Asia/Tokyo", "なごや", "nagoya"),
        city("osaka", "大阪", 34.6937, 135.5023, "Asia/Tokyo", "おおさか", "osaka"),
        city("fukuoka", "福岡", 33.5902, 130.4017, "Asia/Tokyo", "ふくおか", "fukuoka"),
        city("naha", "那覇", 26.2124, 127.6809, "Asia/Tokyo", "なは", "naha", "沖縄", "おきなわ"),
        city("sydney", "シドニー", -33.8688, 151.2093, "Australia/Sydney", "しどにー", "sydney"),
        city("singapore", "シンガポール", 1.3521, 103.8198, "Asia/Singapore", "しんがぽーる", "singapore"),
        city("honolulu", "ホノルル", 21.3069, -157.8583, "Pacific/Honolulu", "ほのるる", "honolulu"),
        city(
            "los-angeles", "ロサンゼルス", 34.0522, -118.2437, "America/Los_Angeles",
            "ろさんぜるす", "losangeles",
        ),
        city(
            "new-york", "ニューヨーク", 40.7128, -74.0060, "America/New_York",
            "にゅーよーく", "newyork",
        ),
        city("london", "ロンドン", 51.5074, -0.1278, "Europe/London", "ろんどん", "london"),
        city("paris", "パリ", 48.8566, 2.3522, "Europe/Paris", "ぱり", "paris"),
        city("dubai", "ドバイ", 25.2048, 55.2708, "Asia/Dubai", "どばい", "dubai"),
        city("delhi", "デリー", 28.6139, 77.2090, "Asia/Kolkata", "でりー", "delhi", "newdelhi"),
        city("bangkok", "バンコク", 13.7563, 100.5018, "Asia/Bangkok", "ばんこく", "bangkok"),
        city(
            "cape-town", "ケープタウン", -33.9249, 18.4241, "Africa/Johannesburg",
            "けーぷたうん", "capetown",
        ),
    )

    /** 文中にある最長の別名を採る。`LA`のような短い別名が英単語の一部へ当たるのを避ける。 */
    fun findIn(text: String): City? {
        val normalized = normalize(text)
        return cities.asSequence()
            .flatMap { city -> city.aliases.asSequence().map { alias -> city to normalize(alias) } }
            .filter { (_, alias) -> alias.length >= 2 && alias in normalized }
            .maxByOrNull { (_, alias) -> alias.length }
            ?.first
    }

    internal fun normalize(text: String): String = buildString(text.length) {
        for (char in text.lowercase(Locale.ROOT)) {
            when (char) {
                in '０'..'９' -> append('0' + (char - '０'))
                '：' -> append(':')
                '　', ' ', '\t', '\n', '\r' -> Unit
                else -> append(char)
            }
        }
    }

    private fun city(
        id: String,
        nameJa: String,
        latDeg: Double,
        lonDeg: Double,
        zoneId: String,
        vararg aliases: String,
    ): City = City(
        id = id,
        nameJa = nameJa,
        site = Site(latDeg, lonDeg),
        zoneId = ZoneId.of(zoneId),
        aliases = aliases.toSet() + nameJa,
    )
}
