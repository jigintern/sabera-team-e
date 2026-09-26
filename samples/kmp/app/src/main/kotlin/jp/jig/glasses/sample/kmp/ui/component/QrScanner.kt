package jp.jig.glasses.sample.kmp.ui.component

import androidx.activity.compose.LocalActivity
import android.content.pm.ActivityInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import jp.jig.glasses.sample.kmp.support.QrCode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * カメラを開いて QR を探す。読めたら [onDecoded] を**一度だけ**呼ぶ。
 *
 * 台本を運ぶのは文字ではなく生バイトなので、渡すのも `ByteArray`（`support/QrCode`）。
 *
 * **`CameraX` を直に使う。** ZXing Android Embedded は専用の Activity を持ち込み、
 * 自分で画面の向きを決めてしまう。**向きを決めるのはこちら**で、
 * 回ると Activity が作り直されて置いた枠の記憶が消える。
 *
 * **開いている間だけ画面を縦へ固定する**（#130）。方位合わせと同じ扱い。
 */
@Composable
internal fun QrScanner(
    onDecoded: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 解析は 1 本の専用スレッドで回す。**次のコマを待たせない**
    val executor = remember { Executors.newSingleThreadExecutor() }
    // 読めた瞬間に何度も呼ばれると、確認の画面が積み上がる
    val done = remember { AtomicBoolean(false) }
    val previewView = remember { PreviewView(context) }

    // **横持ちのまま開くとプレビューが崩れる**（#130）。カメラが開いている間だけ縦へ固定し、
    // 閉じたら端末の回転設定へ戻す。Activity は configChanges で回転を受けるので、
    // ここで向きを変えても作り直されず、束縛し直しも起きない。
    // **カメラを束縛する前に固定する**（後だと横向きのまま 1 度映る）
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (activity != null && previous != null) activity.requestedOrientation = previous
        }
    }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        // **初期化を待っている間に閉じられる。** リスナーは `onDispose` のあとでも
        // メイン executor 上で動くので、束縛してよいかどうかをここで見る（#164）
        val disposed = AtomicBoolean(false)
        val listener = Runnable {
            val provider = runCatching { future.get() }.getOrNull() ?: return@Runnable
            // **もう閉じている。束縛しない。**
            // ここで `unbindAll()` もしない — 画面へ入り直していれば、
            // **次の QrScanner が束縛したカメラを解いてしまう**
            if (disposed.get()) return@Runnable
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                // 溜めずに**いま写っているコマだけ**見る。QR は待てば次のコマで読める
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(executor) { image -> scan(image, done, onDecoded) } }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }
        future.addListener(listener, context.mainExecutor)
        onDispose {
            disposed.set(true)
            // **リスナーと `onDispose` はどちらもメインスレッドで動く**ので、
            // 順番はこの 2 通りしかない。
            //   束縛済み → `isDone` が true。ここで解く
            //   まだ    → 束縛は起きていない。リスナーが上の札を見て降りる
            // `isDone` を見ずに `future.get()` を呼ぶと、**初期化が終わるまで
            // メインスレッドを止める**（100〜500ms のフリーズ）。
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun scan(image: ImageProxy, done: AtomicBoolean, onDecoded: (ByteArray) -> Unit) {
    try {
        if (done.get()) return
        val bytes = image.luminance() ?: return
        val decoded = QrCode.decode(bytes, image.width, image.height) ?: return
        // **最初の 1 枚だけ通す。** 読めている間はコマごとに成功する
        if (done.compareAndSet(false, true)) onDecoded(decoded)
    } finally {
        image.close()
    }
}

/**
 * YUV の Y 平面だけ取り出す。**色は要らない。**
 *
 * `rowStride` は幅より広いことがある（端末が確保しやすい幅へ丸める）ので、
 * そのまま渡すと**斜めにずれた画像**を読ませることになる。
 */
private fun ImageProxy.luminance(): ByteArray? {
    val plane = planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    if (rowStride == width) {
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    val out = ByteArray(width * height)
    val row = ByteArray(rowStride)
    for (y in 0 until height) {
        if (buffer.remaining() < rowStride) break
        buffer.get(row, 0, rowStride)
        row.copyInto(out, y * width, 0, width)
    }
    return out
}
