package jp.jig.glasses.sample.kmp.guide

import jp.jig.glasses.sample.kmp.sky.GuidanceTargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QR とファイルの受け渡し。
 *
 * **実測した容量がここで固定される。** 本文 200 字なら 10 段で QR 1 枚が埋まる。
 * 焼き方を変えて（たとえば Base64 を挟んで）入る段数が落ちたら、ここが落ちる。
 */
class GuideCodecTest {

    /** 本文はすべて別の文章にする。**同じ文を繰り返すと圧縮が不当に効く** */
    private fun body(index: Int, chars: Int): String =
        BODY_SOURCE.drop(index * chars).take(chars).ifEmpty { BODY_SOURCE.take(chars) }

    private fun guide(steps: Int, bodyChars: Int = 200, enabled: (Int) -> Boolean = { true }) = StarGuide(
        id = "guide-1789000000000",
        title = "乗鞍高原・秋の星空ツアー",
        summary = "ペルセウス座からアンドロメダ座までを 40 分でめぐります",
        createdAtMillis = 1_789_000_000_000L,
        origin = GuideOrigin.AUTHORED,
        plannedAtMillis = 1_789_000_000_000L,
        plannedMinutes = 45,
        plannedLatDeg = 36.106,
        plannedLonDeg = 137.626,
        steps = (0 until steps).map {
            GuideStep(
                targetName = NAMES[it % NAMES.size],
                kind = GuidanceTargetKind.CONSTELLATION,
                intro = "ここからは秋の星座です。",
                body = body(it, bodyChars),
                enabled = enabled(it),
            )
        },
    )

    @Test
    fun `圧縮して展開すると同じ台本になる`() {
        val original = guide(5)
        val restored = GuideCodec.unpack(GuideCodec.pack(original))
        assertTrue(restored is GuideImport.Ok)
        val decoded = (restored as GuideImport.Ok).guide
        assertEquals(original.steps, decoded.steps)
        assertEquals(original.plannedAtMillis, decoded.plannedAtMillis)
    }

    /**
     * **日本語が壊れない。**
     *
     * 展開を区切りごとに文字へ直すと、3 バイトの文字が境界をまたいだとき化ける。
     * 化けても JSON としては読めてしまうので、本文を突き合わせて見る。
     */
    @Test
    fun `長い日本語でも文字が化けない`() {
        // **上限いっぱいで見る。** 数字を直に書くとグラスに置ける行数が変わったとき
        // 黙って上限超えになり、化けの検査ではなく長さの検査で落ちる
        val original = guide(8, bodyChars = GuideCodec.MAX_BODY_CHARS)
        val decoded = (GuideCodec.unpack(GuideCodec.pack(original)) as GuideImport.Ok).guide
        assertEquals(original.steps.map { it.body }, decoded.steps.map { it.body })
    }

    /** 受け取ったものだと分かるようにする。**どこから来た文かを辿れなくしない** */
    @Test
    fun `受け取った台本の出どころは受け取ったになる`() {
        val decoded = (GuideCodec.unpack(GuideCodec.pack(guide(3))) as GuideImport.Ok).guide
        assertEquals(GuideOrigin.RECEIVED, decoded.origin)
    }

    /**
     * **実測した容量がここで固まる。** 本文 200 字で 10 段は入り、12 段は入らない。
     *
     * Base64 を挟むと 4 割太って 5 段までしか入らなくなるので、
     * 焼き方を変えたときはここが落ちる。
     */
    @Test
    fun `本文 200 字なら 10 段は QR に入る`() {
        assertTrue(GuideCodec.fitsInQr(guide(10)))
    }

    @Test
    fun `12 段は QR に入らない`() {
        assertFalse(GuideCodec.fitsInQr(guide(12)))
    }

    /** 外した段は配るぶんに入らない。**読めない段のために枠を食わない** */
    @Test
    fun `外した段は QR に入らない`() {
        val half = guide(8, enabled = { it % 2 == 0 })
        val decoded = (GuideCodec.unpack(GuideCodec.pack(half)) as GuideImport.Ok).guide
        assertEquals(4, decoded.size)
        // 外したぶん軽くなるので、全部入りより残量が増える
        assertTrue(GuideCodec.qrRemainingBytes(half) > GuideCodec.qrRemainingBytes(guide(8)))
    }

    @Test
    fun `段が多すぎる台本は断る`() {
        val many = StarGuideJson.encode(guide(GuideCodec.MAX_STEPS + 1, bodyChars = 20))
        val result = GuideCodec.fromJson(many)
        assertTrue(result is GuideImport.Rejected)
        assertNotNull((result as GuideImport.Rejected).reason)
    }

    /** グラスに入らない長さの本文は、**受け取る前に断る**（実機で尻切れにさせない） */
    @Test
    fun `グラスに入らない長さの本文は断る`() {
        val long = StarGuideJson.encode(guide(2, bodyChars = GuideCodec.MAX_BODY_CHARS + 1))
        assertTrue(GuideCodec.fromJson(long) is GuideImport.Rejected)
    }

    @Test
    fun `QR ではないものを読ませても落ちない`() {
        assertTrue(GuideCodec.unpack(byteArrayOf(1, 2, 3, 4, 5)) is GuideImport.Rejected)
        assertTrue(GuideCodec.unpack(ByteArray(0)) is GuideImport.Rejected)
    }

    @Test
    fun `台本でない JSON は断る`() {
        assertTrue(GuideCodec.fromJson("""{"hello":"world"}""") is GuideImport.Rejected)
        assertTrue(GuideCodec.fromJson("これは JSON ではない") is GuideImport.Rejected)
    }

    /**
     * **圧縮爆弾で固まらせない。**
     *
     * QR は 2,953 バイトしか運べないが、Deflate は同じ文字の繰り返しを千倍に膨らませられる。
     * 上限が無いと、悪意ある 1 枚で端末のメモリを食い潰せる。
     */
    @Test
    fun `異常に膨らむ入力は展開を打ち切る`() {
        val bomb = java.io.ByteArrayOutputStream().also { out ->
            val deflater = java.util.zip.Deflater(9)
            deflater.setInput(ByteArray(GuideCodec.MAX_INFLATED_BYTES * 4))
            deflater.finish()
            val buffer = ByteArray(4096)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
        }.toByteArray()
        assertTrue(bomb.size < GuideCodec.QR_CAPACITY_BYTES)
        assertTrue(GuideCodec.unpack(bomb) is GuideImport.Rejected)
    }

    private companion object {
        val NAMES = listOf(
            "ペルセウス座", "カシオペヤ座", "アンドロメダ座", "ペガスス座", "うお座",
            "おひつじ座", "おうし座", "ぎょしゃ座", "はくちょう座", "こと座",
            "わし座", "いるか座", "や座", "とかげ座", "きりん座",
            "みずがめ座", "やぎ座", "くじら座", "エリダヌス座", "オリオン座",
            "ふたご座", "かに座", "しし座", "おとめ座", "うしかい座",
            "かんむり座", "ヘルクレス座", "りゅう座", "こぐま座", "おおぐま座",
            "こじし座", "やまねこ座",
        )

        /**
         * 圧縮に有利にならないよう、**段ごとに重ならない**ように切り出す散文
         * （同梱の星座解説をつないだもの）。同じ文を繰り返すと Deflate が不当に効いて、
         * 実際より多くの段が QR に入るように見える。
         */
        const val BODY_SOURCE =
            "エチオピアの王女アンドロメダの姿です。海の怪物のいけにえにされたところを、勇者ペルセウスに助" +
            "けられました。肉眼で見えるいちばん遠い天体、アンドロメダ銀河がある星座でもあります。空気ポン" +
            "プを表した星座です。18世紀に、フランスの天文学者ラカーユが南の空の星をまとめて名づけました" +
            "。神話は伝わっていません。ごくらくちょうを表した星座です。大航海時代にヨーロッパへ持ち帰られ" +
            "た羽があまりに美しかったので、空にのぼりました。日本からは見えない星座です。大神ゼウスが姿を" +
            "変えた、わしの星座です。いちばん明るいアルタイルは、七夕の彦星として知られています。天の川を" +
            "はさんで、織姫のベガと向かい合っています。神々の宴で酒をつぐ、美少年ガニュメデスの姿です。ゼ" +
            "ウスにさらわれて、水がめを持つ役目をあたえられました。かめから流れ出た水は、みなみのうお座の" +
            "口へ注いでいます。神々が祭壇をきずいて、巨人族との戦いの前に誓いを立てた場所です。勝ったあと" +
            "、ゼウスがその祭壇を空にあげました。日本からは、南の地平線ぎりぎりにしか顔を出しません。空を" +
            "飛ぶ、金色の毛をした羊です。危ないところを逃げる兄と妹を、背に乗せて運びました。その毛皮を探" +
            "しに出かけたのが、アルゴ船の英雄たちの物語です。馬車をあやつる御者の姿です。いちばん明るいカ" +
            "ペラという名前は、子やぎを抱いた雌やぎという意味を持っています。くまを追いかける、牛飼いの姿" +
            "です。オレンジ色に輝くアルクトゥールスは、日本では麦の刈り入れのころに見えるので、麦星と呼ば" +
            "れてきました。狩人オリオンが連れている、大きな犬です。いちばん明るいシリウスは全天でもっとも" +
            "明るい星で、名前には焼き焦がすものという意味があります。オリオンのもう一匹の猟犬です。明るい" +
            "プロキオンという名前には、犬に先立つものという意味があります。おおいぬ座のシリウスより先にの" +
            "ぼるからです。牛飼いが連れている、二匹の猟犬です。くまを追いかけて、いまも空を回り続けていま" +
            "す。渦を巻く姿が美しい、子持ち銀河がある星座です。彫刻に使うのみを表した星座です。18世紀に" +
            "ラカーユが名づけました。明るい星がなく、見つけるのはなかなか難しい星座です。きりんを表した星" +
            "座です。17世紀に、オランダの学者が北の空の何もない場所を埋めるために作りました。暗い星ばか" +
            "りですが、一年じゅう沈みません。上半身がやぎ、下半身が魚という、不思議な姿の星座です。牧神パ" +
            "ーンが怪物に驚いて川に飛び込み、あわてたので、下半身だけ魚になってしまったと伝わります。アル" +
            "ゴ船の船底を表した星座です。いちばん明るいカノープスはシリウスに次いで明るい星ですが、日本で" +
            "は南の地平線すれすれにしか見えません。見えると長生きすると言われ、南極老人星と呼ばれます。エ" +
            "チオピアの王妃カシオペヤです。自分と娘の美しさを自慢して海の神を怒らせ、罰として、いすに座っ" +
            "たまま空を回り続けることになりました。上半身が人、下半身が馬のケンタウロス、賢者ケイロンの姿" +
            "です。いちばん明るい星は、太陽にもっとも近い恒星のなかまとして知られています。エチオピアの王" +
            "ケフェウスです。妃カシオペヤ、娘アンドロメダとそろって空にのぼりました。明るさが規則正しく変" +
            "わる星の、代表がある星座です。アンドロメダ姫をおそった、海の怪物です。ミラという星があり、名" +
            "前は不思議なものという意味で、明るさが大きく変わり、見えなくなる時期もあります。カメレオンを" +
            "表した星座です。大航海時代の航海士たちが、南の空で見つけた星をまとめて名づけました。日本から" +
            "は見ることができません。製図に使うコンパスを表した星座です。ラカーユが名づけました。となりの" +
            "じょうぎ座と合わせて、道具の星座が南の空に集まっています。英雄ヘルクレスに踏みつぶされた、か" +
            "にの星座です。中央にはプレセペと呼ばれる星の集まりがあり、かすんで見えると雨が近いと、昔から" +
            "天気占いに使われてきました。ノアの箱舟から放たれた、はとの星座です。オリーブの枝をくわえて戻" +
            "り、洪水が引いたことを知らせたと言われます。エジプトの王妃ベレニケの髪の毛です。夫の無事を願" +
            "って髪を切り神殿にささげたところ、その髪が消え、空にのぼって星になったと伝えられます。いて座" +
            "の足もとにある、小さな冠の星座です。半円に並んだ星が冠の形をつくります。北のかんむり座と対に" +
            "なる星座として、古くから知られてきました。クレタの王女アリアドネが、結婚のときに贈られた冠で" +
            "す。宝石をちりばめた冠が空にあげられ、輪のように並ぶ星になりました。いちばん明るいアルフェッ" +
            "カは、欠けた輪という意味です。太陽神アポロンの杯を表した星座です。となりのからす座、うみへび" +
            "座と、ひとつの物語でつながっています。使いのカラスがうそをついて罰を受けた話です。南十字星と" +
            "して知られる、全天でいちばん小さな星座です。南半球では方角を知る目印になります。日本では沖縄" +
            "の南の空で、ごく低くしか見えません。アポロンに仕えたカラスの星座です。もとは白い鳥でしたが、" +
            "うそをついた罰に黒くされ、水も飲めないまま空に置かれたと伝わります。ゼウスが白鳥に姿を変えた" +
            "、その姿です。天の川に沿って翼を広げ、大きな十字に見えるので北十字とも呼ばれます。尾のデネブ" +
            "は、夏の大三角のひとつです。海に投げ込まれた歌人アリオンを、背に乗せて助けたイルカです。その" +
            "働きをたたえて、海の神ポセイドンが空にあげました。南の海を泳ぐ魚、しいらを表した星座です。天" +
            "の川銀河のおともをする大マゼラン雲が、この星座にあります。黄金のりんごを守っていた竜、ラドン" +
            "です。北極星の周りを長く取り巻いています。この星座のトゥバンは、ピラミッドが作られたころの北" +
            "極星でした。小さな馬の首だけを表した星座です。ペガススの弟とも、ヘルメスが贈った馬とも言われ" +
            "ます。全天で二番目に小さい星座です。天をかける、川の星座です。太陽の馬車を乗りこなせなかった" +
            "少年パエトンが、落ちた川だと伝えられます。オリオン座の足もとから、南の空へ長く流れています。" +
            "化学の実験に使う炉を表した星座です。ラカーユが名づけた道具の星座のひとつで、明るい星はありま" +
            "せんが、その先には銀河がたくさん集まっています。仲のよい双子の兄弟、カストルとポルックスの星" +
            "座です。弟だけが不死の身だったため、兄と離れるのを悲しみ、ゼウスがふたりそろって空に置いたと" +
            "伝わります。つるを表した星座です。大航海時代に、南の空で名づけられました。つるは古くから、天" +
            "文学者の象徴とされてきたと言われます。ギリシャ神話いちばんの英雄ヘルクレスです。数々の難題を" +
            "やりとげた姿が、こん棒を持って空に描かれています。星が球のように集まった、大集団がある星座で" +
            "す。振り子時計を表した星座です。天文の観測に欠かせない道具として、ラカーユが空に"
    }
}
