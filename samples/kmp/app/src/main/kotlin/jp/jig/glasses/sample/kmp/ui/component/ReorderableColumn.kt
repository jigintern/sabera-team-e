package jp.jig.glasses.sample.kmp.ui.component

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex

/**
 * 長押しして掴み、上下に動かして並べ替える列。**依存を足さずに書く。**
 *
 * 段は 8〜9 個しかないので `LazyColumn` は要らない。Compose に並べ替えは入っておらず、
 * 足すほどの話でもない（SDK が持っているものを自前で書くな、の逆で**誰も持っていない**）。
 *
 * **掴んでいる間は並びを変えない。**
 * 動かすたびに `items` を入れ替えると、掴んでいる要素の index が変わり、
 * その index で作った `pointerInput` が作り直されて**指を離す前にドラッグが切れる**。
 * 見た目だけずらしておいて、**離したときに 1 回だけ [onMove] を呼ぶ**。
 *
 * 掴む場所は [itemContent] が受け取る `handle` を置いたところだけ。
 * カード全体を掴めるようにすると、中の入力欄を長押ししたときに動き出す。
 */
@Composable
internal fun <T> ReorderableColumn(
    items: List<T>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (index: Int, item: T, handle: Modifier) -> Unit,
) {
    // ジェスチャーの中から呼ぶものは、**組み立て時の値を捕まえない**ように包む
    val move = rememberUpdatedState(onMove)
    val count = items.size
    var dragging by remember { mutableIntStateOf(NONE) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 高さは段ごとに違う（本文の長さで伸びる）ので、実測を覚えて割り出す
    val heights = remember { mutableStateMapOf<Int, Int>() }

    val from = dragging
    val target = if (from == NONE) NONE else targetIndex(from, offsetY, items.size, heights)
    val liftedHeight = (heights[from] ?: 0).toFloat()

    Column(modifier) {
        items.forEachIndexed { index, item ->
            val held = index == from
            // 掴んだ要素が抜けた/入った先の要素を、その高さぶんだけずらして見せる
            val shift = when {
                from == NONE || held -> 0f
                target > from && index in (from + 1)..target -> -liftedHeight
                target < from && index in target..(from - 1) -> liftedHeight
                else -> 0f
            }
            Box(
                Modifier
                    .zIndex(if (held) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (held) offsetY else shift
                        // 掴んでいるものを薄くして、下の並びが読めるようにする
                        alpha = if (held) 0.85f else 1f
                    }
                    .onGloballyPositioned { heights[index] = it.size.height },
            ) {
                itemContent(
                    index,
                    item,
                    // **index を鍵に含める。** 含めないと、並べ替えたあとも
                    // 最初に作られたときの index を掴み続けて、別の段が動く
                    Modifier.pointerInput(index, count) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = index
                                offsetY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                offsetY += amount.y
                            },
                            onDragEnd = {
                                // **行き先はここで計算し直す。** 組み立て時の値を捕まえると、
                                // 掴んでいる間に動かしたぶんが反映されない
                                val to = targetIndex(index, offsetY, count, heights)
                                if (to != index) move.value(index, to)
                                dragging = NONE
                                offsetY = 0f
                            },
                            onDragCancel = {
                                dragging = NONE
                                offsetY = 0f
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * どこまで動いたか。**隣の半分を越えたら 1 つ進む**（越えるたびに残りから引く）。
 *
 * 高さをまだ測っていない段に当たったらそこで止める。測る前に飛ばすと、
 * **画面の外にある段まで一気に動く**。
 */
private fun targetIndex(from: Int, offsetY: Float, count: Int, heights: Map<Int, Int>): Int {
    var target = from
    var remaining = offsetY
    if (remaining > 0f) {
        var i = from + 1
        while (i <= count - 1) {
            val height = heights[i]?.toFloat() ?: break
            if (remaining < height / 2f) break
            target = i
            remaining -= height
            i++
        }
    } else {
        var i = from - 1
        while (i >= 0) {
            val height = heights[i]?.toFloat() ?: break
            if (-remaining < height / 2f) break
            target = i
            remaining += height
            i--
        }
    }
    return target
}

private const val NONE = -1
