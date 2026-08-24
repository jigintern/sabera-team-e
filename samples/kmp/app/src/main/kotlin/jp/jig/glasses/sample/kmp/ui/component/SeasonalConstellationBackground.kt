package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.Calendar
import kotlin.random.Random

data class ConstellationBackground(
    val name: String,
    val season: String,
    val stars: List<BackgroundStar>,
    val lines: List<Pair<Int, Int>>,
)

data class BackgroundStar(
    val x: Float,
    val y: Float,
    val major: Boolean = false,
)

@Composable
fun rememberSeasonalConstellation(): ConstellationBackground {
    val month = remember { Calendar.getInstance().get(Calendar.MONTH) + 1 }
    val candidates = remember(month) { seasonalCandidates(month) }
    val selectedIndex by rememberSaveable(month) {
        mutableIntStateOf(Random.nextInt(candidates.size))
    }
    return candidates[selectedIndex]
}

/**
 * 右下の星座名が要る高さ（余白 20dp ＋ labelMedium の行送り）。
 *
 * **上に板を重ねる側はここを空ける。** 数値を重ねる側に持たせると必ずずれるので、
 * 名前を描いているこちらが宣言する。
 */
val BACKGROUND_LABEL_CLEARANCE = 40.dp

@Composable
fun SeasonalConstellationBackground(
    constellation: ConstellationBackground,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color(0xFF050B16))) {
        Canvas(Modifier.fillMaxSize()) {
            val faintStars = listOf(
                0.07f to 0.10f,
                0.21f to 0.07f,
                0.41f to 0.12f,
                0.73f to 0.08f,
                0.91f to 0.17f,
                0.12f to 0.43f,
                0.35f to 0.51f,
                0.61f to 0.48f,
                0.92f to 0.44f,
                0.05f to 0.69f,
                0.25f to 0.76f,
                0.46f to 0.84f,
                0.68f to 0.89f,
                0.93f to 0.78f,
            )
            faintStars.forEachIndexed { index, (x, y) ->
                drawCircle(
                    color = Color.White.copy(alpha = 0.25f),
                    radius = if (index % 4 == 0) 2.2.dp.toPx() else 1.2.dp.toPx(),
                    center = Offset(size.width * x, size.height * y),
                )
            }

            fun position(star: BackgroundStar) = Offset(size.width * star.x, size.height * star.y)
            constellation.lines.forEach { (from, to) ->
                drawLine(
                    color = Color(0xFFB6FFD1).copy(alpha = 0.24f),
                    start = position(constellation.stars[from]),
                    end = position(constellation.stars[to]),
                    strokeWidth = 1.4.dp.toPx(),
                )
            }
            constellation.stars.forEach { star ->
                drawCircle(
                    color = Color(0xFFDFFFEA).copy(alpha = 0.88f),
                    radius = if (star.major) 4.2.dp.toPx() else 2.5.dp.toPx(),
                    center = position(star),
                )
            }
        }

        Text(
            text = "${constellation.season}の星座・${constellation.name}",
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.48f),
        )
    }
}

private fun seasonalCandidates(month: Int): List<ConstellationBackground> = when (month) {
    in 3..5 -> listOf(leo(), virgo())
    in 6..8 -> listOf(cygnus(), scorpius())
    in 9..11 -> listOf(pegasus(), andromeda())
    else -> listOf(orion(), taurus())
}

private fun leo() = ConstellationBackground(
    name = "しし座",
    season = "春",
    stars = stars(0.16f, 0.30f, 0.27f, 0.24f, 0.37f, 0.31f, 0.34f, 0.43f, 0.49f, 0.50f, 0.68f, 0.43f, 0.82f, 0.52f),
    lines = chain(0, 1, 2, 3, 0) + chain(3, 4, 5, 6),
)

private fun virgo() = ConstellationBackground(
    name = "おとめ座",
    season = "春",
    stars = stars(0.17f, 0.31f, 0.35f, 0.38f, 0.51f, 0.46f, 0.68f, 0.39f, 0.82f, 0.29f, 0.58f, 0.66f, 0.72f, 0.77f, majorIndex = 5),
    lines = chain(0, 1, 2, 3, 4) + chain(2, 5, 6),
)

private fun cygnus() = ConstellationBackground(
    name = "はくちょう座",
    season = "夏",
    stars = stars(0.52f, 0.16f, 0.50f, 0.34f, 0.46f, 0.59f, 0.25f, 0.39f, 0.75f, 0.36f, majorIndex = 0),
    lines = chain(0, 1, 2) + listOf(3 to 1, 1 to 4),
)

private fun scorpius() = ConstellationBackground(
    name = "さそり座",
    season = "夏",
    stars = stars(0.20f, 0.25f, 0.29f, 0.34f, 0.39f, 0.43f, 0.50f, 0.55f, 0.56f, 0.69f, 0.68f, 0.78f, 0.80f, 0.72f, 0.86f, 0.58f, majorIndex = 2),
    lines = chain(0, 1, 2, 3, 4, 5, 6, 7),
)

private fun pegasus() = ConstellationBackground(
    name = "ペガスス座",
    season = "秋",
    stars = stars(0.24f, 0.28f, 0.69f, 0.25f, 0.72f, 0.63f, 0.28f, 0.66f, 0.13f, 0.48f, 0.84f, 0.43f, majorIndex = 0),
    lines = chain(0, 1, 2, 3, 0) + listOf(0 to 4, 1 to 5),
)

private fun andromeda() = ConstellationBackground(
    name = "アンドロメダ座",
    season = "秋",
    stars = stars(0.16f, 0.58f, 0.31f, 0.49f, 0.48f, 0.39f, 0.66f, 0.31f, 0.83f, 0.20f, 0.56f, 0.57f, 0.73f, 0.66f, majorIndex = 2),
    lines = chain(0, 1, 2, 3, 4) + listOf(2 to 5, 5 to 6),
)

private fun orion() = ConstellationBackground(
    name = "オリオン座",
    season = "冬",
    stars = stars(0.28f, 0.22f, 0.70f, 0.25f, 0.34f, 0.72f, 0.72f, 0.70f, 0.43f, 0.47f, 0.52f, 0.48f, 0.61f, 0.49f, majorIndex = 3),
    lines = listOf(0 to 4, 4 to 2, 1 to 6, 6 to 3, 0 to 1, 2 to 3, 4 to 5, 5 to 6),
)

private fun taurus() = ConstellationBackground(
    name = "おうし座",
    season = "冬",
    stars = stars(0.16f, 0.25f, 0.39f, 0.43f, 0.51f, 0.53f, 0.67f, 0.40f, 0.85f, 0.21f, 0.53f, 0.70f, majorIndex = 2),
    lines = chain(0, 1, 2, 3, 4) + listOf(2 to 5),
)

private fun stars(vararg coordinates: Float, majorIndex: Int = 0): List<BackgroundStar> =
    coordinates.toList().chunked(2).mapIndexed { index, pair ->
        BackgroundStar(pair[0], pair[1], major = index == majorIndex)
    }

private fun chain(vararg indices: Int): List<Pair<Int, Int>> = indices.toList().zipWithNext()
