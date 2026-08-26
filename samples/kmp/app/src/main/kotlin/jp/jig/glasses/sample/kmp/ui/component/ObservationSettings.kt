package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.jig.glasses.sample.kmp.alignment.CalibrationResult
import jp.jig.glasses.sample.kmp.glass.StarMapInk
import jp.jig.glasses.sample.kmp.glass.StarMapLayer
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
 * **屋外で触るのは空の濃さ・明るさ・音量の 3 つだけ。** 区画を 9 つとも開いておくと、
 * いちばん効くものが画面の外へ流れる。それ以外は畳み（[CollapsibleSection]）、
 * **畳んだ区画も見出しに要約を出す**ので、開かずに状態は読める。
 * **畳むのであって消さない**（緯度経度もログも 13_field-check.md の手順が指す先）。
 *
 * **アイコンを添える。** 暗い屋外では文字より形のほうが速く見つかる。
 * 出す／出さないの 3 つは 1 行の札にし（[IconToggle]）、音量は左のアイコンが入／切を兼ねる。
 *
 * 星図そのものの表示（プレビュー・解説・ボタン 2 つ）はパネルを閉じた側に残す。
 */

/** ログ 1 行。失敗だけ色を変えたいので持っておく */
internal data class LogLine(val at: String, val text: String, val failed: Boolean)

/**
 * 設定パネルの 1 区画。**見出しと中身を必ず組にする**（見出しの外に設定を置かない）。
 *
 * **見出しは 1 行に畳む。** 以前は見出し・説明・カードで 3 行使っていて、
 * 区画が 9 つあると設定に辿り着く前に指が疲れた。アイコンを左に置くのは、
 * **暗い屋外では文字より形のほうが速く見つかる**から。
 */
@Composable
internal fun SettingsSection(
    title: String,
    icon: ImageVector,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            SectionHeader(title, icon, hint)
            content()
        }
    }
}

/**
 * 開くまで中身を出さない区画。
 *
 * **普段は触らない設定を畳む。** 観測中に触るのは空の濃さ・明るさ・音量で、
 * 緯度経度の手入力やログはトラブルのときにしか要らない。畳んでおけば、
 * よく使うものが 1 画面に収まる。
 *
 * 開閉は覚えない（パネルを閉じたら畳んだ状態に戻る）。
 * **次に開いたときもコンパクトなのが既定**でないと、畳んだ意味がなくなる。
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    hint: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SaberaSurface),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            SectionHeader(title, icon, hint, expanded, onToggle = { expanded = !expanded })
            if (expanded) content()
        }
    }
}

/** 見出しの 1 行。アイコン・見出し・要約を横に並べる（開閉できるときは矢印も） */
@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    hint: String?,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            // **要約は見出しの下に小さく。** 畳んでいる間は、開かずに中身が分かる唯一の手がかり
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded != null && onToggle != null) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                if (expanded) "畳む" else "開く",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
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
 * 出す／出さないを 1 つのアイコンで表す札。
 *
 * **3 つのスイッチで 3 行使っていたものを 1 行にする。** 出しているかどうかは
 * 色が付いているかで分かるので、「星座絵: 出す」という文まで要らない。
 * 文字を消してしまうと何のアイコンか分からないので、**ラベルは小さく残す**。
 */
@Composable
private fun IconToggle(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    // **1 つずつ独立したボタンに見せる。** 敷いた緑が隣とつながると、
    // 3 つで 1 本の帯に見えて、どれが入っているのか読めなくなる
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (checked) SaberaSelected else Color.Transparent)
            .border(1.dp, if (checked) SaberaSelected else SaberaFinePrint.copy(alpha = 0.45f), shape)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (checked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * つまみ 1 本。**左のアイコンがそのまま入／切のボタン**になる。
 *
 * 「BGM」のスイッチと「BGM の音量」のつまみで 3 行使っていたが、
 * **切りたい人はつまみを 0 にすればよい**わけではない（0 のまま鳴り続ける）ので、
 * 入／切はアイコンに残して行を詰めた。
 */
@Composable
private fun SliderRow(
    label: String,
    icon: ImageVector,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean = true,
    onIconClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onIconClick != null) {
            IconButton(onClick = onIconClick) {
                Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            Icon(
                icon,
                label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp).size(22.dp),
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            "%d%%".format((value * 100).roundToInt()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp).width(36.dp),
        )
    }
}

/** アイコンだけの操作。**言葉より短く、押せることは形で分かる** */
@Composable
private fun IconAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
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
    CollapsibleSection(
        "今夜見た星座",
        Icons.Filled.Star,
        "%d 星座".format(entries.map { it.nameJa }.distinct().size),
    ) {
        // 新しいものが上。**下に伸びると、読みたい直前の解説がいちばん遠くなる**
        for (entry in entries.asReversed().take(SHOWN_RECORDS)) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${clock.format(Date(entry.atMillis))}　${entry.nameJa}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconAction("もう一度", Icons.Filled.Replay) { onAgain(entry) }
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
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("今夜の記録を消す", style = MaterialTheme.typography.labelMedium)
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
    CollapsibleSection("声で聞いたこと", Icons.Filled.Mic, "%d 件".format(exchanges.size)) {
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
                    IconAction("もう一度", Icons.Filled.Replay) { onAgain(exchange) }
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
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("やり取りを消す", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * グラスに何を描くか。
 *
 * **空の濃さがいちばん効く。** 見えていない星まで描くと、目の前の空と対応が取れなくなる。
 *
 * **出す／出さないの 3 つは 1 行に畳んだ**（星座絵・目印・人工衛星）。スイッチ 3 段だと
 * いちばん効く空の濃さが画面の外へ流れていた。**下敷きの濃さは開くまで出さない**。
 * 現地で追い込むためのもので、初めて開いた人が最初に触るものではない。
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
        Icons.Filled.Visibility,
        // **見えない星を描かないのがいちばん効く**（見えている星と対応が取れなくなる）
        "${density.label}・${"%.1f".format(density.limitMagnitude)} 等まで",
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            for (step in SkyDensity.entries) {
                val selected = step == density
                TextButton(
                    onClick = { onDensityChange(step) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
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
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconToggle("星座絵", Icons.Filled.AutoAwesome, showArt, Modifier.weight(1f), onArtChange)
            IconToggle("目印", Icons.Filled.Explore, showGuides, Modifier.weight(1f), onGuidesChange)
            IconToggle(
                "人工衛星",
                Icons.Filled.SatelliteAlt,
                showSatellites,
                Modifier.weight(1f),
                onSatellitesChange,
            )
        }
    }
}

/**
 * 下敷きの濃さ。**開発者用画面に置く。**
 *
 * **屋内で決めた濃さは屋外の暗闇では必ず明るすぎる**ので現地で動かせるようにしてあるが、
 * 初めて開いた人が最初に触るものではない。見え方の区画に混ぜると、
 * いちばん効く空の濃さがその下に隠れる。
 */
@Composable
internal fun InkSettings(ink: StarMapInk, onInkChange: (StarMapInk) -> Unit) {
    CollapsibleSection("下敷きの濃さ", Icons.Filled.Tune, "段で決める（グラスは緑 8 階調）") {
        // 星はここに入れない（等級を明るさで表しているので、一律に動かすと差が潰れる）
        for (layer in StarMapLayer.entries) {
            InkStepper(layer, ink.level(layer)) { onInkChange(ink.with(layer, it)) }
        }
    }
}

/**
 * 層 1 つぶんの濃さ。
 *
 * **段そのものを出す。** グラスは緑 8 階調しか出せないので、0〜100% のつまみにすると
 * 「動かしたのに何も変わらない」幅ができる。段なら 1 押しがそのまま実機の 1 段になる。
 */
@Composable
private fun InkStepper(layer: StarMapLayer, level: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${layer.label} $level/${StarMapLayer.MAX_LEVEL}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onChange(level - 1) },
            enabled = level > StarMapLayer.MIN_LEVEL,
        ) { Icon(Icons.Filled.Remove, "薄く") }
        IconButton(
            onClick = { onChange(level + 1) },
            enabled = level < StarMapLayer.MAX_LEVEL,
        ) { Icon(Icons.Filled.Add, "濃く") }
    }
}

/**
 * 声。**グラスからは鳴らない**ので、合わせるのはスマホのスピーカーの音量。
 *
 * 適正な音量は場所（屋外の暗騒音）と機種で変わるので、合わせた値は端末に覚えさせる。
 * **曲とは分ける** — 音量を下げたいのがどちらなのかは場面ごとに違う。
 */
@Composable
internal fun VoiceSettings(
    aiVoice: Boolean,
    onAiVoiceChange: (Boolean) -> Unit,
    voiceVolume: Float,
    onVoiceVolumeChange: (Float) -> Unit,
    onVoiceVolumeCommit: () -> Unit,
) {
    SettingsSection(
        "声",
        Icons.Filled.RecordVoiceOver,
        "スマホから鳴る（グラスにスピーカーは無い）",
    ) {
        SliderRow(
            "音量",
            if (voiceVolume > 0f) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
            voiceVolume,
            onVoiceVolumeChange,
            onVoiceVolumeCommit,
        )
        // 端末の読み上げは棒読みで雰囲気を壊す。既定は AI 音声で、
        // 圏外や API キー無しのときは自動で端末の読み上げに落ちる
        SettingSwitch(
            if (aiVoice) "AI 音声（落ち着いた解説員）" else "端末の読み上げ",
            aiVoice,
            onAiVoiceChange,
        )
    }
}

/**
 * 曲（BGM）。**入／切は左のアイコンが兼ねる**（スイッチとつまみで 2 行使わない）。
 *
 * 曲の指名は一度決めたらそのままなので、開くまで出さない。
 */
@Composable
internal fun BgmSettings(
    bgmOn: Boolean,
    onBgmChange: (Boolean) -> Unit,
    bgmVolume: Float,
    onBgmVolumeChange: (Float) -> Unit,
    onBgmVolumeCommit: () -> Unit,
    bgmPlaying: BgmTrack?,
    bgmPinned: BgmTrack?,
    onBgmPinnedChange: (BgmTrack?) -> Unit,
) {
    SettingsSection(
        "曲",
        if (bgmOn) Icons.Filled.MusicNote else Icons.Filled.MusicOff,
        if (bgmOn) "いま ${bgmPlaying?.title ?: "止まっている"}・解説中は自動で下がる" else "鳴らさない",
    ) {
        SliderRow(
            "音量",
            if (bgmOn) Icons.Filled.MusicNote else Icons.Filled.MusicOff,
            bgmVolume,
            onBgmVolumeChange,
            onBgmVolumeCommit,
            enabled = bgmOn,
            onIconClick = { onBgmChange(!bgmOn) },
        )
        if (bgmOn) InlineDisclosure("曲を選ぶ") { BgmPicker(bgmPinned, onBgmPinnedChange) }
    }
}

/** 区画の中でさらに畳む 1 行。**普段は触らないものを、消さずに隠す** */
@Composable
private fun InlineDisclosure(label: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            if (expanded) "畳む" else "開く",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    if (expanded) Column(Modifier.fillMaxWidth()) { content() }
}

/**
 * 同梱物の出典。**設定のいちばん下に、いちばん小さく置く。**
 *
 * **CC BY 4.0 は帰属の表示が条件**で、`NOTICE` はアプリの利用者には見えない。
 * ただし読ませたい文ではないので、区画にはしない（開く手間のぶんだけ場所を取る）。
 */
@Composable
internal fun CreditsFootnote() {
    Spacer(Modifier.height(20.dp))
    Text(
        "BGM: ${BgmTrack.credit}　" +
            "星座絵: The 88 Constellations by NOIRLab/NSF/AURA CC BY 4.0（改変あり）",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        color = SaberaFinePrint,
    )
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
    CollapsibleSection(
        "観測の状態",
        Icons.Filled.MyLocation,
        // **畳んでいる間もここだけは読める。** 「出ない」と言われて最初に見るのが 6DoF
        (if (imuStarted) "6DoF 受信中" else "6DoF 停止中") +
            (calibration?.let { "・方位±%.1f°".format(it.headingStdDeg) } ?: "・方位合わせまだ"),
    ) {
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onRecalibrate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Explore, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("方位を合わせる", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onLocate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.MyLocation, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("現在地", style = MaterialTheme.typography.labelMedium)
            }
        }
        // **緯度経度の手入力は、現在地が取れないときの逃げ道。** 普段は開かない
        var manual by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { manual = !manual }, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (manual) "緯度経度を畳む" else "緯度経度を手で入れる",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (manual) {
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
    CollapsibleSection(
        "記録",
        Icons.Filled.Description,
        "%.1f KB（画面は直近 %d 行）".format(logBytes / 1024.0, visibleLines),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("書き出す", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("消す", style = MaterialTheme.typography.labelMedium)
            }
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
