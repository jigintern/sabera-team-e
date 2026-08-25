package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
import jp.jig.glasses.sample.kmp.sky.SkyDensity
import jp.jig.glasses.sample.kmp.sound.BgmScene
import jp.jig.glasses.sample.kmp.sound.BgmTrack
import jp.jig.glasses.sample.kmp.support.AskHistory
import jp.jig.glasses.sample.kmp.support.NightRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 観測画面の設定パネル（上のバーの「設定」で開くほう）。
 *
 * **見出しの中身を見出しどおりにする。** 以前はここが「ログ」という 1 枚のカードで、
 * その中に星座絵・目印・声・BGM・音量まで入っていた。**BGM を切りたい人はログを開かない**ので、
 * 触れるはずの設定に辿り着けなかった。
 *
 * **置くのは「使う機能」だけ。** 見え方・明るさ・音・観測地と、今夜の記録とログ。
 * 眺めるだけの情報（空の状態・月齢・空にいる衛星の一覧・これから来るパス）は**置かない**。
 * 空を見ている人はスマホを見ないし、同伴者にとっても**触れない情報は読み飛ばす行**になって、
 * 触るはずの設定が下へ流れていくだけだった。
 *
 * 星図そのものの表示（プレビュー・解説・ボタン 2 つ）はパネルを閉じた側に残す。
 */

/** ログ 1 行。失敗だけ色を変えたいので持っておく */
internal data class LogLine(val at: String, val text: String, val failed: Boolean)

/** 設定パネルの 1 区画。**見出しと中身を必ず組にする**（見出しの外に設定を置かない） */
@Composable
internal fun SettingsSection(
    title: String,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (hint != null) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), content = content)
    }
}

/** 切り替え 1 行。**状態を文で書く**（「オン」ではなく「星図に重ねる」） */
@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 今夜どの星座を解説したか。**読み終わった解説文はどこにも残らなかった。**
 *
 * 字幕は数秒でめくれ、声は一度きり。同伴者がスマホを覗いたときには次の星座に変わっている。
 * ここから読み直せるようにしておく（**声も鳴らし直す**）。
 *
 * **1 つも無いときは何も出さない**（呼ぶ側が畳む）。設定パネルは使う機能だけに絞ってあるので、
 * 「まだ何もありません」だけの区画を置かない。
 */
@Composable
internal fun NightRecordCard(
    entries: List<NightRecord.Seen>,
    onAgain: (NightRecord.Seen) -> Unit,
    onClear: () -> Unit,
) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.JAPAN) }
    SettingsSection("今夜見た星座", "%d 星座".format(entries.map { it.nameJa }.distinct().size)) {
        // 新しいものが上。**下に伸びると、読みたい直前の解説がいちばん遠くなる**
        for (entry in entries.asReversed().take(SHOWN_RECORDS)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${clock.format(Date(entry.atMillis))}　${entry.nameJa}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onAgain(entry) }) { Text("もう一度") }
            }
            Text(
                entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entries.size > SHOWN_RECORDS) {
            Spacer(Modifier.height(4.dp))
            Text(
                "ほかに ${entries.size - SHOWN_RECORDS} 件（古いものは畳んでいる）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("今夜の記録を消す")
            }
        }
    }
}

/** 記録を本文つきで出す件数。**全部並べると設定パネルが解説文で埋まる** */
private const val SHOWN_RECORDS = 8

/**
 * 声で聞いたことと、返ってきた答え（#38）。
 *
 * 字幕は流れて消え、声は一度きり。**同伴者がスマホを覗いたときにはもう次の話**で、
 * 聞いた本人も「さっき何と言われたか」を確かめられなかった。
 *
 * **答えは読み直せるようにする**（声も鳴らし直す）。断りや失敗もそのまま残すので、
 * 質問が届かなかったのか答えが返らなかったのかがここで分かる。
 *
 * **1 つも無いときは何も出さない**（呼ぶ側が畳む）。
 */
@Composable
internal fun AskHistoryCard(
    exchanges: List<AskHistory.Exchange>,
    onAgain: (AskHistory.Exchange) -> Unit,
    onClear: () -> Unit,
) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.JAPAN) }
    SettingsSection("声で聞いたこと", "%d 件".format(exchanges.size)) {
        // 新しいものが上。**下に伸びると、いちばん読みたい直前のやり取りが遠くなる**
        for (exchange in exchanges.asReversed().take(SHOWN_RECORDS)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${clock.format(Date(exchange.atMillis))}　${exchange.question}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // 答えが返らなかったものは鳴らし直さない（断り文をもう一度聞いても何も進まない）
                if (exchange.answered) {
                    TextButton(onClick = { onAgain(exchange) }) { Text("もう一度") }
                }
            }
            Text(
                exchange.answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (exchanges.size > SHOWN_RECORDS) {
            Spacer(Modifier.height(4.dp))
            Text(
                "ほかに ${exchanges.size - SHOWN_RECORDS} 件（古いものは畳んでいる）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
            Text("やり取りを消す")
        }
    }
}

/**
 * グラスに何を描くか。
 *
 * **空の濃さがいちばん効く。** 見えていない星まで描くと、目の前の空と対応が取れなくなる。
 */
@Composable
internal fun SkyViewSettings(
    density: SkyDensity,
    onDensityChange: (SkyDensity) -> Unit,
    showSatellites: Boolean,
    onSatellitesChange: (Boolean) -> Unit,
    showArt: Boolean,
    onArtChange: (Boolean) -> Unit,
    showGuides: Boolean,
    onGuidesChange: (Boolean) -> Unit,
) {
    SettingsSection(
        "見え方",
        // **見えない星を描かないのがいちばん効く**（見えている星と対応が取れなくなる）
        "${density.label}：${density.hint}（${"%.1f".format(density.limitMagnitude)} 等まで）",
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            for (step in SkyDensity.entries) {
                val selected = step == density
                TextButton(
                    onClick = { onDensityChange(step) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (selected) SaberaSelected else Color.Transparent,
                    ),
                ) {
                    Text(
                        step.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        SettingSwitch(
            if (showArt) "星座絵: 出す（星より暗く敷く）" else "星座絵: 出さない",
            showArt,
            onArtChange,
        )
        SettingSwitch(
            if (showGuides) "目印: 地平線と方位（北東南西）を出す" else "目印: 出さない",
            showGuides,
            onGuidesChange,
        )
        SettingSwitch(
            if (showSatellites) "人工衛星: 星図に重ねる" else "人工衛星: 出さない",
            showSatellites,
            onSatellitesChange,
        )
    }
}

/**
 * 音。**グラスからは鳴らない**ので、ここで合わせるのはスマホのスピーカーの音量。
 *
 * 適正な音量は場所（屋外の暗騒音）と機種で変わるので、合わせた値は端末に覚えさせる。
 */
@Composable
internal fun SoundSettings(
    aiVoice: Boolean,
    onAiVoiceChange: (Boolean) -> Unit,
    voiceVolume: Float,
    onVoiceVolumeChange: (Float) -> Unit,
    onVoiceVolumeCommit: () -> Unit,
    bgmOn: Boolean,
    onBgmChange: (Boolean) -> Unit,
    bgmVolume: Float,
    onBgmVolumeChange: (Float) -> Unit,
    onBgmVolumeCommit: () -> Unit,
    bgmPlaying: BgmTrack?,
    bgmPinned: BgmTrack?,
    onBgmPinnedChange: (BgmTrack?) -> Unit,
) {
    SettingsSection("音", "鳴るのはスマホのスピーカー。グラスにスピーカーは無い") {
        // 端末の読み上げは棒読みで雰囲気を壊す。既定は AI 音声で、
        // 圏外や API キー無しのときは自動で端末の読み上げに落ちる
        SettingSwitch(
            if (aiVoice) "声: AI 音声（落ち着いた解説員）" else "声: 端末の読み上げ",
            aiVoice,
            onAiVoiceChange,
        )
        Text(
            "読み上げの音量 %d%%".format((voiceVolume * 100).roundToInt()),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = voiceVolume,
            onValueChange = onVoiceVolumeChange,
            onValueChangeFinished = onVoiceVolumeCommit,
        )
        SettingSwitch(
            if (bgmOn) "BGM（いま ${bgmPlaying?.title ?: "止まっている"}）" else "BGM なし",
            bgmOn,
            onBgmChange,
        )
        Text(
            "BGM の音量 %d%%（解説中は自動で下がる）".format((bgmVolume * 100).roundToInt()),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = bgmVolume,
            onValueChange = onBgmVolumeChange,
            onValueChangeFinished = onBgmVolumeCommit,
            enabled = bgmOn,
        )
        if (bgmOn) BgmPicker(bgmPinned, onBgmPinnedChange)
        // CC BY 4.0 は帰属の表示が条件。NOTICE はアプリの利用者には見えないので、ここにも出しておく
        Text(
            "BGM: ${BgmTrack.credit}",
            style = MaterialTheme.typography.bodySmall,
            color = SaberaFinePrint,
        )
    }
}

/**
 * 曲を指名する。**既定はおまかせ**（空の明るさとガイドで勝手に選ぶ）。
 *
 * 空の濃さ（[SkyViewSettings]）のような**横並びにはしない。** 曲名は横に並べると
 * 入りきらないうえ、どれがどんな曲かの手がかり（[BgmTrack.mood]）も置けなくなる。
 *
 * 場面ごとに見出しを付けるのは、**おまかせのときに何が鳴るのかをここで見せる**ため。
 * 指名するとその 1 曲だけを繰り返すので、場面が変わっても入れ替わらない。
 */
@Composable
private fun BgmPicker(pinned: BgmTrack?, onChange: (BgmTrack?) -> Unit) {
    Spacer(Modifier.height(4.dp))
    BgmChoice("おまかせ（空とガイドに合わせて選ぶ）", null, pinned == null) { onChange(null) }
    for (scene in BgmScene.entries) {
        Text(
            scene.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        for (candidate in scene.tracks) {
            BgmChoice(candidate.title, candidate.mood, pinned == candidate) { onChange(candidate) }
        }
    }
}

@Composable
private fun BgmChoice(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) SaberaSelected else Color.Transparent,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    Color.White.copy(alpha = 0.72f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 観測の状態と観測地。
 *
 * 6DoF が来ているか・方位合わせのばらつき・どこで観測しているかは、屋外で「出ない」と
 * 言われたときに最初に見る場所（13_field-check.md）。
 *
 * **描画と転送の時間はここに出さない。** 送信のたびにログの 1 行に入っているので、
 * 同じ数字を 2 か所に置くとパネルが数字で埋まる。
 */
@Composable
internal fun ObservationStatusCard(
    imuStarted: Boolean,
    calibration: CalibrationResult?,
    siteSource: String,
    latText: String,
    onLatChange: (String) -> Unit,
    lonText: String,
    onLonChange: (String) -> Unit,
    onLocate: () -> Unit,
    onRecalibrate: () -> Unit,
) {
    SettingsSection("観測の状態") {
        StatusRow("6DoF", if (imuStarted) "受信中" else "停止中（グラスが 2.0.0 未満かも）")
        StatusRow(
            "方位合わせ",
            calibration?.let {
                "${(System.currentTimeMillis() - it.calibratedAt) / 1000} 秒前・" +
                    "方位±%.1f° / 仰角±%.1f°（%d件）".format(
                        it.headingStdDeg,
                        it.pitchStdDeg,
                        it.sampleCount,
                    )
            } ?: "まだ",
        )
        StatusRow("観測地", siteSource)
        Spacer(Modifier.height(8.dp))
        CommandButton("方位を合わせる", onClick = onRecalibrate)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) {
            Text("現在地を取り直す")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = latText,
            onValueChange = onLatChange,
            label = { Text("緯度") },
            isError = latText.toDoubleOrNull() == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = lonText,
            onValueChange = onLonChange,
            label = { Text("経度") },
            isError = lonText.toDoubleOrNull() == null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 観測の記録。
 *
 * 画面に出るのは直近の数十行だけ。**長い計測はファイルに残っている**ので、
 * 何バイト溜まっているかを出して書き出しと消去へ導く（tools/pull-session-log.sh）。
 */
@Composable
internal fun SessionLogCard(
    logBytes: Long,
    visibleLines: Int,
    lines: List<LogLine>,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsSection("記録", "記録 %.1f KB（画面は直近 %d 行）".format(logBytes / 1024.0, visibleLines)) {
        Row {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Text("記録を書き出す")
            }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("記録を消す") }
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
        ) {
            if (lines.isEmpty()) {
                Text("まだ何も送っていない", style = MaterialTheme.typography.bodySmall)
            }
            for (line in lines) {
                Text(
                    "${line.at}  ${line.text}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** ラベルと値を 3:7 で並べる 1 行。設定パネルの状態表示で共通に使う */
@Composable
internal fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.3f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.7f))
    }
}
