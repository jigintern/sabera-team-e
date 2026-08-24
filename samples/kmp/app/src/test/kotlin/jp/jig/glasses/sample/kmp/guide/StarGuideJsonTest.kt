package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarGuideJsonTest {

    private val guide = StarGuide(
        id = "guide-1",
        title = "今夜のおすすめ",
        summary = "3 つの星座を回ります",
        createdAtMillis = 1_700_000_000_000L,
        origin = GuideOrigin.IMPROMPTU_AI,
        steps = listOf(
            GuideStep("さそり座", GuidanceTargetKind.CONSTELLATION, "ここからが主役です。", "毒針を持つさそりの姿です。"),
            GuideStep("いて座", GuidanceTargetKind.CONSTELLATION, "", "半人半馬の弓の名手です。"),
        ),
    )

    @Test
    fun `書いて読むと同じ台本になる`() {
        val decoded = StarGuideJson.decode(StarGuideJson.encode(guide))
        assertEquals(guide, decoded)
    }

    /** 台本は端末に残り続ける。**あとで足したキーで、前の台本が読めなくならない** */
    @Test
    fun `知らないキーが増えても読める`() {
        val json = JSONObject(StarGuideJson.encode(guide)).put("あとで足した", "値").toString()
        assertEquals(guide, StarGuideJson.decode(json))
    }

    /** 壊れた 1 本で一覧ごと開けなくならないよう、**例外ではなく null を返す** */
    @Test
    fun `壊れていたら例外ではなく空で返す`() {
        assertNull(StarGuideJson.decode("これは JSON ではない"))
        assertNull(StarGuideJson.decode("""{"id":"a","title":"b","steps":[]}"""))
    }

    /** 本文の無い段は喋ることが無い。**その段だけ落として台本は生かす** */
    @Test
    fun `本文の無い段は落とす`() {
        val json = """
            {"id":"a","title":"b","origin":"AUTHORED","steps":[
              {"targetName":"さそり座","body":"毒針を持つさそりの姿です。"},
              {"targetName":"いて座"}
            ]}
        """.trimIndent()
        val decoded = StarGuideJson.decode(json)
        assertEquals(1, decoded?.steps?.size)
        assertEquals(GuideOrigin.AUTHORED, decoded?.origin)
        // 種別を書いていない台本（手書き）は星座として扱う
        assertEquals(GuidanceTargetKind.CONSTELLATION, decoded?.steps?.first()?.kind)
    }
}
