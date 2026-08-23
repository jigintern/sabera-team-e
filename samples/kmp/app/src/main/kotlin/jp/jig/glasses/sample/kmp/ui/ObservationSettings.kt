package jp.jig.glasses.sample.kmp.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import jp.jig.glasses.sample.kmp.satellite.SatelliteScene
import jp.jig.glasses.sample.kmp.starmap.CalibrationResult
import jp.jig.glasses.sample.kmp.starmap.SkyDensity
import kotlin.math.roundToInt

/**
 * 観測画面の設定パネル（上のバーの「設定」で開くほう）。
 *
 * **見出しの中身を見出しどおりにする。** 以前はここが「ログ」という 1 枚のカードで、
 * その中に星座絵・目印・声・BGM・音量まで入っていた。**BGM を切りたい人はログを開かない**ので、
 * 触れるはずの設定に辿り着けなかった。
 *
 * 星図そのものの表示（プレビュー・解説・ボタン 2 つ）はパネルを閉じた側に残す。
 * ここは**同伴者がスマホで見るぶん**と、**実機で数字を確かめるぶん**。
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
 * いま空に出ている名前つきの衛星。**同伴者がスマホで見るためのもの。**
 *
 * グラスのタップは星座の解説に使うので（#36）、衛星の案内はここから始める。
 */
@Composable
internal fun SatellitesInSkyCard(
    sightings: List<SatelliteScene.Sighting>,
    onNarrate: () -> Unit,
) {
    SettingsSection("いま空に出ている", "● は日が当たっていて肉眼でも見える可能性がある。○ は地球の影") {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
        ) {
            if (sightings.isEmpty()) {
                Text("名前つきの衛星が空に出ていない", style = MaterialTheme.typography.bodyMedium)
            }
            for (sighting in sightings) {
                Text(
                    "${if (sighting.sunlit) "●" else "○"} ${sighting.name}　${sighting.where}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 「上昇中・最接近まで 3 分」。点の位置だけでは待つ価値が分からない
                if (sighting.timing.isNotEmpty()) {
                    Text(
                        "　　${sighting.timing}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onNarrate,
            enabled = sightings.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("この空の衛星を案内する") }
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
    bgmTrackLabel: String?,
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
            if (bgmOn) "BGM（いま ${bgmTrackLabel ?: "止まっている"}）" else "BGM なし",
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
        // CC BY 4.0 は帰属の表示が条件。NOTICE はアプリの利用者には見えないので、ここにも出しておく
        Text(
            "BGM: Silver Blue Light / Fluidscape by Kevin MacLeod (incompetech.com) CC BY 4.0",
            style = MaterialTheme.typography.bodySmall,
            color = SaberaFinePrint,
        )
    }
}

/**
 * 観測の状態と観測地。**実機で数字を確かめるための区画。**
 *
 * 6DoF が来ているか・方位合わせのばらつき・描画と転送にかかった時間は、
 * 屋外で「出ない」と言われたときに最初に見る場所（field-check.md）。
 */
@Composable
internal fun ObservationStatusCard(
    imuStarted: Boolean,
    calibration: CalibrationResult?,
    panelSize: String,
    mapSize: String?,
    siteSource: String,
    latText: String,
    onLatChange: (String) -> Unit,
    lonText: String,
    onLonChange: (String) -> Unit,
    onLocate: () -> Unit,
    onRecalibrate: () -> Unit,
    onSendNow: () -> Unit,
    onClearGlass: () -> Unit,
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
        StatusRow("星図の大きさ", panelSize)
        if (mapSize != null) {
            Text(mapSize, style = MaterialTheme.typography.bodySmall)
        }
        StatusRow("観測地", siteSource)
        Spacer(Modifier.height(8.dp))
        CommandButton("方位を合わせる", onClick = onRecalibrate)
        Row {
            OutlinedButton(onClick = onSendNow, modifier = Modifier.weight(1f)) { Text("いま送る") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = onClearGlass, modifier = Modifier.weight(1f)) { Text("表示を消す") }
        }
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
