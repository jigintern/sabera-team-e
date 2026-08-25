package jp.jig.glasses.sample.kmp.glass

import app.jigglass.glass.CommandManager

/**
 * 時代を送る窓を、**途中が空にならないように**送る（#45）。
 *
 * 全画面ではやらない。転送時間の下限は **面積 ÷ 32**（RLE の連長上限が 32 なので、
 * 真っ黒でもこれだけかかる）で、528×330 は 231ms ＝ 4fps が上限になり点滅にしかならない。
 * **星を減らしても背景ぶんは減らない**ので、精度ではなく枠の大きさで稼ぐ。
 *
 * | 枠 | 背景だけの下限 | 1 枚 | 上限 fps |
 * |---|---|---|---|
 * | 528×330 | 5,445 B | 231ms | 4 |
 * | 240×160 | 1,200 B | 51ms | 20 |
 */
object TimelapseWindow {
    /** 240×160。**バッファは 2 枚で 153,600 バイト**なので、星図（348,480）とは同居できない */
    const val WIDTH = 240
    const val HEIGHT = 160

    /** 星は絞る。星座絵・天の川・名前は出さない（流れているあいだ誰も読めない） */
    const val LIMIT_MAGNITUDE = 3.5
}

/** 星図（0）と案内の矢印（1）の次。**2 枚を交互に使う** */
const val TIMELAPSE_IMAGE_ID_A = 2
const val TIMELAPSE_IMAGE_ID_B = 3

/**
 * 重ねた画像の前後関係が**置いた順**なら、2 枚交互で完全に消えなくなる。
 *
 * **id 順だった場合は交互の片方向で 1 枚ぶん透ける**ので、そのときはここを false にして
 * 1 枚だけ使う形へ落とす。**どちらなのかは実機で見るまで分からない**
 * （[docs/team-e/17_sky-simulation.md] の「未確認」）。
 */
const val TIMELAPSE_DOUBLE_BUFFER = true

/**
 * 窓を 1 枚ずつ送る。**新しいのを置いてから古いのを消す**のが肝。
 *
 * 逆にすると「消えている時間」が生まれて、それがそのままチカチカになる。
 */
class TimelapseSender(private val commandManager: CommandManager) {

    private var visibleId: Int? = null

    /** 次の 1 枚を出す。返すのは**この枚の転送にかかる見込み時間**（呼ぶ側がその間待つ） */
    suspend fun show(map: StarMap): Long {
        val next = when {
            !TIMELAPSE_DOUBLE_BUFFER -> TIMELAPSE_IMAGE_ID_A
            visibleId == TIMELAPSE_IMAGE_ID_A -> TIMELAPSE_IMAGE_ID_B
            else -> TIMELAPSE_IMAGE_ID_A
        }
        val previous = visibleId
        commandManager.sendCanvasImage(
            id = next,
            x = (PANEL_WIDTH - map.width) / 2,
            y = (PANEL_HEIGHT - map.height) / 2,
            width = map.width,
            height = map.height,
            grayscale = map.gray,
        )
        // **置いたあとで消す。** 先に消すと、その間パネルに何も無い時間ができる
        if (previous != null && previous != next) {
            runCatching { commandManager.removeCanvasImage(previous) }
        }
        visibleId = next
        return map.transferMillis()
    }

    /** 窓を片付ける。**両方消す**（どちらが残っているか分からない経路があるため） */
    suspend fun clear() {
        runCatching { commandManager.removeCanvasImage(TIMELAPSE_IMAGE_ID_A) }
        runCatching { commandManager.removeCanvasImage(TIMELAPSE_IMAGE_ID_B) }
        visibleId = null
    }
}

/**
 * 圧縮後のバイト数から出す転送の見込み時間。
 *
 * **送信は積むだけで返る**ので、これで待たないと転送が追いつかず、
 * 古い枚が順番待ちで残る（[docs/team-e/11_pitfalls.md]）。
 */
fun StarMap.transferMillis(packetMs: Long = TIMELAPSE_PACKET_MS): Long {
    val packets = (compressedSizeBytes() + CANVAS_PACKET_BYTES - 1) / CANVAS_PACKET_BYTES
    return packets * packetMs
}

/** 1 パケット 200 バイトの実測は 8〜9ms。**遅いほうで見積もる**（積むより待つほうが安全） */
const val TIMELAPSE_PACKET_MS = 9L

/** 窓 2 枚ぶんのバッファ。星図と同居できないことを呼ぶ側が確かめるために出す */
fun timelapseBufferUsageBytes(): Int =
    TimelapseWindow.WIDTH * TimelapseWindow.HEIGHT * 2 * if (TIMELAPSE_DOUBLE_BUFFER) 2 else 1
