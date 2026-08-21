package jp.jig.glasses.sample.kmp.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExplanationGuardTest {
    private val guard = ExplanationGuard(
        visibleStarNames = setOf("アンタレス"),
        knownStarNames = setOf("アンタレス", "シリウス", "ベガ"),
    )

    @Test
    fun `視野内の星と星図の見た目だけなら採用する`() {
        assertNull(guard.rejectionReason("アンタレスを目印に、細い星座線をたどれます。"))
    }

    @Test
    fun `視野外の固有名星を補った文は棄却する`() {
        assertNotNull(guard.rejectionReason("視野の右にはシリウスが見えます。"))
    }

    @Test
    fun `提供していない距離や神話を補った文は棄却する`() {
        assertNotNull(guard.rejectionReason("その光は五百光年を旅してきました。"))
        assertNotNull(guard.rejectionReason("ギリシャ神話では英雄として描かれます。"))
    }
}
